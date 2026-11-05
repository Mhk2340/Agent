package inference

import contracts.GenerateParams
import contracts.InferenceRequest
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One case contains only the approved numeric CPU measurements. */
internal data class LocalModelSelfTestCaseMetrics(
    val nThreads: Int,
    val promptTokenCount: Int,
    val firstTokenTimeMs: Long,
    val tokensPerSecond: Double
)

/** The final library system_info string is shown after the complete thread sweep. */
internal data class LocalModelSelfTestMetrics(
    val cases: List<LocalModelSelfTestCaseMetrics>,
    val systemInfo: String
)

internal sealed class LocalModelSelfTestOutcome {
    data class Success(val metrics: LocalModelSelfTestMetrics) : LocalModelSelfTestOutcome()
    object Failure : LocalModelSelfTestOutcome()
}

/** Cancellation is shared by UI lifecycle guards, the timeout, the runner, and the CPU bridge. */
internal class LocalModelSelfTestCancellation {
    private val cancellationRequested = AtomicBoolean(false)
    private val abortHandler = AtomicReference<(() -> Unit)?>(null)

    fun isCancelled(): Boolean = cancellationRequested.get()

    fun cancel() {
        cancellationRequested.set(true)
        runCatching { abortHandler.get()?.invoke() }
    }

    fun attachAbortHandler(handler: () -> Unit) {
        abortHandler.set(handler)
        if (cancellationRequested.get()) runCatching(handler)
    }

    fun clearAbortHandler() {
        abortHandler.set(null)
    }
}

/** A run-scoped engine session. The backend is always closed by the runner after the attempt. */
internal class LocalModelSelfTestSession(
    val inferenceEngine: LocalInferenceEngine,
    private val backend: LlamaCppInferenceBackend,
    private val measurements: AtomicReference<LlamaRuntimeMeasurements?>
) : Closeable {
    fun latestMeasurements(): LlamaRuntimeMeasurements? = measurements.get()

    fun systemInfo(): String = backend.systemInfo()

    fun requestAbort() {
        backend.requestAbortGeneration()
    }

    override fun close() {
        backend.close()
        measurements.set(null)
    }
}

/** Runs one fixed, no-tools prompt at each approved thread count and retains no generated text. */
internal class LocalModelSelfTestRunner(
    private val sessionFactory: (LocalModelSelfTestCancellation, Int) -> LocalModelSelfTestSession,
    private val isEligible: () -> Boolean
) {
    fun run(cancellation: LocalModelSelfTestCancellation): LocalModelSelfTestOutcome {
        if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

        val caseMetrics = mutableListOf<LocalModelSelfTestCaseMetrics>()
        var lastSession: LocalModelSelfTestSession? = null
        try {
            for (nThreads in THREAD_SWEEP) {
                if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

                var session: LocalModelSelfTestSession? = null
                try {
                    session = sessionFactory(cancellation, nThreads)
                    cancellation.attachAbortHandler(session::requestAbort)
                    if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

                    val response = session.inferenceEngine.generate(fixedRequest()).getOrNull()
                        ?: return LocalModelSelfTestOutcome.Failure
                    if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

                    val measurements = session.latestMeasurements()
                        ?: return LocalModelSelfTestOutcome.Failure
                    val promptTokens = response.usage.prompt_tokens
                    val generatedTokens = response.usage.completion_tokens
                    // PROVISIONAL - pending GEN-PERF-1
                    if (promptTokens < MIN_PROMPT_TOKENS ||
                        generatedTokens !in MIN_OUTPUT_TOKENS..MAX_OUTPUT_TOKENS ||
                        measurements.tokenCount != generatedTokens ||
                        measurements.firstTokenTimeMs < 0L ||
                        !measurements.tokensPerSecond.isFinite() ||
                        measurements.tokensPerSecond <= 0.0
                    ) {
                        return LocalModelSelfTestOutcome.Failure
                    }

                    caseMetrics += LocalModelSelfTestCaseMetrics(
                        nThreads = nThreads,
                        promptTokenCount = promptTokens,
                        firstTokenTimeMs = measurements.firstTokenTimeMs,
                        tokensPerSecond = measurements.tokensPerSecond
                    )
                    lastSession = session
                } finally {
                    runCatching { session?.close() }
                }
            }

            if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure
            val systemInfo = lastSession?.systemInfo()?.takeIf(String::isNotBlank)
                ?: return LocalModelSelfTestOutcome.Failure
            if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

            return LocalModelSelfTestOutcome.Success(
                LocalModelSelfTestMetrics(
                    cases = caseMetrics.toList(),
                    systemInfo = systemInfo
                )
            )
        } catch (_: Throwable) {
            return LocalModelSelfTestOutcome.Failure
        } finally {
            cancellation.clearAbortHandler()
        }
    }

    private fun canContinue(cancellation: LocalModelSelfTestCancellation): Boolean =
        !cancellation.isCancelled() && runCatching(isEligible).getOrDefault(false)

    private fun fixedRequest(): InferenceRequest = InferenceRequest(
        prompt = SELF_TEST_PROMPT,
        context = SELF_TEST_CONTEXT,
        params = GenerateParams(
            max_tokens = MAX_OUTPUT_TOKENS,
            temperature = SELF_TEST_TEMPERATURE,
            top_p = SELF_TEST_TOP_P
        )
    )

    private companion object {
        // PROVISIONAL - pending GEN-PERF-1
        val THREAD_SWEEP = intArrayOf(2, 4, 6)
        // PROVISIONAL - pending GEN-PERF-1
        const val MAX_OUTPUT_TOKENS = 8
        // PROVISIONAL - pending GEN-PERF-1
        const val MIN_OUTPUT_TOKENS = 1
        // PROVISIONAL - pending GEN-PERF-1
        const val MIN_PROMPT_TOKENS = 1
        // PROVISIONAL - pending GEN-PERF-1
        const val SELF_TEST_TEMPERATURE = 0.0f
        // PROVISIONAL - pending GEN-PERF-1
        const val SELF_TEST_TOP_P = 1.0f
        const val SELF_TEST_CONTEXT =
            "Local runtime self-test only. Reply with a short harmless greeting. Do not use tools or take actions."
        const val SELF_TEST_PROMPT = "Say hello in a few words."
    }
}
