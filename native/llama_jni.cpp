#include <jni.h>

#include "llama.h"

#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

std::once_flag backendInitFlag;

struct NativeSession {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    // PROVISIONAL - pending GEN-PERF-1
    uint32_t contextTokens = 0;
    std::mutex mutex;

    ~NativeSession() {
        if (context != nullptr) {
            llama_free(context);
        }
        if (model != nullptr) {
            llama_model_free(model);
        }
    }
};

// PROVISIONAL - pending GEN-PERF-1
constexpr int32_t kSingleTokenBatchSize = 1;
// PROVISIONAL - pending GEN-PERF-1
constexpr int32_t kLlamaDecodeSuccess = 0;
// PROVISIONAL - pending GEN-PERF-1
constexpr int32_t kLlamaDecodeNoKvSlot = 1;
// PROVISIONAL - pending GEN-PERF-1
constexpr jlong kInvalidNativeHandle = 0;

void checkDecodeStatus(int32_t status) {
    if (status == kLlamaDecodeSuccess) {
        return;
    }
    if (status == kLlamaDecodeNoKvSlot) {
        throw std::bad_alloc();
    }
    throw std::runtime_error("Pinned llama.cpp decode failed with status " + std::to_string(status));
}

void throwJava(JNIEnv * env, const char * className, const std::string & message) {
    if (env->ExceptionCheck()) {
        return;
    }
    jclass exceptionClass = env->FindClass(className);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
        env->DeleteLocalRef(exceptionClass);
    }
}

void appendUtf8(std::string & output, uint32_t codePoint) {
    if (codePoint <= 0x7f) {
        output.push_back(static_cast<char>(codePoint));
    } else if (codePoint <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (codePoint >> 6)));
        output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
    } else if (codePoint <= 0xffff) {
        output.push_back(static_cast<char>(0xe0 | (codePoint >> 12)));
        output.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
    } else {
        output.push_back(static_cast<char>(0xf0 | (codePoint >> 18)));
        output.push_back(static_cast<char>(0x80 | ((codePoint >> 12) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
    }
}

std::string fromJavaString(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) {
        throw std::runtime_error("Could not read a Java string");
    }

    std::string output;
    for (jsize index = 0; index < length; ++index) {
        uint32_t codePoint = chars[index];
        if (codePoint >= 0xd800 && codePoint <= 0xdbff && index + 1 < length) {
            const uint32_t low = chars[index + 1];
            if (low >= 0xdc00 && low <= 0xdfff) {
                codePoint = 0x10000 + ((codePoint - 0xd800) << 10) + (low - 0xdc00);
                ++index;
            } else {
                codePoint = 0xfffd;
            }
        } else if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
            codePoint = 0xfffd;
        }
        appendUtf8(output, codePoint);
    }

    env->ReleaseStringChars(value, chars);
    return output;
}

void appendUtf16(std::u16string & output, uint32_t codePoint) {
    if (codePoint <= 0xffff) {
        output.push_back(static_cast<char16_t>(codePoint));
        return;
    }
    codePoint -= 0x10000;
    output.push_back(static_cast<char16_t>(0xd800 + (codePoint >> 10)));
    output.push_back(static_cast<char16_t>(0xdc00 + (codePoint & 0x3ff)));
}

std::u16string toUtf16(const std::string & input) {
    std::u16string output;
    size_t index = 0;
    while (index < input.size()) {
        const auto first = static_cast<unsigned char>(input[index]);
        uint32_t codePoint = 0xfffd;
        size_t width = 1;
        bool valid = true;

        if (first <= 0x7f) {
            codePoint = first;
        } else if (first >= 0xc2 && first <= 0xdf) {
            width = 2;
            codePoint = first & 0x1f;
        } else if (first >= 0xe0 && first <= 0xef) {
            width = 3;
            codePoint = first & 0x0f;
        } else if (first >= 0xf0 && first <= 0xf4) {
            width = 4;
            codePoint = first & 0x07;
        } else {
            valid = false;
        }

        if (valid && index + width > input.size()) {
            valid = false;
        }
        if (valid) {
            for (size_t offset = 1; offset < width; ++offset) {
                const auto next = static_cast<unsigned char>(input[index + offset]);
                if ((next & 0xc0) != 0x80) {
                    valid = false;
                    break;
                }
                codePoint = (codePoint << 6) | (next & 0x3f);
            }
        }
        if (valid) {
            const bool overlong = (width == 2 && codePoint < 0x80) ||
                                  (width == 3 && codePoint < 0x800) ||
                                  (width == 4 && codePoint < 0x10000);
            valid = !overlong && codePoint <= 0x10ffff &&
                    !(codePoint >= 0xd800 && codePoint <= 0xdfff);
        }

        if (valid) {
            appendUtf16(output, codePoint);
            index += width;
        } else {
            appendUtf16(output, 0xfffd);
            ++index;
        }
    }
    return output;
}

