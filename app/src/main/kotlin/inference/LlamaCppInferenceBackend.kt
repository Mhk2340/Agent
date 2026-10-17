package inference

import contracts.InferenceError
import contracts.InferenceRequest
import contracts.InferenceResponse
import contracts.TokenUsage
import kotlinx.serialization.json.Json
import reasoning.StructuredGrammarConstraint
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** JNI result shape; the native bridge constructs this value after one CPU generation. */
internal data class NativeGenerationResult(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val finishReason: String
)

/** Small test seam around the source-built JNI library. */
internal interface LlamaNativeBridge {
    fun loadModel(modelPath: String, contextTokens: Int): Long
    fun generate(
        handle: Long,
        context: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        grammar: String?
    ): NativeGenerationResult
    fun releaseModel(handle: Long)
}

/** JNI declarations are resolved only when the app actually requests local inference. */
internal object JniLlamaBridge : LlamaNativeBridge {
    private val libraryLoadFailure: Throwable? =
        runCatching { System.loadLibrary("agent_llama") }.exceptionOrNull()

    override fun loadModel(modelPath: String, contextTokens: Int): Long {
        requireLibrary()
        return nativeLoadModel(modelPath, contextTokens)
    }

    override fun generate(
        handle: Long,
        context: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        grammar: String?
    ): NativeGenerationResult {
        requireLibrary()
        return nativeGenerate(handle, context, prompt, maxTokens, temperature, topP, grammar)
    }

    override fun releaseModel(handle: Long) {
        requireLibrary()
        nativeReleaseModel(handle)
    }

    private fun requireLibrary() {
        libraryLoadFailure?.let { error ->
            throw IllegalStateException("The CI-built CPU inference library is not packaged", error)
        }
    }

    private external fun nativeLoadModel(modelPath: String, contextTokens: Int): Long
    private external fun nativeGenerate(
        handle: Long,
        context: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        grammar: String?
    ): NativeGenerationResult
    private external fun nativeReleaseModel(handle: Long)
}

/**
 * Production CPU backend. It loads only the hash-verified private model file and serializes access
 * to the single llama.cpp context. Native compilation is intentionally absent from Gradle.
 */
class LlamaCppInferenceBackend internal constructor(
    private val modelAssetStore: ModelAssetStore,
    private val contextTokens: Int,
    private val bridge: LlamaNativeBridge = JniLlamaBridge
) : Closeable {
    private val lock = ReentrantLock()
    private var loadedModelPath: String? = null
    private var nativeHandle: Long = INVALID_NATIVE_HANDLE

    private companion object {
        // PROVISIONAL - pending GEN-PERF-1
        const val INVALID_NATIVE_HANDLE = 0L
    }

    init {
        // PROVISIONAL - pending GEN-PERF-1
        require(contextTokens > 0) { "The configured context window must be positive" }
    }

    fun generate(request: InferenceRequest): Result<InferenceResponse> {
        if (request.onToken != null || request.streamState == contracts.StreamState.STREAMING) {
            return failure(InferenceError.Unavailable("Incremental streaming is not supported by this CPU backend"))
        }
        // PROVISIONAL - pending GEN-PERF-1
        if (request.params.max_tokens <= 0 || request.params.max_tokens > contextTokens) {
            return failure(InferenceError.ResourceExhausted("Requested output budget is outside the configured context window"))
        }
        if (!request.params.temperature.isFinite() || !request.params.top_p.isFinite()) {
            return failure(InferenceError.Malformed("Sampling parameters must be finite"))
        }
        // PROVISIONAL - pending GEN-PERF-1
        if (request.params.top_p <= 0.0f || request.params.top_p > 1.0f) {
            return failure(InferenceError.Malformed("top_p must be a probability in (0, 1]"))
        }

        val grammar = grammarFor(request.outputSchema)
            ?: if (request.outputSchema == null) null
            else return failure(InferenceError.Malformed("The CPU runtime only supports the frozen cognitive-call schema"))

        val modelFile = modelAssetStore.verifiedModelFile()
            ?: return failure(InferenceError.Unavailable("Import the SHA-256-verified Qwen3 model before local inference"))

        return try {
            val nativeResult = lock.withLock {
                val handle = ensureLoaded(modelFile)
                bridge.generate(
                    handle = handle,
                    context = request.context,
                    prompt = request.prompt,
                    maxTokens = request.params.max_tokens,
                    temperature = request.params.temperature,
                    topP = request.params.top_p,
                    grammar = grammar
                )
            }
            Result.success(
                InferenceResponse(
                    text = nativeResult.text,
                    usage = TokenUsage(
                        prompt_tokens = nativeResult.promptTokens,
                        completion_tokens = nativeResult.completionTokens
                    ),
                    finish_reason = nativeResult.finishReason
                )
            )
        } catch (error: OutOfMemoryError) {
            failure(InferenceError.ResourceExhausted("The CPU model or context could not be allocated"))
        } catch (error: LinkageError) {
            failure(InferenceError.Unavailable("The source-built CPU inference library could not be loaded"))
        } catch (error: Exception) {
            failure(InferenceError.Unavailable(error.message ?: "Native CPU inference failed"))
        }
    }

    override fun close() {
        lock.withLock {
            if (nativeHandle != INVALID_NATIVE_HANDLE) {
                runCatching { bridge.releaseModel(nativeHandle) }
                nativeHandle = INVALID_NATIVE_HANDLE
                loadedModelPath = null
            }
        }
    }

    private fun ensureLoaded(modelFile: File): Long {
        val path = modelFile.absolutePath
        if (loadedModelPath != null && loadedModelPath != path) {
            if (nativeHandle != INVALID_NATIVE_HANDLE) bridge.releaseModel(nativeHandle)
            nativeHandle = INVALID_NATIVE_HANDLE
            loadedModelPath = null
        }
        if (nativeHandle == INVALID_NATIVE_HANDLE) {
            nativeHandle = bridge.loadModel(path, contextTokens)
            check(nativeHandle != INVALID_NATIVE_HANDLE) { "llama.cpp returned an invalid model handle" }
            loadedModelPath = path
        }
        return nativeHandle
    }

    private fun grammarFor(outputSchema: String?): String? {
        if (outputSchema == null) return null
        val expected = Json.parseToJsonElement(StructuredGrammarConstraint.cognitiveCallSchemaJson)
        val requested = runCatching { Json.parseToJsonElement(outputSchema) }.getOrNull()
        return if (requested == expected) StructuredGrammarConstraint.gbnfGrammarDefinition else null
    }

    private fun failure(error: InferenceError): Result<InferenceResponse> =
        Result.failure(AgentModelException(error))
}
