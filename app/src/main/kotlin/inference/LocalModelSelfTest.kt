package inference

import contracts.GenerateParams
import contracts.InferenceRequest
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class LocalModelSelfTestConfiguration(
    val nThreads: Int,
    val isPinned: Boolean,
    val displayName: String,
    val isPrefill: Boolean = false
) {
    // PROVISIONAL - pending GEN-PERF-1
    WARM_UP_B(nThreads = 2, isPinned = true, displayName = "B warm-up"),
    // PROVISIONAL - pending GEN-PERF-1
    A(nThreads = 1, isPinned = true, displayName = "A"),
    // PROVISIONAL - pending GEN-PERF-1
    B(nThreads = 2, isPinned = true, displayName = "B"),
    // PROVISIONAL - pending GEN-PERF-1
    D(nThreads = 2, isPinned = false, displayName = "D"),
    // PROVISIONAL - pending GEN-PERF-1
    PREFILL_B(nThreads = 2, isPinned = true, displayName = "B prefill", isPrefill = true)
}

internal enum class LocalModelSelfTestRunStatus(val displayLabel: String) {
    PINNED("pinned"),
    UNPINNED("unpinned"),
    PIN_FAILED("pin failed"),
    UNPIN_FAILED("unpin failed"),
    CPU_FREQUENCY_UNAVAILABLE("CPU frequency unavailable"),
    NOT_ENOUGH_STRONG_CORES("not enough strong CPU cores"),
    AFFINITY_UNAVAILABLE("affinity unavailable")
}

/** Each entry is one sample; generated text and cross-run aggregates are intentionally omitted. */
internal data class LocalModelSelfTestCaseMetrics(
    val configuration: LocalModelSelfTestConfiguration,
    val runNumber: Int,
    val nThreads: Int,
    val affinityBeforeCoreIds: List<Int>?,
    val allowedCoreIds: List<Int>?,
    val affinityAfterCoreIds: List<Int>?,
    val promptTokenCount: Int?,
    val firstTokenTimeMs: Long?,
    val prefillTokensPerSecond: Double?,
    val decodeTokensPerSecond: Double?,
    val status: LocalModelSelfTestRunStatus
)

/** The final library system_info string is shown after the complete self-test. */
internal data class LocalModelSelfTestMetrics(
    val cases: List<LocalModelSelfTestCaseMetrics>,
    val systemInfo: String
)

internal sealed class LocalModelSelfTestOutcome {
    data class Success(val metrics: LocalModelSelfTestMetrics) : LocalModelSelfTestOutcome()
    data class PartialFailure(val metrics: LocalModelSelfTestMetrics) : LocalModelSelfTestOutcome()
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

    fun configureThreadCounts(nThreads: Int, nThreadsBatch: Int) =
        backend.configureThreadCounts(nThreads, nThreadsBatch)

    fun requestAbort() {
        backend.requestAbortGeneration()
    }

    override fun close() {
        backend.close()
        measurements.set(null)
    }
}

