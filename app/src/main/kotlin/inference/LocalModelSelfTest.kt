package inference

import contracts.GenerateParams
import contracts.InferenceRequest
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

private const val QUICK_PROMPT_TOKEN_TARGET_VALUE = 41
private const val PREFILL_PROMPT_TOKEN_TARGET_VALUE = 606
private const val QUICK_MAX_OUTPUT_TOKENS_VALUE = 16
private const val PREFILL_MAX_OUTPUT_TOKENS_VALUE = 4
private const val SWEEP_MAX_OUTPUT_TOKENS_VALUE = 4
private const val NO_REST_MS_VALUE = 0L
private const val SWEEP_INTER_RUN_REST_MS_VALUE = 5_000L

internal enum class LocalModelSelfTestConfiguration(
    val nThreads: Int,
    val isPinned: Boolean,
    val displayName: String
) {
    A(nThreads = 1, isPinned = true, displayName = "A"),
    B(nThreads = 2, isPinned = true, displayName = "B"),
    D(nThreads = 2, isPinned = false, displayName = "D — saved baseline")
}

/** Separate, explicit test actions. QUICK is the only default action. */
internal enum class LocalModelSelfTestMode(
    val displayName: String,
    val configurations: List<LocalModelSelfTestConfiguration>,
    val targetPromptTokens: Int,
    val maxOutputTokens: Int,
    val interRunRestMs: Long,
    val isPrefillOnly: Boolean
) {
    QUICK(
        displayName = "Quick B",
        configurations = listOf(LocalModelSelfTestConfiguration.B),
        targetPromptTokens = QUICK_PROMPT_TOKEN_TARGET_VALUE,
        maxOutputTokens = QUICK_MAX_OUTPUT_TOKENS_VALUE,
        interRunRestMs = NO_REST_MS_VALUE,
        isPrefillOnly = false
    ),
    PREFILL_606(
        displayName = "B prefill 606",
        configurations = listOf(LocalModelSelfTestConfiguration.B),
        targetPromptTokens = PREFILL_PROMPT_TOKEN_TARGET_VALUE,
        maxOutputTokens = PREFILL_MAX_OUTPUT_TOKENS_VALUE,
        interRunRestMs = NO_REST_MS_VALUE,
        isPrefillOnly = true
    ),
    /** Debug-only UI action: one A, one B, one D pass; never run as a startup/default test. */
    SWEEP_ABD(
        displayName = "A/B/D debug sweep",
        configurations = listOf(
            LocalModelSelfTestConfiguration.A,
            LocalModelSelfTestConfiguration.B,
            LocalModelSelfTestConfiguration.D
        ),
        targetPromptTokens = QUICK_PROMPT_TOKEN_TARGET_VALUE,
        maxOutputTokens = SWEEP_MAX_OUTPUT_TOKENS_VALUE,
        interRunRestMs = SWEEP_INTER_RUN_REST_MS_VALUE,
        isPrefillOnly = false
    );

    val runCount: Int get() = configurations.size

    fun basePrompt(): String = if (isPrefillOnly) PREFILL_BASE_PROMPT else QUICK_BASE_PROMPT

    companion object {
        const val QUICK_PROMPT_TOKEN_TARGET = QUICK_PROMPT_TOKEN_TARGET_VALUE
        const val PREFILL_PROMPT_TOKEN_TARGET = PREFILL_PROMPT_TOKEN_TARGET_VALUE
        const val QUICK_MAX_OUTPUT_TOKENS = QUICK_MAX_OUTPUT_TOKENS_VALUE
        const val PREFILL_MAX_OUTPUT_TOKENS = PREFILL_MAX_OUTPUT_TOKENS_VALUE
        const val SWEEP_MAX_OUTPUT_TOKENS = SWEEP_MAX_OUTPUT_TOKENS_VALUE
        const val NO_REST_MS = NO_REST_MS_VALUE
        const val SWEEP_INTER_RUN_REST_MS = SWEEP_INTER_RUN_REST_MS_VALUE

        const val PREFILL_SENTENCE =
            "Please reply with one short harmless greeting. This is a fixed CPU prefill check using only neutral sample text and no user data."
        const val PREFILL_SENTENCE_REPETITIONS = 22
        val PREFILL_BASE_PROMPT: String =
            List(PREFILL_SENTENCE_REPETITIONS) { PREFILL_SENTENCE }.joinToString(" ")

        const val QUICK_BASE_PROMPT = "Say hello in a few words."
    }
}

