package inference

import android.os.Debug
import contracts.GenerateParams
import contracts.InferenceRequest
import contracts.InferenceResponse
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Carries only the CPU engine and timing values; generated text never enters this result type. */
internal data class LocalModelSelfTestMetrics(
    val modelLoadTimeMs: Long,
    val firstTokenTimeMs: Long,
    val tokensPerSecond: Double,
    val tokenCount: Int,
    val peakPssKilobytes: Long
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

    fun requestAbort() {
        backend.requestAbortGeneration()
    }

    override fun close() {
        backend.close()
        measurements.set(null)
    }
}

fun interface ProcessPssProbe {
    /** Returns total process PSS in KiB, or null when the operating system cannot sample it. */
    fun currentPssKilobytes(): Long?
}

internal object AndroidProcessPssProbe : ProcessPssProbe {
    override fun currentPssKilobytes(): Long? = runCatching {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        memoryInfo.totalPss.toLong().takeIf { it >= 0L }
    }.getOrNull()
}

/** Runs the fixed, no-tools prompt through LocalInferenceEngine and returns numeric-only results. */
internal class LocalModelSelfTestRunner(
    private val sessionFactory: (LocalModelSelfTestCancellation) -> LocalModelSelfTestSession,
    private val pssProbe: ProcessPssProbe,
    private val isEligible: () -> Boolean
) {
    fun run(cancellation: LocalModelSelfTestCancellation): LocalModelSelfTestOutcome {
        if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

        val memorySampler = PeakPssSampler(pssProbe)
        var session: LocalModelSelfTestSession? = null
        var sessionClosed = false
        try {
            memorySampler.start()
            if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

            session = sessionFactory(cancellation)
            cancellation.attachAbortHandler(session::requestAbort)
            if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

            val response = session.inferenceEngine.generate(fixedRequest()).getOrNull()
                ?: return LocalModelSelfTestOutcome.Failure
            if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

            val measurements = session.latestMeasurements()
                ?: return LocalModelSelfTestOutcome.Failure
            val generatedTokens = response.usage.completion_tokens
            if (generatedTokens !in MIN_OUTPUT_TOKENS..MAX_OUTPUT_TOKENS ||
                measurements.tokenCount != generatedTokens ||
                measurements.modelLoadTimeMs < 0L ||
                measurements.firstTokenTimeMs < 0L ||
                !measurements.tokensPerSecond.isFinite() ||
                measurements.tokensPerSecond < 0.0
            ) {
                return LocalModelSelfTestOutcome.Failure
            }

            val peakPss = memorySampler.finishAndGet()
                ?: return LocalModelSelfTestOutcome.Failure
            if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

            session.close()
            sessionClosed = true
            if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

            return LocalModelSelfTestOutcome.Success(
                LocalModelSelfTestMetrics(
                    modelLoadTimeMs = measurements.modelLoadTimeMs,
                    firstTokenTimeMs = measurements.firstTokenTimeMs,
                    tokensPerSecond = measurements.tokensPerSecond,
                    tokenCount = generatedTokens,
                    peakPssKilobytes = peakPss
                )
            )
        } catch (_: Throwable) {
            return LocalModelSelfTestOutcome.Failure
        } finally {
            if (!sessionClosed) runCatching { session?.close() }
            memorySampler.close()
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

    private class PeakPssSampler(private val probe: ProcessPssProbe) : Closeable {
        private val peakPss = AtomicLong(NO_PSS_SAMPLE)
        @Volatile
        private var scheduler: ScheduledExecutorService? = null
        @Volatile
        private var stopped = false

        fun start() {
            sample()
            val executor = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "local-model-self-test-memory-sampler").apply { isDaemon = true }
            }
            scheduler = executor
            // PROVISIONAL - pending GEN-PERF-1
            executor.scheduleAtFixedRate(::sample, PSS_SAMPLE_INTERVAL_MS, PSS_SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }

        fun finishAndGet(): Long? {
            if (!stopped) {
                sample()
                stopped = true
                scheduler?.shutdownNow()
                scheduler = null
            }
            return peakPss.get().takeIf { it != NO_PSS_SAMPLE }
        }

        private fun sample() {
            if (stopped) return
            val measured = runCatching { probe.currentPssKilobytes() }.getOrNull() ?: return
            if (measured < 0L) return
            peakPss.updateAndGet { previous -> maxOf(previous, measured) }
        }

        override fun close() {
            finishAndGet()
        }
    }

    private companion object {
        // PROVISIONAL - pending GEN-PERF-1
        const val MAX_OUTPUT_TOKENS = 32
        // PROVISIONAL - pending GEN-PERF-1
        const val MIN_OUTPUT_TOKENS = 1
        // PROVISIONAL - pending GEN-PERF-1
        const val PSS_SAMPLE_INTERVAL_MS = 100L
        // PROVISIONAL - pending GEN-PERF-1
        const val NO_PSS_SAMPLE = -1L
        // PROVISIONAL - pending GEN-PERF-1
        const val SELF_TEST_TEMPERATURE = 0.0f
        // PROVISIONAL - pending GEN-PERF-1
        const val SELF_TEST_TOP_P = 1.0f
        const val SELF_TEST_CONTEXT =
            "Local runtime self-test only. Reply with a short harmless greeting. Do not use tools or take actions."
        const val SELF_TEST_PROMPT = "Say hello in a few words."
    }
}