/** Runs the visible B warm-up, interleaved A/B/D sweep, and two fixed B prefill samples. */
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
        var baselineCaptured = false
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
                var affinitySetupFailed = false
                var affinityBeforeRun: List<Int>? = null
                var affinityAfterRun: List<Int>? = null
                try {
                    session = sessionFactory(cancellation, configuration.nThreads)
                    lastSession = session
                    cancellation.attachAbortHandler(session::requestAbort)
                    if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

                    // Capture once, on this runner's dedicated worker, before the warm-up begins.
                    if (!baselineCaptured) {
                        baselineCaptured = true
                        originalAffinity = readAllowedCores(session)
                    }
                    affinityBeforeRun = readAllowedCores(session)
                    val prepared = prepareRun(session, configuration, originalAffinity, strongCoreIds)
                    if (prepared == null) {
                        affinitySetupFailed = true
                        caseMetrics += failedAffinityCase(
                            configuration = configuration,
                            runNumber = index.inc(),
                            affinityBeforeCoreIds = affinityBeforeRun,
                            allowedCoreIds = readAllowedCores(session)
                        )
                    } else {
                        session.configureThreadCounts(prepared.nThreads, prepared.nThreads)

                        // Verify the mask immediately before the first call that can load a context.
                        val allowedAtGeneration = readAllowedCores(session)
                        if (!prepared.matches(allowedAtGeneration)) return LocalModelSelfTestOutcome.Failure
                        if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

                        val response = session.inferenceEngine.generate(fixedRequest(configuration)).getOrNull()
                            ?: return LocalModelSelfTestOutcome.Failure
                        if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

                        val measurements = session.latestMeasurements()
                            ?: return LocalModelSelfTestOutcome.Failure
                        val promptTokens = response.usage.prompt_tokens
                        val generatedTokens = response.usage.completion_tokens
                        // PROVISIONAL - pending GEN-PERF-1
                        val prefillRate = measurements.prefillTokensPerSecond
                        val decodeRate = measurements.decodeTokensPerSecond
                        if (promptTokens < MIN_PROMPT_TOKENS ||
                            (configuration.isPrefill && promptTokens < MIN_PREFILL_PROMPT_TOKENS) ||
                            generatedTokens !in MIN_OUTPUT_TOKENS..MAX_OUTPUT_TOKENS ||
                            measurements.tokenCount != generatedTokens ||
                            measurements.firstTokenTimeMs < 0L ||
                            prefillRate == null || !prefillRate.isFinite() || prefillRate <= 0.0 ||
                            (decodeRate != null && (!decodeRate.isFinite() || decodeRate <= 0.0))
                        ) {
                            return LocalModelSelfTestOutcome.Failure
                        }

                        caseMetrics += LocalModelSelfTestCaseMetrics(
                            configuration = configuration,
                            runNumber = index.inc(),
                            nThreads = prepared.nThreads,
                            affinityBeforeCoreIds = affinityBeforeRun,
                            allowedCoreIds = allowedAtGeneration,
                            affinityAfterCoreIds = affinityAfterRun,
                            promptTokenCount = promptTokens,
                            firstTokenTimeMs = measurements.firstTokenTimeMs,
                            prefillTokensPerSecond = prefillRate,
                            decodeTokensPerSecond = decodeRate,
                            status = prepared.status
                        )
                        generated = true
                    }
                } finally {
                    // Restore on the same worker in every exit path, before closing the session.
                    val baseline = originalAffinity
                    if (session != null && baseline != null) {
                        affinityRestored = restoreAffinity(session, baseline)
                    }
                    runCatching { session?.close() }
                    affinityAfterRun = session?.let(::readAllowedCores)
                    if (baseline != null && affinityAfterRun != baseline) {
                        affinityRestored = false
                    } else if (baseline == null && affinityBeforeRun != null &&
                        affinityAfterRun != affinityBeforeRun
                    ) {
                        affinityRestored = false
                    }
                    replaceRunAffinities(
                        caseMetrics,
                        index.inc(),
                        affinityBeforeRun,
                        affinityAfterRun
                    )
                }

                if (!affinityRestored) {
                    replaceRunStatus(caseMetrics, index.inc(), LocalModelSelfTestRunStatus.UNPIN_FAILED)
                    return partialFailure(caseMetrics, lastSession)
                }
                if (affinitySetupFailed) return partialFailure(caseMetrics, lastSession)
                if (generated && index < EXECUTION_PLAN.lastIndex) {
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

    private fun failedAffinityCase(
        configuration: LocalModelSelfTestConfiguration,
        runNumber: Int,
        affinityBeforeCoreIds: List<Int>?,
        allowedCoreIds: List<Int>?
    ): LocalModelSelfTestCaseMetrics = LocalModelSelfTestCaseMetrics(
        configuration = configuration,
        runNumber = runNumber,
        nThreads = FALLBACK_N_THREADS,
        affinityBeforeCoreIds = affinityBeforeCoreIds,
        allowedCoreIds = allowedCoreIds,
        affinityAfterCoreIds = null,
        promptTokenCount = null,
        firstTokenTimeMs = null,
        prefillTokensPerSecond = null,
        decodeTokensPerSecond = null,
        status = LocalModelSelfTestRunStatus.UNPIN_FAILED
    )

    private fun replaceRunStatus(
        cases: MutableList<LocalModelSelfTestCaseMetrics>,
        runNumber: Int,
        status: LocalModelSelfTestRunStatus
    ) {
        val position = cases.indexOfLast { it.runNumber == runNumber }
        if (position >= 0) cases[position] = cases[position].copy(status = status)
    }

    private fun replaceRunAffinities(
        cases: MutableList<LocalModelSelfTestCaseMetrics>,
        runNumber: Int,
        affinityBeforeCoreIds: List<Int>?,
        affinityAfterCoreIds: List<Int>?
    ) {
        val position = cases.indexOfLast { it.runNumber == runNumber }
        if (position >= 0) {
            cases[position] = cases[position].copy(
                affinityBeforeCoreIds = affinityBeforeCoreIds,
                affinityAfterCoreIds = affinityAfterCoreIds
            )
        }
    }

    private fun partialFailure(
        cases: List<LocalModelSelfTestCaseMetrics>,
        lastSession: LocalModelSelfTestSession?
    ): LocalModelSelfTestOutcome.PartialFailure {
        val systemInfo = runCatching { lastSession?.systemInfo() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: SYSTEM_INFO_UNAVAILABLE
        return LocalModelSelfTestOutcome.PartialFailure(
            LocalModelSelfTestMetrics(cases = cases.toList(), systemInfo = systemInfo)
        )
    }

    private fun prepareRun(
        session: LocalModelSelfTestSession,
        configuration: LocalModelSelfTestConfiguration,
        baseline: List<Int>?,
        strongCoreIds: List<Int>?
    ): PreparedRun? {
        if (baseline != null && !restoreAffinity(session, baseline)) return null

        if (!configuration.isPinned) {
            if (baseline == null) {
                return PreparedRun(
                    LocalModelSelfTestRunStatus.AFFINITY_UNAVAILABLE,
                    FALLBACK_N_THREADS,
                    expectedMask = readAllowedCores(session)
                )
            }
            val allowed = readAllowedCores(session)
            if (allowed == baseline) {
                return PreparedRun(LocalModelSelfTestRunStatus.UNPINNED, FALLBACK_N_THREADS, baseline)
            }
            // Do not call a run unpinned unless the kernel's actual mask equals the saved mask.
            if (!restoreAffinity(session, baseline) || readAllowedCores(session) != baseline) return null
            return PreparedRun(LocalModelSelfTestRunStatus.UNPINNED, FALLBACK_N_THREADS, baseline)
        }

        if (baseline == null) {
            return PreparedRun(
                LocalModelSelfTestRunStatus.AFFINITY_UNAVAILABLE,
                FALLBACK_N_THREADS,
                expectedMask = null
            )
        }
        if (strongCoreIds == null) {
            return PreparedRun(
                LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE,
                FALLBACK_N_THREADS,
                expectedMask = baseline
            )
        }
        val targetCores = strongCoreIds.take(configuration.nThreads)
        if (targetCores.size < configuration.nThreads) {
            return PreparedRun(
                LocalModelSelfTestRunStatus.NOT_ENOUGH_STRONG_CORES,
                FALLBACK_N_THREADS,
                expectedMask = baseline
            )
        }

        val pinApplied = runCatching { session.setThreadAffinity(targetCores) }.getOrDefault(false)
        val allowedAfterPin = readAllowedCores(session)
        if (pinApplied && allowedAfterPin == targetCores) {
            return PreparedRun(LocalModelSelfTestRunStatus.PINNED, configuration.nThreads, targetCores)
        }
        if (!restoreAffinity(session, baseline)) return null
        return PreparedRun(LocalModelSelfTestRunStatus.PIN_FAILED, FALLBACK_N_THREADS, baseline)
    }

    private data class PreparedRun(
        val status: LocalModelSelfTestRunStatus,
        val nThreads: Int,
        val expectedMask: List<Int>?
    ) {
        fun matches(actualMask: List<Int>?): Boolean = when (status) {
            LocalModelSelfTestRunStatus.AFFINITY_UNAVAILABLE -> expectedMask == null || actualMask == expectedMask
            LocalModelSelfTestRunStatus.PINNED -> actualMask == expectedMask
            LocalModelSelfTestRunStatus.UNPINNED -> actualMask != null && actualMask == expectedMask
            else -> expectedMask == null || actualMask == expectedMask
        }
    }

    private fun readAllowedCores(session: LocalModelSelfTestSession): List<Int>? =
        runCatching { session.currentThreadAffinity() }
            .getOrNull()
            ?.distinct()
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }

    private fun restoreAffinity(session: LocalModelSelfTestSession, baseline: List<Int>): Boolean {
        if (readAllowedCores(session) == baseline) return true
        runCatching { session.setThreadAffinity(baseline) }
        return readAllowedCores(session) == baseline
    }

    private fun canContinue(cancellation: LocalModelSelfTestCancellation): Boolean =
        !cancellation.isCancelled() && runCatching(isEligible).getOrDefault(false)

    private fun fixedRequest(configuration: LocalModelSelfTestConfiguration): InferenceRequest =
        InferenceRequest(
            prompt = if (configuration.isPrefill) PREFILL_PROMPT else SELF_TEST_PROMPT,
            context = SELF_TEST_CONTEXT,
            params = GenerateParams(
                max_tokens = MAX_OUTPUT_TOKENS,
                temperature = SELF_TEST_TEMPERATURE,
                top_p = SELF_TEST_TOP_P
            )
        )

    private companion object {
        // PROVISIONAL - pending GEN-PERF-1
        // Fixed Latin-square crossover: each A/B/D configuration appears once in each position.
        val SWEEP_PLAN = listOf(
            LocalModelSelfTestConfiguration.A,
            LocalModelSelfTestConfiguration.B,
            LocalModelSelfTestConfiguration.D,
            LocalModelSelfTestConfiguration.B,
            LocalModelSelfTestConfiguration.D,
            LocalModelSelfTestConfiguration.A,
            LocalModelSelfTestConfiguration.D,
            LocalModelSelfTestConfiguration.A,
            LocalModelSelfTestConfiguration.B
        )
        // PROVISIONAL - pending GEN-PERF-1
        val EXECUTION_PLAN = listOf(LocalModelSelfTestConfiguration.WARM_UP_B) + SWEEP_PLAN +
            listOf(LocalModelSelfTestConfiguration.PREFILL_B, LocalModelSelfTestConfiguration.PREFILL_B)
        // PROVISIONAL - pending GEN-PERF-1
        const val FALLBACK_N_THREADS = 2
        // PROVISIONAL - pending GEN-PERF-1
        const val COOLDOWN_MILLIS = 20_000L
        // PROVISIONAL - pending GEN-PERF-1
        const val MAX_OUTPUT_TOKENS = 8
        // PROVISIONAL - pending GEN-PERF-1
        const val MIN_OUTPUT_TOKENS = 1
        // PROVISIONAL - pending GEN-PERF-1
        const val MIN_PROMPT_TOKENS = 1
        // PROVISIONAL - pending GEN-PERF-1
        const val MIN_PREFILL_PROMPT_TOKENS = 513
        // PROVISIONAL - pending GEN-PERF-1
        const val SELF_TEST_TEMPERATURE = 0.0f
        // PROVISIONAL - pending GEN-PERF-1
        const val SELF_TEST_TOP_P = 1.0f
        const val PREFILL_SENTENCE =
            "Please reply with one short harmless greeting. This is a fixed CPU prefill check using only neutral sample text and no user data."
        // PROVISIONAL - pending GEN-PERF-1
        const val PREFILL_SENTENCE_REPETITIONS = 22
        // A fixed, approximately 500-token code-only prompt; no user content is appended.
        val PREFILL_PROMPT = List(PREFILL_SENTENCE_REPETITIONS) { PREFILL_SENTENCE }.joinToString(" ")
        const val SELF_TEST_CONTEXT =
            "Local runtime self-test only. Reply with a short harmless greeting. Do not use tools or take actions."
        const val SELF_TEST_PROMPT = "Say hello in a few words."
        const val SYSTEM_INFO_UNAVAILABLE = "System information unavailable"
    }
}
