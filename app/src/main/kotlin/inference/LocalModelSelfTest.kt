package inference

import contracts.GenerateParams
import contracts.InferenceRequest
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class LocalModelSelfTestConfiguration(val nThreads: Int, val isPinned: Boolean) {
    // PROVISIONAL - pending GEN-PERF-1
    A(nThreads = 1, isPinned = true),
    // PROVISIONAL - pending GEN-PERF-1
    B(nThreads = 2, isPinned = true),
    // PROVISIONAL - pending GEN-PERF-1
    C(nThreads = 2, isPinned = false)
}

internal enum class LocalModelSelfTestRunStatus(val displayLabel: String) {
    PINNED("pinned"),
    UNPINNED("unpinned"),
    PIN_FAILED("pin failed"),
    CPU_FREQUENCY_UNAVAILABLE("skipped: CPU max-frequency data unavailable"),
    NOT_ENOUGH_STRONG_CORES("skipped: insufficient top-frequency CPU cores"),
    AFFINITY_UNAVAILABLE("affinity unavailable"),
    AFFINITY_RESTORE_FAILED("baseline affinity restore failed")
}

/** Each entry is one sample; generated text and cross-run aggregates are intentionally omitted. */
internal data class LocalModelSelfTestCaseMetrics(
    val configuration: LocalModelSelfTestConfiguration,
    val runNumber: Int,
    val nThreads: Int,
    val allowedCoreIds: List<Int>?,
    val promptTokenCount: Int?,
    val firstTokenTimeMs: Long?,
    val tokensPerSecond: Double?,
    val status: LocalModelSelfTestRunStatus
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
    private val cancellationSignal = CountDownLatch(CANCELLATION_SIGNAL_COUNT)
    private val abortHandler = AtomicReference<(() -> Unit)?>(null)

    fun isCancelled(): Boolean = cancellationRequested.get()

    fun cancel() {
        cancellationRequested.set(true)
        cancellationSignal.countDown()
        runCatching { abortHandler.get()?.invoke() }
    }

    fun awaitCancellation(timeout: Long, unit: TimeUnit): Boolean =
        cancellationSignal.await(timeout, unit)

    fun attachAbortHandler(handler: () -> Unit) {
        abortHandler.set(handler)
        if (cancellationRequested.get()) runCatching(handler)
    }

    fun clearAbortHandler() {
        abortHandler.set(null)
    }

    private companion object {
        // PROVISIONAL - pending GEN-PERF-1
        const val CANCELLATION_SIGNAL_COUNT = 1
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

    fun currentThreadAffinity(): List<Int>? = backend.currentThreadAffinity()

    fun setThreadAffinity(cpuIds: List<Int>): Boolean = backend.setThreadAffinity(cpuIds)

    fun requestAbort() {
        backend.requestAbortGeneration()
    }

    override fun close() {
        backend.close()
        measurements.set(null)
    }
}

/** Runs the fixed H-3 prompt using A, B, C, C, B, A and retains only per-run numeric metrics. */
internal class LocalModelSelfTestRunner(
    private val sessionFactory: (LocalModelSelfTestCancellation, Int) -> LocalModelSelfTestSession,
    private val isEligible: () -> Boolean,
    private val strongCoreIdsProvider: () -> List<Int>? = {
        CpuMaxFrequencyReader().readStrongCoreIds()
    },
    private val waitBetweenRuns: (LocalModelSelfTestCancellation) -> Boolean = { cancellation ->
        !cancellation.awaitCancellation(COOLDOWN_MILLIS, TimeUnit.MILLISECONDS)
    }
) {
    fun run(cancellation: LocalModelSelfTestCancellation): LocalModelSelfTestOutcome {
        if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

        val caseMetrics = mutableListOf<LocalModelSelfTestCaseMetrics>()
        var lastSession: LocalModelSelfTestSession? = null
        var originalAffinity: List<Int>? = null
        try {
            val strongCoreIds = runCatching(strongCoreIdsProvider)
                .getOrNull()
                ?.distinct()
                ?.sorted()
                ?.takeIf { it.isNotEmpty() }

            for ((index, configuration) in EXECUTION_PLAN.withIndex()) {
                if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

                var session: LocalModelSelfTestSession? = null
                var generated = false
                var affinityRestored = true
                try {
                    session = sessionFactory(cancellation, configuration.nThreads)
                    lastSession = session
                    cancellation.attachAbortHandler(session::requestAbort)
                    if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

                    val allowedBeforeRun = readAllowedCores(session)
                    if (originalAffinity == null && allowedBeforeRun != null) {
                        originalAffinity = allowedBeforeRun
                    }
                    val baseline = originalAffinity
                    val baselineRestoredBeforeRun = when {
                        baseline == null -> false
                        allowedBeforeRun == baseline -> true
                        else -> {
                            runCatching { session.setThreadAffinity(baseline) }
                            readAllowedCores(session) == baseline
                        }
                    }

                    val runNumber = index.inc()
                    val targetCores = if (configuration.isPinned && strongCoreIds != null) {
                        strongCoreIds.take(configuration.nThreads)
                    } else {
                        null
                    }

                    when {
                        configuration.isPinned && strongCoreIds == null -> {
                            caseMetrics += skippedCase(
                                configuration = configuration,
                                runNumber = runNumber,
                                status = LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE,
                                allowedCoreIds = readAllowedCores(session)
                            )
                        }
                        configuration.isPinned && targetCores.orEmpty().size < configuration.nThreads -> {
                            caseMetrics += skippedCase(
                                configuration = configuration,
                                runNumber = runNumber,
                                status = LocalModelSelfTestRunStatus.NOT_ENOUGH_STRONG_CORES,
                                allowedCoreIds = readAllowedCores(session)
                            )
                        }
                        else -> {
                            val allowedForRun: List<Int>?
                            val runStatus: LocalModelSelfTestRunStatus
                            if (configuration.isPinned) {
                                val requestedCores = checkNotNull(targetCores)
                                val pinApplied = originalAffinity != null &&
                                    runCatching { session.setThreadAffinity(requestedCores) }.getOrDefault(false)
                                // Read back the kernel's effective set, including after a failed or narrowed pin.
                                allowedForRun = readAllowedCores(session)
                                runStatus = if (pinApplied && allowedForRun == requestedCores) {
                                    LocalModelSelfTestRunStatus.PINNED
                                } else {
                                    LocalModelSelfTestRunStatus.PIN_FAILED
                                }
                            } else {
                                allowedForRun = readAllowedCores(session)
                                runStatus = when {
                                    allowedForRun == null -> LocalModelSelfTestRunStatus.AFFINITY_UNAVAILABLE
                                    originalAffinity != null &&
                                        (!baselineRestoredBeforeRun || allowedForRun != originalAffinity) ->
                                        LocalModelSelfTestRunStatus.AFFINITY_RESTORE_FAILED
                                    else -> LocalModelSelfTestRunStatus.UNPINNED
                                }
                            }

                            // Apply affinity on this worker before generate() can load the model or create
                            // llama_context. llama.cpp creates disposable ggml workers during decode; on Linux
                            // those new threads inherit this caller thread's affinity mask.
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
                                configuration = configuration,
                                runNumber = runNumber,
                                nThreads = configuration.nThreads,
                                allowedCoreIds = allowedForRun,
                                promptTokenCount = promptTokens,
                                firstTokenTimeMs = measurements.firstTokenTimeMs,
                                tokensPerSecond = measurements.tokensPerSecond,
                                status = runStatus
                            )
                            generated = true
                        }
                    }
                } finally {
                    runCatching { session?.close() }
                    val baseline = originalAffinity
                    if (session != null && baseline != null) {
                        affinityRestored = restoreAffinity(session, baseline)
                    }
                }

                if (!affinityRestored) return LocalModelSelfTestOutcome.Failure
                val hasLaterRunnableRun = EXECUTION_PLAN.drop(index.inc())
                    .any { isRunnable(it, strongCoreIds) }
                if (generated && hasLaterRunnableRun) {
                    if (!waitBetweenRuns(cancellation) || !canContinue(cancellation)) {
                        return LocalModelSelfTestOutcome.Failure
                    }
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

    private fun readAllowedCores(session: LocalModelSelfTestSession): List<Int>? =
        runCatching { session.currentThreadAffinity() }
            .getOrNull()
            ?.distinct()
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }

    private fun restoreAffinity(session: LocalModelSelfTestSession, baseline: List<Int>): Boolean {
        val current = readAllowedCores(session)
        if (current == baseline) return true
        runCatching { session.setThreadAffinity(baseline) }
        return readAllowedCores(session) == baseline
    }

    private fun skippedCase(
        configuration: LocalModelSelfTestConfiguration,
        runNumber: Int,
        status: LocalModelSelfTestRunStatus,
        allowedCoreIds: List<Int>?
    ): LocalModelSelfTestCaseMetrics = LocalModelSelfTestCaseMetrics(
        configuration = configuration,
        runNumber = runNumber,
        nThreads = configuration.nThreads,
        allowedCoreIds = allowedCoreIds,
        promptTokenCount = null,
        firstTokenTimeMs = null,
        tokensPerSecond = null,
        status = status
    )

    private fun isRunnable(
        configuration: LocalModelSelfTestConfiguration,
        strongCoreIds: List<Int>?
    ): Boolean = !configuration.isPinned ||
        (strongCoreIds != null && strongCoreIds.size >= configuration.nThreads)

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
        val EXECUTION_PLAN = listOf(
            LocalModelSelfTestConfiguration.A,
            LocalModelSelfTestConfiguration.B,
            LocalModelSelfTestConfiguration.C,
            LocalModelSelfTestConfiguration.C,
            LocalModelSelfTestConfiguration.B,
            LocalModelSelfTestConfiguration.A
        )
        // PROVISIONAL - pending GEN-PERF-1
        const val COOLDOWN_MILLIS = 20_000L
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
