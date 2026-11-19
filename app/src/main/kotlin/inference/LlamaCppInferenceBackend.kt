package inference

import contracts.InferenceError
import contracts.InferenceRequest
import contracts.InferenceResponse
import contracts.TokenUsage
import kotlinx.serialization.json.Json
import reasoning.StructuredGrammarConstraint
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** JNI result shape; the native bridge constructs this value after one CPU generation. */
internal data class NativeGenerationResult(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val finishReason: String,
    val prefillElapsedNanos: Long,
    val firstTokenElapsedNanos: Long,
    val decodeElapsedNanos: Long
)

internal data class NativeChatMessage(val role: String, val content: String) {
    override fun toString(): String = "NativeChatMessage(role=$role, content=<redacted>)"
}

internal interface NativeChatCallback {
    fun onPromptTokenCount(tokenCount: Int)
    /** Delivers a cumulative, valid-text snapshot of all output generated so far. */
    fun onTextSnapshot(snapshot: String)
}

internal data class LlamaRuntimeMeasurements(
    val modelLoadTimeMs: Long,
    val firstTokenTimeMs: Long,
    val prefillTokensPerSecond: Double?,
    val decodeTokensPerSecond: Double?,
    val tokenCount: Int
)

internal data class MeasuredLlamaInference(
    val response: InferenceResponse,
    val measurements: LlamaRuntimeMeasurements
)

/** Small test seam around the source-built JNI library. */
internal interface LlamaNativeBridge {
    fun loadModel(modelPath: String, contextTokens: Int, nThreads: Int, nThreadsBatch: Int): Long
    fun systemInfo(): String
    fun generate(
        handle: Long,
        context: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        grammar: String?
    ): NativeGenerationResult

    /** Counts the exact model-formatted prompt without generating tokens; -1 means unavailable. */
    fun countPromptTokens(handle: Long, context: String, prompt: String): Int = -1

    /** Counts a system prompt plus role-preserving chat turns after applying the model template. */
    fun countConversationTokens(
        handle: Long,
        systemPrompt: String,
        messages: List<NativeChatMessage>
    ): Int = countPromptTokens(handle, systemPrompt, flattenConversation(messages))

    /** Test-compatible fallback; production JNI overrides this with role-preserving template input. */
    fun generateConversation(
        handle: Long,
        systemPrompt: String,
        messages: List<NativeChatMessage>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: NativeChatCallback?
    ): NativeGenerationResult {
        val prompt = flattenConversation(messages)
        val result = generate(handle, systemPrompt, prompt, maxTokens, temperature, topP, null)
        callback?.onPromptTokenCount(result.promptTokens)
        if (result.text.isNotEmpty()) callback?.onTextSnapshot(result.text)
        return result
    }

    fun releaseModel(handle: Long)

    /** Requests llama.cpp's CPU abort callback without waiting for the inference lock. */
    fun cancelGeneration(handle: Long) = Unit

    /** Returns the calling Linux thread's effective scheduler affinity, or null when unavailable. */
    fun currentThreadAffinity(): IntArray? = null

    /** Applies affinity to the calling Linux thread; the caller must read it back separately. */
    fun setThreadAffinity(cpuIds: IntArray): Boolean = false
}

/** JNI declarations are resolved only when the app actually requests local inference. */
internal object JniLlamaBridge : LlamaNativeBridge {
    private val libraryLoadFailure: Throwable? = runCatching {
        loadNativeLibraryAfterArmDotprodCheck {
            System.loadLibrary("agent_llama")
        }
    }.exceptionOrNull()

    override fun loadModel(
        modelPath: String,
        contextTokens: Int,
        nThreads: Int,
        nThreadsBatch: Int
    ): Long {
        requireLibrary()
        return nativeLoadModel(modelPath, contextTokens, nThreads, nThreadsBatch)
    }