jstring toJavaString(JNIEnv * env, const std::string & value) {
    const std::u16string utf16 = toUtf16(value);
    if (utf16.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throw std::runtime_error("Generated text is too large for a Java string");
    }
    return env->NewString(
        reinterpret_cast<const jchar *>(utf16.data()),
        static_cast<jsize>(utf16.size())
    );
}

NativeSession * sessionFromHandle(jlong handle) {
    return reinterpret_cast<NativeSession *>(static_cast<intptr_t>(handle));
}

std::string formatPrompt(
    const llama_model * model,
    const std::string & context,
    const std::string & prompt
) {
    const char * chatTemplate = llama_model_chat_template(model, nullptr);
    if (chatTemplate == nullptr) {
        throw std::invalid_argument("The verified model does not contain a chat template");
    }

    std::vector<llama_chat_message> messages;
    if (!context.empty()) {
        messages.push_back({"system", context.c_str()});
    }
    messages.push_back({"user", prompt.c_str()});

    const int32_t required = llama_chat_apply_template(
        chatTemplate,
        messages.data(),
        messages.size(),
        true,
        nullptr,
        int32_t{}
    );
    if (required < 0 || required == std::numeric_limits<int32_t>::max()) {
        throw std::invalid_argument("The pinned llama.cpp build cannot apply this model chat template");
    }

    std::vector<char> buffer(static_cast<size_t>(required) + 1, '\0');
    const int32_t actual = llama_chat_apply_template(
        chatTemplate,
        messages.data(),
        messages.size(),
        true,
        buffer.data(),
        static_cast<int32_t>(buffer.size())
    );
    if (actual < 0 || actual > required) {
        throw std::runtime_error("Could not format the model chat prompt");
    }
    return std::string(buffer.data(), static_cast<size_t>(actual));
}

