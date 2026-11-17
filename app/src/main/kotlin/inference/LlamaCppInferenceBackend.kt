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

    private fun grammarFor(outputSchema: String?): String? {
        if (outputSchema == null) return null
        val expected = Json.parseToJsonElement(StructuredGrammarConstraint.cognitiveCallSchemaJson)
        val requested = runCatching { Json.parseToJsonElement(outputSchema) }.getOrNull()
        return if (requested == expected) StructuredGrammarConstraint.gbnfGrammarDefinition else null
    }

    private fun <T> failure(error: InferenceError): Result<T> =
        Result.failure(AgentModelException(error))
}