/** Planning budgets, deliberately conservative and not device performance claims. */
internal object LocalModelSelfTestTiming {
    const val TIMEOUT_MS = 180_000L
    // Planning allowance from beginning a run to its first token; includes loading/token-count setup.
    // It is not the native TTFT metric, which is reported separately.
    const val EXPECTED_FIRST_TOKEN_WALL_BUDGET_MS = 30_000L
    // Conservative end of the user's observed decode range; only used for the estimate below.
    const val EXPECTED_DECODE_TOKENS_PER_SECOND = 1.1

    fun expectedDecodeBudgetMs(mode: LocalModelSelfTestMode): Long {
        val decodeTokens = (mode.maxOutputTokens - 1).coerceAtLeast(0)
        return ceil(decodeTokens * MILLIS_PER_SECOND / EXPECTED_DECODE_TOKENS_PER_SECOND).toLong()
    }

    fun expectedDurationMs(mode: LocalModelSelfTestMode): Long {
        val perRunBudgetMs = EXPECTED_FIRST_TOKEN_WALL_BUDGET_MS +
            expectedDecodeBudgetMs(mode) + mode.interRunRestMs
        // Use N * rest (rather than N-1) as the conservative report formula requested.
        return mode.runCount.toLong() * perRunBudgetMs
    }

    private const val MILLIS_PER_SECOND = 1_000.0
}

internal enum class LocalModelSelfTestRunStatus(val displayLabel: String) {
    PINNED("pinned; readback verified"),
    BASELINE_UNPINNED("saved baseline; no explicit pin"),
    PIN_FAILED("pin failed"),
    UNPIN_FAILED("affinity restore failed"),
    CPU_FREQUENCY_UNAVAILABLE("CPU frequency unavailable"),
    NOT_ENOUGH_STRONG_CORES("not enough strong CPU cores"),
    AFFINITY_UNAVAILABLE("affinity unavailable"),
    PROMPT_TOKEN_TARGET_UNAVAILABLE("could not form exact prompt-token target"),
    PROMPT_TOKEN_TARGET_MISMATCH("prompt-token target mismatch"),
    INVALID_METRICS("invalid inference metrics"),
    INFERENCE_FAILED("inference failed")
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

/** The final library system_info string is shown after the selected test action. */
internal data class LocalModelSelfTestMetrics(
    val mode: LocalModelSelfTestMode,
    val cases: List<LocalModelSelfTestCaseMetrics>,
    val systemInfo: String
)

internal data class LocalModelSelfTestProgress(
    val mode: LocalModelSelfTestMode,
    val runNumber: Int,
    val totalRuns: Int,
    val configuration: LocalModelSelfTestConfiguration
)

internal sealed class LocalModelSelfTestOutcome {
    data class Success(val metrics: LocalModelSelfTestMetrics) : LocalModelSelfTestOutcome()
    data class PartialFailure(val metrics: LocalModelSelfTestMetrics) : LocalModelSelfTestOutcome()
    object Failure : LocalModelSelfTestOutcome()
    object Cancelled : LocalModelSelfTestOutcome()
    object TimedOut : LocalModelSelfTestOutcome()
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

    /** Uses the pinned model's chat template and tokenizer; it does no model generation. */
    fun formattedPromptTokenCount(context: String, prompt: String): Int? =
        backend.formattedPromptTokenCount(context, prompt)

    fun requestAbort() {
        backend.requestAbortGeneration()
    }