    override fun systemInfo(): String {
        requireLibrary()
        return nativeSystemInfo()
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

    override fun countPromptTokens(handle: Long, context: String, prompt: String): Int {
        requireLibrary()
        return nativeCountPromptTokens(handle, context, prompt)
    }

    override fun countConversationTokens(
        handle: Long,
        systemPrompt: String,
        messages: List<NativeChatMessage>
    ): Int {
        requireLibrary()
        return nativeCountConversationTokens(handle, systemPrompt, messages.toTypedArray())
    }

    override fun generateConversation(
        handle: Long,
        systemPrompt: String,
        messages: List<NativeChatMessage>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: NativeChatCallback?
    ): NativeGenerationResult {
        requireLibrary()
        return nativeGenerateConversation(
            handle,
            systemPrompt,
            messages.toTypedArray(),
            maxTokens,
            temperature,
            topP,
            callback
        )
    }

    override fun releaseModel(handle: Long) {
        requireLibrary()
        nativeReleaseModel(handle)
    }

    override fun cancelGeneration(handle: Long) {
        requireLibrary()
        nativeCancelGeneration(handle)
    }

    override fun currentThreadAffinity(): IntArray? {
        requireLibrary()
        return nativeGetThreadAffinity()
    }

    override fun setThreadAffinity(cpuIds: IntArray): Boolean {
        requireLibrary()
        return nativeSetThreadAffinity(cpuIds)
    }

    private fun requireLibrary() {
        libraryLoadFailure?.let { error ->
            throw IllegalStateException("The local CPU runtime is unavailable", error)
        }
    }

    private external fun nativeLoadModel(
        modelPath: String,
        contextTokens: Int,
        nThreads: Int,
        nThreadsBatch: Int
    ): Long
    private external fun nativeSystemInfo(): String
    private external fun nativeGenerate(
        handle: Long,
        context: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        grammar: String?
    ): NativeGenerationResult
    private external fun nativeCountPromptTokens(handle: Long, context: String, prompt: String): Int
    private external fun nativeCountConversationTokens(
        handle: Long,
        systemPrompt: String,
        messages: Array<NativeChatMessage>
    ): Int
    private external fun nativeGenerateConversation(
        handle: Long,
        systemPrompt: String,
        messages: Array<NativeChatMessage>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: NativeChatCallback?
    ): NativeGenerationResult
    private external fun nativeReleaseModel(handle: Long)
    private external fun nativeCancelGeneration(handle: Long)
    private external fun nativeGetThreadAffinity(): IntArray?
    private external fun nativeSetThreadAffinity(cpuIds: IntArray): Boolean
}

/**
 * Production CPU backend. It loads only the hash-verified private model file and serializes access
 * to the single llama.cpp context. Native compilation is intentionally absent from Gradle.
 */
class LlamaCppInferenceBackend internal constructor(
    private val modelAssetStore: ModelAssetStore,
    private val contextTokens: Int,
    private val bridge: LlamaNativeBridge = JniLlamaBridge,
    private val monotonicNanos: () -> Long = System::nanoTime,
    nThreads: Int = Runtime.getRuntime().availableProcessors(),
    nThreadsBatch: Int = nThreads
) : Closeable {
    private val lock = ReentrantLock()
    private val nativeHandleLock = Any()
    private var loadedModelPath: String? = null
    @Volatile
    private var configuredNThreads: Int = nThreads
    @Volatile
    private var configuredNThreadsBatch: Int = nThreadsBatch
    @Volatile
    private var nativeHandle: Long = INVALID_NATIVE_HANDLE

    private companion object {
        // PROVISIONAL - pending GEN-PERF-1
        const val INVALID_NATIVE_HANDLE = 0L
        // PROVISIONAL - pending GEN-PERF-1
        const val NANOSECONDS_PER_SECOND = 1_000_000_000.0
    }

    init {
        // PROVISIONAL - pending GEN-PERF-1
        require(contextTokens > 0) { "The configured context window must be positive" }
        // PROVISIONAL - pending GEN-PERF-1
        require(nThreads > 0) { "The configured inference thread count must be positive" }
        // PROVISIONAL - pending GEN-PERF-1
        require(nThreadsBatch > 0) { "The configured batch thread count must be positive" }
    }

    fun generate(request: InferenceRequest): Result<InferenceResponse> =
        generateMeasured(request).map { it.response }

    internal fun generateMeasured(
        request: InferenceRequest,
        isStillAllowed: () -> Boolean = { true }
    ): Result<MeasuredLlamaInference> {
        if (!isStillAllowedSafely(isStillAllowed)) {
            return failure(InferenceError.Unavailable("Local self-test was stopped"))
        }
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
            var modelLoadNanos = 0L
            val nativeResult = lock.withLock {
                if (!isStillAllowedSafely(isStillAllowed)) throw LocalSelfTestStoppedException()
                val loaded = ensureLoaded(modelFile)
                modelLoadNanos = loaded.loadDurationNanos
                if (!isStillAllowedSafely(isStillAllowed)) throw LocalSelfTestStoppedException()
                bridge.generate(
                    handle = loaded.handle,
                    context = request.context,
                    prompt = request.prompt,
                    maxTokens = request.params.max_tokens,
                    temperature = request.params.temperature,
                    topP = request.params.top_p,
                    grammar = grammar
                )
            }
            if (!isStillAllowedSafely(isStillAllowed)) {
                return failure(InferenceError.Unavailable("Local self-test was stopped"))
            }
            if (nativeResult.promptTokens < 0 || nativeResult.completionTokens < 0 ||
                nativeResult.prefillElapsedNanos < 0L ||
                nativeResult.firstTokenElapsedNanos < 0L ||
                nativeResult.decodeElapsedNanos < 0L
            ) {
                return failure(InferenceError.Unavailable("Native timing data was unavailable"))
            }
            val prefillRate = if (nativeResult.promptTokens > 0 && nativeResult.prefillElapsedNanos > 0L) {
                nativeResult.promptTokens.toDouble() * NANOSECONDS_PER_SECOND /
                    nativeResult.prefillElapsedNanos.toDouble()
            } else {
                null
            }
            // PROVISIONAL - pending GEN-PERF-1
            val decodeTokensAfterFirst = (nativeResult.completionTokens - 1).coerceAtLeast(0)
            val decodeRate = if (decodeTokensAfterFirst > 0 && nativeResult.decodeElapsedNanos > 0L) {
                decodeTokensAfterFirst.toDouble() * NANOSECONDS_PER_SECOND /
                    nativeResult.decodeElapsedNanos.toDouble()
            } else {
                null
            }
            if ((prefillRate != null && !prefillRate.isFinite()) ||
                (decodeRate != null && !decodeRate.isFinite())
            ) {
                return failure(InferenceError.Unavailable("Native timing data was unavailable"))
            }
            Result.success(
                MeasuredLlamaInference(
                    response = InferenceResponse(
                        text = nativeResult.text,
                        usage = TokenUsage(
                            prompt_tokens = nativeResult.promptTokens,
                            completion_tokens = nativeResult.completionTokens
                        ),
                        finish_reason = nativeResult.finishReason
                    ),
                    measurements = LlamaRuntimeMeasurements(
                        modelLoadTimeMs = TimeUnit.NANOSECONDS.toMillis(modelLoadNanos.coerceAtLeast(0L)),
                        firstTokenTimeMs = TimeUnit.NANOSECONDS.toMillis(nativeResult.firstTokenElapsedNanos),
                        prefillTokensPerSecond = prefillRate,
                        decodeTokensPerSecond = decodeRate,
                        tokenCount = nativeResult.completionTokens
                    )
                )
            )
        } catch (_: OutOfMemoryError) {
            failure(InferenceError.ResourceExhausted("The CPU model or context could not be allocated"))
        } catch (_: LinkageError) {
            failure(InferenceError.Unavailable("The source-built CPU inference library could not be loaded"))
        } catch (_: Exception) {
            failure(InferenceError.Unavailable("Native CPU inference failed"))
        }
    }