std::vector<llama_token> tokenizePrompt(const llama_vocab * vocab, const std::string & prompt) {
    if (prompt.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        throw std::invalid_argument("Prompt is too large to tokenize");
    }
    const int32_t promptLength = static_cast<int32_t>(prompt.size());
    const int32_t query = llama_tokenize(
        vocab,
        prompt.c_str(),
        promptLength,
        nullptr,
        int32_t{},
        true,
        true
    );
    if (query == std::numeric_limits<int32_t>::min()) {
        throw std::invalid_argument("The prompt tokenization result is too large");
    }
    const int32_t capacity = query < 0 ? -query : query;
    std::vector<llama_token> tokens(static_cast<size_t>(capacity));
    const int32_t count = llama_tokenize(
        vocab,
        prompt.c_str(),
        promptLength,
        tokens.data(),
        capacity,
        true,
        true
    );
    if (count < 0) {
        throw std::runtime_error("The prompt could not be tokenized within its measured capacity");
    }
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

std::string detokenize(const llama_vocab * vocab, const std::vector<llama_token> & tokens) {
    if (tokens.empty()) {
        return {};
    }
    const int32_t required = llama_detokenize(
        vocab,
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        nullptr,
        int32_t{},
        true,
        false
    );
    if (required == std::numeric_limits<int32_t>::min()) {
        throw std::runtime_error("Generated text is too large to detokenize");
    }
    const int32_t capacity = required < 0 ? -required : required;
    std::vector<char> buffer(static_cast<size_t>(capacity) + 1, '\0');
    const int32_t actual = llama_detokenize(
        vocab,
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        buffer.data(),
        capacity,
        true,
        false
    );
    if (actual < 0 || actual > capacity) {
        throw std::runtime_error("Could not detokenize the generated response");
    }
    return std::string(buffer.data(), static_cast<size_t>(actual));
}

jobject makeGenerationResult(
    JNIEnv * env,
    const std::string & text,
    jint promptTokens,
    jint completionTokens,
    const std::string & finishReason
) {
    jclass resultClass = env->FindClass("inference/NativeGenerationResult");
    if (resultClass == nullptr) {
        return nullptr;
    }
    const jmethodID constructor = env->GetMethodID(
        resultClass,
        "<init>",
        "(Ljava/lang/String;IILjava/lang/String;)V"
    );
    if (constructor == nullptr) {
        env->DeleteLocalRef(resultClass);
        return nullptr;
    }

    jstring javaText = toJavaString(env, text);
    jstring javaFinishReason = toJavaString(env, finishReason);
    if (javaText == nullptr || javaFinishReason == nullptr) {
        if (javaText != nullptr) env->DeleteLocalRef(javaText);
        if (javaFinishReason != nullptr) env->DeleteLocalRef(javaFinishReason);
        env->DeleteLocalRef(resultClass);
        return nullptr;
    }

    jobject result = env->NewObject(resultClass, constructor, javaText, promptTokens, completionTokens, javaFinishReason);
    env->DeleteLocalRef(javaText);
    env->DeleteLocalRef(javaFinishReason);
    env->DeleteLocalRef(resultClass);
    return result;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_inference_JniLlamaBridge_nativeLoadModel(JNIEnv * env, jobject, jstring modelPath, jint contextTokens) {
    try {
        const std::string path = fromJavaString(env, modelPath);
        // PROVISIONAL - pending GEN-PERF-1
        if (path.empty() || contextTokens <= 0) {
            throw std::invalid_argument("A model path and positive context window are required");
        }
        std::call_once(backendInitFlag, [] { llama_backend_init(); });

        auto session = std::make_unique<NativeSession>();
        session->contextTokens = static_cast<uint32_t>(contextTokens);
        llama_model_params modelParams = llama_model_default_params();
        // PROVISIONAL - pending GEN-PERF-1
        modelParams.n_gpu_layers = 0;
        session->model = llama_model_load_from_file(path.c_str(), modelParams);
        if (session->model == nullptr) {
            throw std::bad_alloc();
        }

        llama_context_params contextParams = llama_context_default_params();
        contextParams.n_ctx = static_cast<uint32_t>(contextTokens);
        contextParams.n_batch = static_cast<uint32_t>(contextTokens);
        contextParams.offload_kqv = false;
        contextParams.op_offload = false;
        session->context = llama_init_from_model(session->model, contextParams);
        if (session->context == nullptr) {
            throw std::bad_alloc();
        }

        return static_cast<jlong>(reinterpret_cast<intptr_t>(session.release()));
    } catch (const std::bad_alloc &) {
        throwJava(env, "java/lang/OutOfMemoryError", "The CPU model or context could not be allocated");
    } catch (const std::exception & error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native model-load failure");
    }
    // PROVISIONAL - pending GEN-PERF-1
    return kInvalidNativeHandle;
}

extern "C" JNIEXPORT jobject JNICALL
Java_inference_JniLlamaBridge_nativeGenerate(
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring contextValue,
    jstring promptValue,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jstring grammarValue
) {
    try {
        NativeSession * session = sessionFromHandle(handle);
        if (session == nullptr || session->model == nullptr || session->context == nullptr) {
            throw std::invalid_argument("The native model handle is invalid");
        }
        // PROVISIONAL - pending GEN-PERF-1
        if (maxTokens <= 0) {
            throw std::invalid_argument("The output token budget must be positive");
        }

        const std::string context = fromJavaString(env, contextValue);
        const std::string prompt = fromJavaString(env, promptValue);
        const std::string grammar = fromJavaString(env, grammarValue);
        if (context.find('\0') != std::string::npos || prompt.find('\0') != std::string::npos) {
            throw std::invalid_argument("Prompt text cannot contain a null character");
        }

        std::lock_guard<std::mutex> guard(session->mutex);
        const std::string formattedPrompt = formatPrompt(session->model, context, prompt);
        const llama_vocab * vocab = llama_model_get_vocab(session->model);
        std::vector<llama_token> promptTokens = tokenizePrompt(vocab, formattedPrompt);
        if (promptTokens.empty()) {
            throw std::invalid_argument("The formatted prompt produced no tokens");
        }
        const uint64_t totalTokens = static_cast<uint64_t>(promptTokens.size()) + static_cast<uint64_t>(maxTokens);
        if (totalTokens > static_cast<uint64_t>(session->contextTokens)) {
            throw std::bad_alloc();
        }

        llama_memory_clear(llama_get_memory(session->context), true);
        // Pinned b11669 llama_batch_compat supplies the missing sequence, position, and output metadata.
        const llama_batch promptBatch = llama_batch_get_one(
            promptTokens.data(),
            static_cast<int32_t>(promptTokens.size())
        );
        checkDecodeStatus(llama_decode(session->context, promptBatch));

        llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
        samplerParams.no_perf = true;
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(samplerParams),
            llama_sampler_free
        );
        if (!sampler) {
            throw std::bad_alloc();
        }

        if (!grammar.empty()) {
            llama_sampler * grammarSampler = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
            if (grammarSampler == nullptr) {
                throw std::invalid_argument("The frozen cognitive-call GBNF grammar was rejected");
            }
            llama_sampler_chain_add(sampler.get(), grammarSampler);
        }

        // PROVISIONAL - pending GEN-PERF-1
        constexpr size_t kTopPMinKeep = 1;
        if (temperature <= 0.0f) {
            llama_sampler * greedySampler = llama_sampler_init_greedy();
            if (greedySampler == nullptr) {
                throw std::bad_alloc();
            }
            llama_sampler_chain_add(sampler.get(), greedySampler);
        } else {
            llama_sampler * topPSampler = llama_sampler_init_top_p(topP, kTopPMinKeep);
            llama_sampler * temperatureSampler = llama_sampler_init_temp(temperature);
            llama_sampler * distributionSampler = llama_sampler_init_dist(LLAMA_DEFAULT_SEED);
            if (topPSampler == nullptr || temperatureSampler == nullptr || distributionSampler == nullptr) {
                if (topPSampler != nullptr) llama_sampler_free(topPSampler);
                if (temperatureSampler != nullptr) llama_sampler_free(temperatureSampler);
                if (distributionSampler != nullptr) llama_sampler_free(distributionSampler);
                throw std::bad_alloc();
            }
            llama_sampler_chain_add(sampler.get(), topPSampler);
            llama_sampler_chain_add(sampler.get(), temperatureSampler);
            llama_sampler_chain_add(sampler.get(), distributionSampler);
        }

        // PROVISIONAL - pending GEN-PERF-1
        constexpr int32_t kLastLogitsIndex = -1;
        std::vector<llama_token> outputTokens;
        std::string finishReason = "max_tokens";
        for (jint produced = 0; produced < maxTokens; ++produced) {
            const llama_token token = llama_sampler_sample(sampler.get(), session->context, kLastLogitsIndex);
            if (llama_vocab_is_eog(vocab, token)) {
                finishReason = "stop";
                break;
            }
            outputTokens.push_back(token);
            const llama_batch nextBatch = llama_batch_get_one(&outputTokens.back(), kSingleTokenBatchSize);
            checkDecodeStatus(llama_decode(session->context, nextBatch));
        }

        const std::string outputText = detokenize(vocab, outputTokens);
        if (promptTokens.size() > static_cast<size_t>(std::numeric_limits<jint>::max()) ||
            outputTokens.size() > static_cast<size_t>(std::numeric_limits<jint>::max())) {
            throw std::runtime_error("Token usage count exceeds the Kotlin contract range");
        }
        return makeGenerationResult(
            env,
            outputText,
            static_cast<jint>(promptTokens.size()),
            static_cast<jint>(outputTokens.size()),
            finishReason
        );
    } catch (const std::bad_alloc &) {
        throwJava(env, "java/lang/OutOfMemoryError", "The CPU inference context could not process this request");
    } catch (const std::exception & error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native generation failure");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_inference_JniLlamaBridge_nativeReleaseModel(JNIEnv * env, jobject, jlong handle) {
    NativeSession * session = sessionFromHandle(handle);
    if (session == nullptr) {
        return;
    }
    {
        std::lock_guard<std::mutex> guard(session->mutex);
        if (session->context != nullptr) {
            llama_free(session->context);
            session->context = nullptr;
        }
        if (session->model != nullptr) {
            llama_model_free(session->model);
            session->model = nullptr;
        }
    }
    delete session;
    (void) env;
}