    override fun close() {
        backend.close()
        measurements.set(null)
    }
}

/** Runs exactly the selected plan: one quick B run by default, or a separate prefill/debug action. */
internal class LocalModelSelfTestRunner(
    private val sessionFactory: (LocalModelSelfTestCancellation, Int) -> LocalModelSelfTestSession,
    private val isEligible: () -> Boolean,
    private val strongCoreIdsProvider: () -> List<Int>? = {
        CpuMaxFrequencyReader().readStrongCoreIds()
    },
    private val waitBetweenRuns: (LocalModelSelfTestCancellation, Long) -> Boolean = { cancellation, restMs ->
        restMs <= 0L || !cancellation.awaitCancellation(restMs, TimeUnit.MILLISECONDS)
    }
) {
    fun run(
        cancellation: LocalModelSelfTestCancellation,
        mode: LocalModelSelfTestMode = LocalModelSelfTestMode.QUICK,
        onProgress: (LocalModelSelfTestProgress) -> Unit = {}
    ): LocalModelSelfTestOutcome {
        if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

        val caseMetrics = mutableListOf<LocalModelSelfTestCaseMetrics>()
        var lastSession: LocalModelSelfTestSession? = null
        var baselineCaptured = false
        var originalAffinity: List<Int>? = null
        var anyRunFailed = false

        try {
            val strongCoreIds = runCatching(strongCoreIdsProvider)
                .getOrNull()
                ?.distinct()
                ?.sorted()
                ?.takeIf { it.isNotEmpty() }

            for ((index, configuration) in mode.configurations.withIndex()) {
                if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure
                runCatching {
                    onProgress(
                        LocalModelSelfTestProgress(
                            mode = mode,
                            runNumber = index.inc(),
                            totalRuns = mode.runCount,
                            configuration = configuration
                        )
                    )
                }

                var session: LocalModelSelfTestSession? = null
                var affinityBeforeRun: List<Int>? = null
                var affinityDuringRun: List<Int>? = null
                var affinityAfterRun: List<Int>? = null
                var nThreads = configuration.nThreads
                var promptTokens: Int? = null
                var firstTokenTimeMs: Long? = null
                var prefillRate: Double? = null
                var decodeRate: Double? = null
                var status = LocalModelSelfTestRunStatus.INFERENCE_FAILED
                var issue = false
                var fatalIssue = false
                var generated = false
                var affinityRestored = true

                try {
                    session = sessionFactory(cancellation, configuration.nThreads)
                    lastSession = session
                    cancellation.attachAbortHandler(session::requestAbort)
                    if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

                    // Capture the worker's original mask once, before any explicit pin is applied.
                    if (!baselineCaptured) {
                        baselineCaptured = true
                        originalAffinity = readAllowedCores(session)
                    }
                    affinityBeforeRun = readAllowedCores(session)

                    val prepared = prepareRun(
                        session = session,
                        configuration = configuration,
                        baseline = originalAffinity,
                        strongCoreIds = strongCoreIds
                    )
                    if (prepared == null) {
                        status = LocalModelSelfTestRunStatus.UNPIN_FAILED
                        affinityDuringRun = readAllowedCores(session)
                        issue = true
                        fatalIssue = true
                    } else {
                        status = prepared.status
                        nThreads = prepared.nThreads
                        session.configureThreadCounts(prepared.nThreads, prepared.nThreads)
                        affinityDuringRun = readAllowedCores(session)

                        if (!prepared.canGenerate) {
                            issue = true
                        } else if (!prepared.matches(affinityDuringRun)) {
                            status = if (configuration.isPinned) {
                                LocalModelSelfTestRunStatus.PIN_FAILED
                            } else {
                                LocalModelSelfTestRunStatus.AFFINITY_UNAVAILABLE
                            }
                            issue = true
                        } else {
                            val promptBuild = exactPromptForTarget(
                                session = session,
                                context = SELF_TEST_CONTEXT,
                                basePrompt = mode.basePrompt(),
                                targetTokenCount = mode.targetPromptTokens,
                                cancellation = cancellation
                            )
                            promptTokens = promptBuild.observedTokenCount
                            if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure
                            val exactPrompt = promptBuild.prompt
                            if (exactPrompt == null) {
                                status = LocalModelSelfTestRunStatus.PROMPT_TOKEN_TARGET_UNAVAILABLE
                                issue = true
                                fatalIssue = true
                            } else {
                                // Read back on the same worker immediately before generation.
                                affinityDuringRun = readAllowedCores(session)
                                if (!prepared.matches(affinityDuringRun)) {
                                    status = if (configuration.isPinned) {
                                        LocalModelSelfTestRunStatus.PIN_FAILED
                                    } else {
                                        LocalModelSelfTestRunStatus.AFFINITY_UNAVAILABLE
                                    }
                                    issue = true
                                } else if (!canContinue(cancellation)) {
                                    return LocalModelSelfTestOutcome.Failure
                                } else {
                                    val response = session.inferenceEngine.generate(
                                        fixedRequest(mode, exactPrompt)
                                    ).getOrNull()
                                    if (response == null) {
                                        status = LocalModelSelfTestRunStatus.INFERENCE_FAILED
                                        issue = true
                                        fatalIssue = true
                                    } else if (!canContinue(cancellation)) {
                                        return LocalModelSelfTestOutcome.Failure
                                    } else {
                                        val measurements = session.latestMeasurements()
                                        val actualPromptTokens = response.usage.prompt_tokens
                                        val generatedTokens = response.usage.completion_tokens
                                        promptTokens = actualPromptTokens
                                        firstTokenTimeMs = measurements?.firstTokenTimeMs?.takeIf { generatedTokens > 0 }
                                        prefillRate = measurements?.prefillTokensPerSecond
                                        decodeRate = measurements?.decodeTokensPerSecond

                                        val outputCountValid = if (mode.isPrefillOnly) {
                                            generatedTokens in 0..mode.maxOutputTokens
                                        } else {
                                            generatedTokens in MIN_OUTPUT_TOKENS..mode.maxOutputTokens
                                        }
                                        val firstTokenValid = (mode.isPrefillOnly && generatedTokens == 0) ||
                                            (measurements?.firstTokenTimeMs ?: -1L) > 0L
                                        val validMetrics = measurements != null &&
                                            actualPromptTokens == mode.targetPromptTokens &&
                                            outputCountValid &&
                                            measurements.tokenCount == generatedTokens &&
                                            firstTokenValid &&
                                            prefillRate != null && prefillRate.isFinite() && prefillRate > 0.0 &&
                                            (decodeRate == null || (decodeRate.isFinite() && decodeRate > 0.0))
                                        if (actualPromptTokens != mode.targetPromptTokens) {
                                            status = LocalModelSelfTestRunStatus.PROMPT_TOKEN_TARGET_MISMATCH
                                            issue = true
                                            fatalIssue = true
                                        } else if (!validMetrics) {
                                            status = LocalModelSelfTestRunStatus.INVALID_METRICS
                                            issue = true
                                            fatalIssue = true
                                        } else {
                                            status = prepared.status
                                            generated = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    status = LocalModelSelfTestRunStatus.INFERENCE_FAILED
                    issue = true
                    fatalIssue = true
                } finally {
                    // Restore on the same dedicated worker in every exit path, before closing.
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
                }

                if (!affinityRestored) {
                    status = LocalModelSelfTestRunStatus.UNPIN_FAILED
                    issue = true
                    fatalIssue = true
                }
                caseMetrics += LocalModelSelfTestCaseMetrics(
                    configuration = configuration,
                    runNumber = index.inc(),
                    nThreads = nThreads,
                    affinityBeforeCoreIds = affinityBeforeRun,
                    allowedCoreIds = affinityDuringRun,
                    affinityAfterCoreIds = affinityAfterRun,
                    promptTokenCount = promptTokens,
                    firstTokenTimeMs = firstTokenTimeMs,
                    prefillTokensPerSecond = prefillRate,
                    decodeTokensPerSecond = decodeRate,
                    status = status
                )

                if (cancellation.isCancelled()) return LocalModelSelfTestOutcome.Failure
                if (issue) {
                    anyRunFailed = true
                    // Sweep pin/readback failures remain visible as skipped samples; other failures
                    // are terminal. Quick and prefill actions fail closed if B cannot be verified.
                    if (fatalIssue || mode != LocalModelSelfTestMode.SWEEP_ABD) {
                        return partialFailure(mode, caseMetrics, lastSession)
                    }
                }
                if (generated && index < mode.configurations.lastIndex && mode.interRunRestMs > 0L) {
                    if (!waitBetweenRuns(cancellation, mode.interRunRestMs) || !canContinue(cancellation)) {
                        return LocalModelSelfTestOutcome.Failure
                    }
                }
            }

            if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure
            val systemInfo = lastSession?.systemInfo()?.takeIf(String::isNotBlank)
                ?: return LocalModelSelfTestOutcome.Failure
            if (!canContinue(cancellation)) return LocalModelSelfTestOutcome.Failure

            val metrics = LocalModelSelfTestMetrics(
                mode = mode,
                cases = caseMetrics.toList(),
                systemInfo = systemInfo
            )
            return if (anyRunFailed) {
                LocalModelSelfTestOutcome.PartialFailure(metrics)
            } else {
                LocalModelSelfTestOutcome.Success(metrics)
            }
        } catch (_: Throwable) {
            return LocalModelSelfTestOutcome.Failure
        } finally {
            cancellation.clearAbortHandler()
        }
    }

    private data class PromptBuildResult(
        val prompt: String?,
        val observedTokenCount: Int?
    )

    private fun exactPromptForTarget(
        session: LocalModelSelfTestSession,
        context: String,
        basePrompt: String,
        targetTokenCount: Int,
        cancellation: LocalModelSelfTestCancellation
    ): PromptBuildResult {
        var prompt = basePrompt
        var tokenCount = session.formattedPromptTokenCount(context, prompt)
            ?: return PromptBuildResult(null, null)
        if (tokenCount > targetTokenCount) return PromptBuildResult(null, tokenCount)
        if (tokenCount == targetTokenCount) return PromptBuildResult(prompt, tokenCount)

        var attempts = 0
        while (tokenCount < targetTokenCount && attempts++ < MAX_PROMPT_PADDING_STEPS) {
            if (!canContinue(cancellation)) return PromptBuildResult(null, tokenCount)
            var bestNextPrompt: String? = null
            var bestNextCount = tokenCount
            for (atom in PROMPT_PADDING_ATOMS) {
                if (!canContinue(cancellation)) return PromptBuildResult(null, tokenCount)
                val candidate = "$prompt $atom"
                val candidateCount = session.formattedPromptTokenCount(context, candidate) ?: continue
                if (candidateCount == targetTokenCount) return PromptBuildResult(candidate, candidateCount)
                if (candidateCount == tokenCount + 1) {
                    bestNextPrompt = candidate
                    bestNextCount = candidateCount
                    break
                }
                if (candidateCount in (bestNextCount + 1) until targetTokenCount) {
                    bestNextPrompt = candidate
                    bestNextCount = candidateCount
                }
            }
            if (bestNextPrompt == null) return PromptBuildResult(null, tokenCount)
            prompt = bestNextPrompt
            tokenCount = bestNextCount
        }
        return if (tokenCount == targetTokenCount) PromptBuildResult(prompt, tokenCount)
        else PromptBuildResult(null, tokenCount)
    }

    private data class PreparedRun(
        val status: LocalModelSelfTestRunStatus,
        val nThreads: Int,
        val expectedMask: List<Int>?,
        val canGenerate: Boolean
    ) {
        fun matches(actualMask: List<Int>?): Boolean = actualMask != null && actualMask == expectedMask
    }

    /** Returns null only when restoring the original mask itself failed. */
    private fun prepareRun(
        session: LocalModelSelfTestSession,
        configuration: LocalModelSelfTestConfiguration,
        baseline: List<Int>?,
        strongCoreIds: List<Int>?
    ): PreparedRun? {
        if (baseline == null) {
            return PreparedRun(
                LocalModelSelfTestRunStatus.AFFINITY_UNAVAILABLE,
                configuration.nThreads,
                expectedMask = null,
                canGenerate = false
            )
        }
        if (!restoreAffinity(session, baseline)) return null

        if (!configuration.isPinned) {
            val currentMask = readAllowedCores(session)
            return if (currentMask == baseline) {
                PreparedRun(
                    LocalModelSelfTestRunStatus.BASELINE_UNPINNED,
                    FALLBACK_N_THREADS,
                    expectedMask = baseline,
                    canGenerate = true
                )
            } else {
                null
            }
        }

        if (strongCoreIds == null) {
            return PreparedRun(
                LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE,
                configuration.nThreads,
                expectedMask = baseline,
                canGenerate = false
            )
        }
        val targetCores = strongCoreIds.take(configuration.nThreads)
        if (targetCores.size < configuration.nThreads) {
            return PreparedRun(
                LocalModelSelfTestRunStatus.NOT_ENOUGH_STRONG_CORES,
                configuration.nThreads,
                expectedMask = baseline,
                canGenerate = false
            )
        }

        val pinApplied = runCatching { session.setThreadAffinity(targetCores) }.getOrDefault(false)
        val allowedAfterPin = readAllowedCores(session)
        if (pinApplied && allowedAfterPin == targetCores) {
            return PreparedRun(
                LocalModelSelfTestRunStatus.PINNED,
                configuration.nThreads,
                expectedMask = targetCores,
                canGenerate = true
            )
        }
        if (!restoreAffinity(session, baseline)) return null
        return PreparedRun(
            LocalModelSelfTestRunStatus.PIN_FAILED,
            configuration.nThreads,
            expectedMask = baseline,
            canGenerate = false
        )
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

    private fun fixedRequest(mode: LocalModelSelfTestMode, prompt: String): InferenceRequest =
        InferenceRequest(
            prompt = prompt,
            context = SELF_TEST_CONTEXT,
            params = GenerateParams(
                max_tokens = mode.maxOutputTokens,
                temperature = SELF_TEST_TEMPERATURE,
                top_p = SELF_TEST_TOP_P
            )
        )

    private fun partialFailure(
        mode: LocalModelSelfTestMode,
        cases: List<LocalModelSelfTestCaseMetrics>,
        lastSession: LocalModelSelfTestSession?
    ): LocalModelSelfTestOutcome.PartialFailure {
        val systemInfo = runCatching { lastSession?.systemInfo() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: SYSTEM_INFO_UNAVAILABLE
        return LocalModelSelfTestOutcome.PartialFailure(
            LocalModelSelfTestMetrics(mode = mode, cases = cases.toList(), systemInfo = systemInfo)
        )
    }

    private companion object {
        const val FALLBACK_N_THREADS = 2
        const val MIN_OUTPUT_TOKENS = 1
        const val MAX_PROMPT_PADDING_STEPS = 700
        const val SELF_TEST_TEMPERATURE = 0.0f
        const val SELF_TEST_TOP_P = 1.0f
        const val SELF_TEST_CONTEXT =
            "Local runtime self-test only. Reply with a short harmless greeting. Do not use tools or take actions."
        const val SYSTEM_INFO_UNAVAILABLE = "System information unavailable"
        val PROMPT_PADDING_ATOMS = listOf(
            "neutral", "sample", "plain", "local", "CPU", "check", "safe", "harmless",
            "fixed", "ordinary", "text", "data", "please", "reply", "greeting", "one",
            "short", "small", "token", "hello", "a", "the", "x", "okay", "0", "."
        )
    }
}