    /** Configures the context before it is created; a live context is discarded on thread-count change. */
    internal fun configureThreadCounts(nThreads: Int, nThreadsBatch: Int) {
        require(nThreads > 0 && nThreadsBatch > 0)
        lock.withLock {
            if (configuredNThreads != nThreads || configuredNThreadsBatch != nThreadsBatch) {
                releaseLoadedModel()
                configuredNThreads = nThreads
                configuredNThreadsBatch = nThreadsBatch
            }
        }
    }

    internal fun systemInfo(): String = bridge.systemInfo()

    /** Returns the token count after the verified model chat template has been applied. */
    internal fun formattedPromptTokenCount(context: String, prompt: String): Int? {
        val modelFile = modelAssetStore.verifiedModelFile() ?: return null
        return try {
            lock.withLock {
                val loaded = ensureLoaded(modelFile)
                bridge.countPromptTokens(loaded.handle, context, prompt).takeIf { it > 0 }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Counts the production conversation template, including system and all retained turns. */
    internal fun formattedConversationTokenCount(
        systemPrompt: String,
        messages: List<NativeChatMessage>,
        isStillAllowed: () -> Boolean = { true }
    ): Result<Int> {
        if (!isStillAllowedSafely(isStillAllowed)) {
            return failure(InferenceError.Unavailable("Local chat access was closed"))
        }
        val modelFile = modelAssetStore.verifiedModelFile()
            ?: return failure(InferenceError.Unavailable("Import the SHA-256-verified Qwen3 model before local inference"))
        return try {
            val count = lock.withLock {
                if (!isStillAllowedSafely(isStillAllowed)) throw LocalChatAccessClosedException()
                val loaded = ensureLoaded(modelFile)
                if (!isStillAllowedSafely(isStillAllowed)) throw LocalChatAccessClosedException()
                bridge.countConversationTokens(loaded.handle, systemPrompt, messages)
            }
            if (!isStillAllowedSafely(isStillAllowed)) {
                failure(InferenceError.Unavailable("Local chat access was closed"))
            } else if (count <= 0) {
                failure(InferenceError.Unavailable("The formatted prompt token count is unavailable"))
            } else {
                Result.success(count)
            }
        } catch (_: OutOfMemoryError) {
            failure(InferenceError.ResourceExhausted("The formatted prompt could not be allocated"))
        } catch (_: LocalChatAccessClosedException) {
            failure(InferenceError.Unavailable("Local chat access was closed"))
        } catch (_: Exception) {
            failure(InferenceError.Unavailable("The formatted prompt could not be counted"))
        }
    }

    /** Runs a role-preserving chat prompt and forwards bounded cumulative snapshots from JNI. */
    internal fun generateConversationMeasured(
        systemPrompt: String,
        messages: List<NativeChatMessage>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        isStillAllowed: () -> Boolean = { true },
        onPromptTokenCount: (Int) -> Unit = {},
        onTextSnapshot: (String) -> Unit
    ): Result<MeasuredLlamaInference> {
        if (!isStillAllowedSafely(isStillAllowed)) {
            return failure(InferenceError.Unavailable("Local chat access was closed"))
        }
        if (maxTokens <= 0 || maxTokens > contextTokens) {
            return failure(InferenceError.ResourceExhausted("Requested output budget is outside the configured context window"))
        }
        if (!temperature.isFinite() || temperature < 0f || !topP.isFinite() || topP <= 0f || topP > 1f) {
            return failure(InferenceError.Malformed("Sampling parameters are invalid"))
        }
        val modelFile = modelAssetStore.verifiedModelFile()
            ?: return failure(InferenceError.Unavailable("Import the SHA-256-verified Qwen3 model before local inference"))
        val promptTokenCallback = onPromptTokenCount
        val textSnapshotCallback = onTextSnapshot

        return try {
            var modelLoadNanos = 0L
            val nativeResult = lock.withLock {
                if (!isStillAllowedSafely(isStillAllowed)) throw LocalChatAccessClosedException()
                val loaded = ensureLoaded(modelFile)
                modelLoadNanos = loaded.loadDurationNanos
                if (!isStillAllowedSafely(isStillAllowed)) throw LocalChatAccessClosedException()
                bridge.generateConversation(
                    handle = loaded.handle,
                    systemPrompt = systemPrompt,
                    messages = messages,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                    callback = object : NativeChatCallback {
                        override fun onPromptTokenCount(tokenCount: Int) {
                            if (isStillAllowedSafely(isStillAllowed) && tokenCount > 0) {
                                promptTokenCallback(tokenCount)
                            }
                        }

                        override fun onTextSnapshot(snapshot: String) {
                            if (isStillAllowedSafely(isStillAllowed)) textSnapshotCallback(snapshot)
                        }
                    }
                )
            }
            if (!isStillAllowedSafely(isStillAllowed)) {
                return failure(InferenceError.Unavailable("Local chat access was closed"))
            }
            if (nativeResult.promptTokens <= 0 || nativeResult.completionTokens < 0 ||
                nativeResult.prefillElapsedNanos < 0L ||
                nativeResult.firstTokenElapsedNanos < 0L ||
                nativeResult.decodeElapsedNanos < 0L
            ) {
                return failure(InferenceError.Unavailable("Native chat timing data was unavailable"))
            }
            val prefillRate = if (nativeResult.prefillElapsedNanos > 0L) {
                nativeResult.promptTokens.toDouble() * NANOSECONDS_PER_SECOND /
                    nativeResult.prefillElapsedNanos.toDouble()
            } else null
            val decodeTokensAfterFirst = (nativeResult.completionTokens - 1).coerceAtLeast(0)
            val decodeRate = if (decodeTokensAfterFirst > 0 && nativeResult.decodeElapsedNanos > 0L) {
                decodeTokensAfterFirst.toDouble() * NANOSECONDS_PER_SECOND /
                    nativeResult.decodeElapsedNanos.toDouble()
            } else null
            if ((prefillRate != null && !prefillRate.isFinite()) ||
                (decodeRate != null && !decodeRate.isFinite())
            ) {
                return failure(InferenceError.Unavailable("Native chat timing data was unavailable"))
            }
            Result.success(
                MeasuredLlamaInference(
                    response = InferenceResponse(
                        text = nativeResult.text,
                        usage = TokenUsage(
                            prompt_tokens = nativeResult.promptTokens,
                            completion_tokens = nativeResult.completionTokens
                        ),
                        finish_reason = nativeResult.finishReason
                    ),
                    measurements = LlamaRuntimeMeasurements(
                        modelLoadTimeMs = TimeUnit.NANOSECONDS.toMillis(modelLoadNanos.coerceAtLeast(0L)),
                        firstTokenTimeMs = TimeUnit.NANOSECONDS.toMillis(nativeResult.firstTokenElapsedNanos),
                        prefillTokensPerSecond = prefillRate,
                        decodeTokensPerSecond = decodeRate,
                        tokenCount = nativeResult.completionTokens
                    )
                )
            )
        } catch (_: OutOfMemoryError) {
            failure(InferenceError.ResourceExhausted("The CPU model or context could not be allocated"))
        } catch (_: LocalChatAccessClosedException) {
            failure(InferenceError.Unavailable("Local chat access was closed"))
        } catch (_: LinkageError) {
            failure(InferenceError.Unavailable("The source-built CPU inference library could not be loaded"))
        } catch (_: Exception) {
            failure(InferenceError.Unavailable("Native CPU chat inference failed"))
        }
    }

    internal fun currentThreadAffinity(): List<Int>? = runCatching {
        bridge.currentThreadAffinity()?.toList()
    }.getOrNull()

    internal fun setThreadAffinity(cpuIds: List<Int>): Boolean = runCatching {
        bridge.setThreadAffinity(cpuIds.toIntArray())
    }.getOrDefault(false)

    /** Can be called without waiting for the generation lock; the CPU context checks this at decode boundaries. */
    fun requestAbortGeneration() {
        synchronized(nativeHandleLock) {
            val handle = nativeHandle
            if (handle != INVALID_NATIVE_HANDLE) runCatching { bridge.cancelGeneration(handle) }
        }
    }

    override fun close() {
        lock.withLock { releaseLoadedModel() }
    }

    private fun releaseLoadedModel() {
        synchronized(nativeHandleLock) {
            if (nativeHandle != INVALID_NATIVE_HANDLE) {
                runCatching { bridge.releaseModel(nativeHandle) }
                nativeHandle = INVALID_NATIVE_HANDLE
                loadedModelPath = null
            }
        }
    }

    private data class LoadedModel(val handle: Long, val loadDurationNanos: Long)

    private fun ensureLoaded(modelFile: File): LoadedModel {
        val path = modelFile.absolutePath
        if (loadedModelPath != null && loadedModelPath != path) {
            synchronized(nativeHandleLock) {
                if (nativeHandle != INVALID_NATIVE_HANDLE) runCatching { bridge.releaseModel(nativeHandle) }
                nativeHandle = INVALID_NATIVE_HANDLE
                loadedModelPath = null
            }
        }
        if (nativeHandle == INVALID_NATIVE_HANDLE) {
            val start = monotonicNanos()
            val loadedHandle = bridge.loadModel(
                path,
                contextTokens,
                configuredNThreads,
                configuredNThreadsBatch
            )
            val loadDuration = (monotonicNanos() - start).coerceAtLeast(0L)
            check(loadedHandle != INVALID_NATIVE_HANDLE) { "llama.cpp returned an invalid model handle" }
            synchronized(nativeHandleLock) {
                nativeHandle = loadedHandle
                loadedModelPath = path
            }
            return LoadedModel(loadedHandle, loadDuration)
        }
        return LoadedModel(nativeHandle, 0L)
    }

    private fun isStillAllowedSafely(check: () -> Boolean): Boolean =
        runCatching(check).getOrDefault(false)

    private class LocalSelfTestStoppedException : IllegalStateException("Local self-test was stopped")
    private class LocalChatAccessClosedException : IllegalStateException("Local chat access was closed")

    private fun grammarFor(outputSchema: String?): String? {
        if (outputSchema == null) return null
        val expected = Json.parseToJsonElement(StructuredGrammarConstraint.cognitiveCallSchemaJson)
        val requested = runCatching { Json.parseToJsonElement(outputSchema) }.getOrNull()
        return if (requested == expected) StructuredGrammarConstraint.gbnfGrammarDefinition else null
    }

    private fun <T> failure(error: InferenceError): Result<T> =
        Result.failure(AgentModelException(error))
}

private fun flattenConversation(messages: List<NativeChatMessage>): String =
    messages.joinToString(separator = "\n") { message ->
        "${message.role}: ${message.content}"
    }
