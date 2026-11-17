package inference

import contracts.GenerateParams
import contracts.InferenceError
import contracts.InferenceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import reasoning.StructuredGrammarConstraint
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LlamaCppInferenceBackendTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class FakeBridge : LlamaNativeBridge {
        // PROVISIONAL - pending GEN-PERF-1
        var loadCalls = INITIAL_CALL_COUNT
        // PROVISIONAL - pending GEN-PERF-1
        var releaseCalls = INITIAL_CALL_COUNT
        // PROVISIONAL - pending GEN-PERF-1
        var cancelCalls = INITIAL_CALL_COUNT
        // PROVISIONAL - pending GEN-PERF-1
        var generateCalls = INITIAL_CALL_COUNT
        var recordedContext: String? = null
        var recordedPrompt: String? = null
        val recordedPrompts = mutableListOf<String>()
        val releasedAffinityMasks = mutableListOf<List<Int>>()
        var recordedGrammar: String? = null
        var recordedMaxTokens: Int? = null
        val recordedThreadCounts = mutableListOf<Int>()
        val recordedBatchThreadCounts = mutableListOf<Int>()
        val recordedMaxTokenBudgets = mutableListOf<Int>()
        var systemInfoCalls = INITIAL_CALL_COUNT
        var fakeSystemInfo = "Fake CPU system info"
        var loadOverride: (() -> Long)? = null
        var generateOverride: (() -> NativeGenerationResult)? = null
        var cancelOverride: (() -> Unit)? = null
        var fakeAllowedCores = FAKE_BASELINE_CORES
        var rejectedAffinityMasks: Set<List<Int>> = emptySet()
        var effectiveAffinityOverrides: Map<List<Int>, List<Int>> = emptyMap()
        val loadedAffinityMasks = mutableListOf<List<Int>>()
        val generatedAffinityMasks = mutableListOf<List<Int>>()

        override fun currentThreadAffinity(): IntArray? = fakeAllowedCores.toIntArray()

        override fun setThreadAffinity(cpuIds: IntArray): Boolean {
            val requested = cpuIds.toList().distinct().sorted()
            if (requested in rejectedAffinityMasks) return false
            fakeAllowedCores = effectiveAffinityOverrides[requested] ?: requested
            return true
        }

        override fun loadModel(
            modelPath: String,
            contextTokens: Int,
            nThreads: Int,
            nThreadsBatch: Int
        ): Long {
            loadCalls += CALL_COUNT_INCREMENT
            recordedThreadCounts += nThreads
            recordedBatchThreadCounts += nThreadsBatch
            loadedAffinityMasks += fakeAllowedCores
            return loadOverride?.invoke() ?: FAKE_NATIVE_HANDLE
        }

        override fun systemInfo(): String {
            systemInfoCalls += CALL_COUNT_INCREMENT
            return fakeSystemInfo
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
            generateCalls += CALL_COUNT_INCREMENT
            generatedAffinityMasks += fakeAllowedCores
            recordedContext = context
            recordedPrompt = prompt
            recordedPrompts += prompt
            recordedGrammar = grammar
            recordedMaxTokens = maxTokens
            recordedMaxTokenBudgets += maxTokens
            return generateOverride?.invoke() ?: NativeGenerationResult(
                "native output",
                promptTokens = if (prompt.length > PREFILL_PROMPT_MIN_CHARS) {
                    FAKE_PREFILL_PROMPT_TOKEN_COUNT
                } else {
                    FAKE_PROMPT_TOKEN_COUNT
                },
                completionTokens = FAKE_COMPLETION_TOKEN_COUNT,
                finishReason = "stop",
                prefillElapsedNanos = FAKE_PREFILL_ELAPSED_NANOS,
                firstTokenElapsedNanos = FAKE_FIRST_TOKEN_ELAPSED_NANOS,
                decodeElapsedNanos = FAKE_DECODE_ELAPSED_NANOS
            )
        }

        override fun releaseModel(handle: Long) {
            releaseCalls += CALL_COUNT_INCREMENT
            releasedAffinityMasks += fakeAllowedCores
        }

        override fun cancelGeneration(handle: Long) {
            cancelCalls += CALL_COUNT_INCREMENT
            cancelOverride?.invoke()
        }
    }

    @Test
    fun missingVerifiedAssetReturnsUnavailableWithoutLoadingNativeLibrary() {
        val store = ModelAssetStore(temporaryFolder.newFolder(), "not-imported", "fixture.gguf")
        val bridge = FakeBridge()
        val backend = LlamaCppInferenceBackend(store, ContextWindowBudgetPolicy().maxContextTokens, bridge)

        val result = backend.generate(request())

        assertTrue(result.isFailure)
        assertTrue((result.exceptionOrNull() as AgentModelException).error is InferenceError.Unavailable)
        assertEquals(INITIAL_CALL_COUNT, bridge.loadCalls)
    }

    @Test
    fun verifiedAssetUsesInjectedCpuBridgeAndReportsReportedTokenUsage() {
        val store = installedStore()
        val bridge = FakeBridge()
        val backend = LlamaCppInferenceBackend(store, ContextWindowBudgetPolicy().maxContextTokens, bridge)

        val result = backend.generate(request())

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals("native output", response.text)
        assertEquals(FAKE_PROMPT_TOKEN_COUNT, response.usage.prompt_tokens)
        assertEquals(FAKE_COMPLETION_TOKEN_COUNT, response.usage.completion_tokens)
        assertEquals("stop", response.finish_reason)
        assertEquals("system context", bridge.recordedContext)
        assertEquals("user prompt", bridge.recordedPrompt)
        assertEquals(SINGLE_CALL_COUNT, bridge.loadCalls)
        backend.close()
        assertEquals(SINGLE_CALL_COUNT, bridge.releaseCalls)
    }

    @Test
    fun frozenCognitiveSchemaIsMappedToTheExistingGbnfGrammar() {
        val store = installedStore()
        val bridge = FakeBridge()
        val backend = LlamaCppInferenceBackend(store, ContextWindowBudgetPolicy().maxContextTokens, bridge)

        val result = backend.generate(request(StructuredGrammarConstraint.cognitiveCallSchemaJson))

        assertTrue(result.isSuccess)
        assertEquals(StructuredGrammarConstraint.gbnfGrammarDefinition, bridge.recordedGrammar)
    }

    @Test
    fun arbitrarySchemaIsRejectedBeforeCallingTheNativeBridge() {
        val store = installedStore()
        val bridge = FakeBridge()
        val backend = LlamaCppInferenceBackend(store, ContextWindowBudgetPolicy().maxContextTokens, bridge)

        val result = backend.generate(request("{\"type\":\"string\"}"))

        assertTrue(result.isFailure)
        assertTrue((result.exceptionOrNull() as AgentModelException).error is InferenceError.Malformed)
        assertEquals(INITIAL_CALL_COUNT, bridge.loadCalls)
    }

    @Test
    fun nonStreamingCapabilityIsReportedHonestly() {
        val engine = LocalInferenceEngine(
            supportsStreaming = false,
            supportsSeed = false,
            supportsCancellation = false
        )

        val capabilities = engine.capabilities()

        assertFalse(capabilities.supports_streaming)
        assertFalse(capabilities.supports_seed)
        assertFalse(capabilities.supports_cancellation)
    }

    @Test
    fun selfTestRunsWarmUpInterleavedABDSweepAndPrefillWithPerRunMetrics() {
        val bridge = FakeBridge()
        val outcome = selfTestRunner(installedStore(), bridge).run(LocalModelSelfTestCancellation())

        assertTrue(outcome is LocalModelSelfTestOutcome.Success)
        assertEquals(LOCAL_SELF_TEST_MAX_OUTPUT_TOKENS, bridge.recordedMaxTokens)
        assertEquals(List(SELF_TEST_RUN_NUMBERS.size) { LOCAL_SELF_TEST_MAX_OUTPUT_TOKENS }, bridge.recordedMaxTokenBudgets)
        assertEquals("Say hello in a few words.", bridge.recordedPrompts.first())
        assertTrue(bridge.recordedPrompts.take(SHORT_PROMPT_RUN_COUNT).all { it == "Say hello in a few words." })
        assertEquals(bridge.recordedPrompts[SHORT_PROMPT_RUN_COUNT], bridge.recordedPrompts.last())
        assertTrue(bridge.recordedPrompts.last().contains("no user data"))
        assertEquals(
            "Local runtime self-test only. Reply with a short harmless greeting. Do not use tools or take actions.",
            bridge.recordedContext
        )
        assertEquals(SELF_TEST_THREAD_COUNTS, bridge.recordedThreadCounts)
        assertEquals(SELF_TEST_THREAD_COUNTS, bridge.recordedBatchThreadCounts)
        assertEquals(SELF_TEST_RUN_NUMBERS.size, bridge.loadCalls)
        assertEquals(SELF_TEST_RUN_NUMBERS.size, bridge.releaseCalls)
        assertEquals(List(SELF_TEST_RUN_NUMBERS.size) { FAKE_BASELINE_CORES }, bridge.releasedAffinityMasks)
        assertEquals(SINGLE_CALL_COUNT, bridge.systemInfoCalls)
        assertFalse(outcome.toString().contains("native output"))

        val metrics = (outcome as LocalModelSelfTestOutcome.Success).metrics
        assertEquals(SELF_TEST_CONFIGURATION_ORDER, metrics.cases.map { it.configuration })
        assertEquals(SELF_TEST_RUN_NUMBERS, metrics.cases.map { it.runNumber })
        assertEquals(SELF_TEST_THREAD_COUNTS, metrics.cases.map { it.nThreads })
        assertEquals(
            List(SELF_TEST_RUN_NUMBERS.size) { FAKE_BASELINE_CORES },
            metrics.cases.map { it.affinityBeforeCoreIds }
        )
        assertEquals(
            List(SELF_TEST_RUN_NUMBERS.size) { FAKE_BASELINE_CORES },
            metrics.cases.map { it.affinityAfterCoreIds }
        )
        assertEquals(
            listOf(
                LocalModelSelfTestConfiguration.A,
                LocalModelSelfTestConfiguration.B,
                LocalModelSelfTestConfiguration.D,
                LocalModelSelfTestConfiguration.B,
                LocalModelSelfTestConfiguration.D,
                LocalModelSelfTestConfiguration.A,
                LocalModelSelfTestConfiguration.D,
                LocalModelSelfTestConfiguration.A,
                LocalModelSelfTestConfiguration.B
            ),
            metrics.cases.drop(1).take(SWEEP_RUN_COUNT).map { it.configuration }
        )
        assertEquals(
            List(SHORT_PROMPT_RUN_COUNT) { FAKE_PROMPT_TOKEN_COUNT } +
                List(PREFILL_RUN_COUNT) { FAKE_PREFILL_PROMPT_TOKEN_COUNT },
            metrics.cases.map { it.promptTokenCount }
        )
        // PROVISIONAL - pending GEN-PERF-1
        assertTrue(metrics.cases.all { (it.firstTokenTimeMs ?: 0L) > 0L })
        // PROVISIONAL - pending GEN-PERF-1
        assertTrue(metrics.cases.all { (it.prefillTokensPerSecond ?: 0.0) > 0.0 })
        // PROVISIONAL - pending GEN-PERF-1
        assertTrue(metrics.cases.all { (it.decodeTokensPerSecond ?: 0.0) > 0.0 })
        // PROVISIONAL - pending GEN-PERF-1
        assertTrue(metrics.cases.first().prefillTokensPerSecond != metrics.cases.first().decodeTokensPerSecond)
        assertEquals(FAKE_STRONG_CORE_IDS, metrics.cases.first().allowedCoreIds)
        assertEquals(
            listOf(FAKE_STRONG_CORE_IDS.first()),
            metrics.cases.first { it.configuration == LocalModelSelfTestConfiguration.A }.allowedCoreIds
        )
        assertEquals(
            FAKE_BASELINE_CORES,
            metrics.cases.first { it.configuration == LocalModelSelfTestConfiguration.D }.allowedCoreIds
        )
        assertEquals(SELF_TEST_ALLOWED_MASKS, bridge.loadedAffinityMasks)
        assertEquals(SELF_TEST_ALLOWED_MASKS, bridge.generatedAffinityMasks)
        assertEquals(SELF_TEST_STATUS_ORDER, metrics.cases.map { it.status })
        assertEquals(bridge.fakeSystemInfo, metrics.systemInfo)
    }

    @Test
    fun failedPinsAreMarkedAndDoNotAbortTheRemainingSweep() {
        val bridge = FakeBridge().apply {
            rejectedAffinityMasks = setOf(
                listOf(FAKE_STRONG_CORE_IDS.first()),
                FAKE_STRONG_CORE_IDS
            )
        }

        val outcome = selfTestRunner(installedStore(), bridge).run(LocalModelSelfTestCancellation())

        assertTrue(outcome is LocalModelSelfTestOutcome.Success)
        val cases = (outcome as LocalModelSelfTestOutcome.Success).metrics.cases
        assertEquals(SELF_TEST_RUN_NUMBERS.size, bridge.generateCalls)
        assertEquals(SELF_TEST_FAILED_PIN_STATUS_ORDER, cases.map { it.status })
        assertEquals(List(SELF_TEST_RUN_NUMBERS.size) { FALLBACK_THREAD_COUNT }, cases.map { it.nThreads })
        assertTrue(cases.all { it.affinityBeforeCoreIds == FAKE_BASELINE_CORES })
        assertTrue(cases.all { it.allowedCoreIds == FAKE_BASELINE_CORES })
        assertTrue(cases.all { it.affinityAfterCoreIds == FAKE_BASELINE_CORES })
        assertEquals(FAKE_BASELINE_CORES, bridge.fakeAllowedCores)
    }

    @Test
    fun effectiveAffinityIsReadBackAndARestrictedMaskIsReportedAsPinFailed() {
        val bridge = FakeBridge().apply {
            effectiveAffinityOverrides = mapOf(FAKE_STRONG_CORE_IDS to listOf(FAKE_STRONG_CORE_IDS.first()))
        }

        val outcome = selfTestRunner(installedStore(), bridge).run(LocalModelSelfTestCancellation())

        assertTrue(outcome is LocalModelSelfTestOutcome.Success)
        val cases = (outcome as LocalModelSelfTestOutcome.Success).metrics.cases
        val firstPinnedB = cases.first { it.configuration == LocalModelSelfTestConfiguration.WARM_UP_B }
        assertEquals(LocalModelSelfTestRunStatus.PIN_FAILED, firstPinnedB.status)
        assertEquals(FAKE_BASELINE_CORES, firstPinnedB.allowedCoreIds)
        assertEquals(SELF_TEST_RUN_NUMBERS.size, bridge.generateCalls)
        assertEquals(List(SELF_TEST_RUN_NUMBERS.size) { FAKE_BASELINE_CORES }, bridge.releasedAffinityMasks)
        assertEquals(FAKE_BASELINE_CORES, bridge.fakeAllowedCores)
    }

    @Test
    fun failedAffinityRestoreIsReportedAsUnpinFailedAndNeverAsUnpinned() {
        val bridge = FakeBridge().apply { rejectedAffinityMasks = setOf(FAKE_BASELINE_CORES) }

        val outcome = selfTestRunner(installedStore(), bridge).run(LocalModelSelfTestCancellation())

        assertTrue(outcome is LocalModelSelfTestOutcome.PartialFailure)
        val metrics = (outcome as LocalModelSelfTestOutcome.PartialFailure).metrics
        val failedWarmUp = metrics.cases.single()
        assertEquals(LocalModelSelfTestRunStatus.UNPIN_FAILED, failedWarmUp.status)
        assertEquals(FAKE_BASELINE_CORES, failedWarmUp.affinityBeforeCoreIds)
        assertEquals(FAKE_STRONG_CORE_IDS, failedWarmUp.allowedCoreIds)
        assertEquals(FAKE_STRONG_CORE_IDS, failedWarmUp.affinityAfterCoreIds)
        assertFalse(metrics.cases.any { it.status == LocalModelSelfTestRunStatus.UNPINNED })
        assertEquals(bridge.fakeSystemInfo, metrics.systemInfo)
        assertEquals(SINGLE_CALL_COUNT, bridge.generateCalls)
    }

    @Test
    fun missingCpuFrequencyDataUsesTheTwoThreadFallbackAndKeepsEverySampleVisible() {
        val bridge = FakeBridge()
        val waitCalls = mutableListOf<Boolean>()
        val outcome = selfTestRunner(
            store = installedStore(),
            bridge = bridge,
            strongCoreIdsProvider = { null },
            waitBetweenRuns = { waitCalls += true; true }
        ).run(LocalModelSelfTestCancellation())

        assertTrue(outcome is LocalModelSelfTestOutcome.Success)
        val cases = (outcome as LocalModelSelfTestOutcome.Success).metrics.cases
        assertEquals(SELF_TEST_RUN_NUMBERS.size, cases.size)
        assertEquals(SELF_TEST_NO_FREQUENCY_STATUS_ORDER, cases.map { it.status })
        assertEquals(
            List(SHORT_PROMPT_RUN_COUNT) { FAKE_PROMPT_TOKEN_COUNT } +
                List(PREFILL_RUN_COUNT) { FAKE_PREFILL_PROMPT_TOKEN_COUNT },
            cases.map { it.promptTokenCount }
        )
        assertEquals(SELF_TEST_RUN_NUMBERS.size, bridge.generateCalls)
        assertEquals(List(SELF_TEST_RUN_NUMBERS.size) { FALLBACK_THREAD_COUNT }, cases.map { it.nThreads })
        assertEquals(SELF_TEST_RUN_NUMBERS.size - 1, waitCalls.size)
        assertTrue(cases.all { it.allowedCoreIds == FAKE_BASELINE_CORES })
    }

    @Test
    fun cancellationDuringCooldownWakesTheRunnerAndLeavesBaselineAffinityRestored() {
        val bridge = FakeBridge()
        val cooldownStarted = CountDownLatch(CALL_COUNT_INCREMENT)
        val cancellation = LocalModelSelfTestCancellation()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<LocalModelSelfTestOutcome> {
                selfTestRunner(
                    store = installedStore(),
                    bridge = bridge,
                    waitBetweenRuns = { currentCancellation ->
                        cooldownStarted.countDown()
                        !currentCancellation.awaitCancellation(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }
                ).run(cancellation)
            }
            assertTrue(cooldownStarted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            cancellation.cancel()
            assertEquals(LocalModelSelfTestOutcome.Failure, future.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(SINGLE_CALL_COUNT, bridge.generateCalls)
            assertEquals(FAKE_BASELINE_CORES, bridge.fakeAllowedCores)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun lockedAppCannotStartSelfTestOrLoadNativeModel() {
        val bridge = FakeBridge()
        val allowed = AtomicBoolean(false)
        val outcome = selfTestRunner(installedStore(), bridge, isEligible = allowed::get)
            .run(LocalModelSelfTestCancellation())

        assertEquals(LocalModelSelfTestOutcome.Failure, outcome)
        assertEquals(INITIAL_CALL_COUNT, bridge.loadCalls)
        assertEquals(INITIAL_CALL_COUNT, bridge.releaseCalls)
    }

    @Test
    fun lockDuringGenerationAbortsAndReleasesTheModelWithoutReturningText() {
        val bridge = FakeBridge()
        val generationStarted = CountDownLatch(CALL_COUNT_INCREMENT)
        val abortObserved = CountDownLatch(CALL_COUNT_INCREMENT)
        val allowed = AtomicBoolean(true)
        bridge.generateOverride = {
            generationStarted.countDown()
            check(abortObserved.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            NativeGenerationResult(
                "private simulated output",
                FAKE_PROMPT_TOKEN_COUNT,
                FAKE_COMPLETION_TOKEN_COUNT,
                "stop",
                FAKE_PREFILL_ELAPSED_NANOS,
                FAKE_FIRST_TOKEN_ELAPSED_NANOS,
                FAKE_DECODE_ELAPSED_NANOS
            )
        }
        bridge.cancelOverride = { abortObserved.countDown() }
        val cancellation = LocalModelSelfTestCancellation()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<LocalModelSelfTestOutcome> {
                selfTestRunner(installedStore(), bridge, isEligible = allowed::get).run(cancellation)
            }
            assertTrue(generationStarted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            allowed.set(false)
            cancellation.cancel()
            assertEquals(LocalModelSelfTestOutcome.Failure, future.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(SINGLE_CALL_COUNT, bridge.cancelCalls)
            assertEquals(SINGLE_CALL_COUNT, bridge.releaseCalls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun lockDuringUninterruptibleModelLoadSkipsGenerationAndUnloadsAfterLoadReturns() {
        val bridge = FakeBridge()
        val loadStarted = CountDownLatch(CALL_COUNT_INCREMENT)
        val finishLoad = CountDownLatch(CALL_COUNT_INCREMENT)
        val allowed = AtomicBoolean(true)
        bridge.loadOverride = {
            loadStarted.countDown()
            check(finishLoad.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            FAKE_NATIVE_HANDLE
        }
        val cancellation = LocalModelSelfTestCancellation()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<LocalModelSelfTestOutcome> {
                selfTestRunner(installedStore(), bridge, isEligible = allowed::get).run(cancellation)
            }
            assertTrue(loadStarted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            allowed.set(false)
            cancellation.cancel()
            finishLoad.countDown()
            assertEquals(LocalModelSelfTestOutcome.Failure, future.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(INITIAL_CALL_COUNT, bridge.cancelCalls)
            assertEquals(INITIAL_CALL_COUNT, bridge.generateCalls)
            assertEquals(SINGLE_CALL_COUNT, bridge.releaseCalls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun nativeErrorsBecomeGenericFailureAndStillUnload() {
        val bridge = FakeBridge().apply {
            generateOverride = { throw IllegalStateException("private native failure details") }
        }

        val outcome = selfTestRunner(installedStore(), bridge).run(LocalModelSelfTestCancellation())

        assertEquals(LocalModelSelfTestOutcome.Failure, outcome)
        assertFalse(outcome.toString().contains("private native failure details"))
        assertEquals(SINGLE_CALL_COUNT, bridge.releaseCalls)
    }

    private fun selfTestRunner(
        store: ModelAssetStore,
        bridge: FakeBridge,
        isEligible: () -> Boolean = { true },
        strongCoreIdsProvider: () -> List<Int>? = { FAKE_STRONG_CORE_IDS },
        waitBetweenRuns: (LocalModelSelfTestCancellation) -> Boolean = { true }
    ): LocalModelSelfTestRunner = LocalModelSelfTestRunner(
        sessionFactory = { cancellation, nThreads ->
            val measurements = AtomicReference<LlamaRuntimeMeasurements?>(null)
            val backend = LlamaCppInferenceBackend(
                modelAssetStore = store,
                contextTokens = ContextWindowBudgetPolicy().maxContextTokens,
                bridge = bridge,
                nThreads = nThreads,
                nThreadsBatch = nThreads
            )
            val engine = LocalInferenceEngine(
                delegateBackendExecutor = InferenceDelegateExecutor { delegate, inferenceRequest ->
                    if (delegate != HardwareDelegate.CPU) {
                        Result.failure(IllegalStateException("Self-test CPU-only fake was not selected"))
                    } else {
                        backend.generateMeasured(inferenceRequest) { !cancellation.isCancelled() }.map { generated ->
                            measurements.set(generated.measurements)
                            InferenceDelegateOutput(generated.response)
                        }
                    }
                },
                modeDelegateOverride = { HardwareDelegate.CPU },
                supportsStreaming = false,
                supportsSeed = false,
                supportsCancellation = false
            )
            LocalModelSelfTestSession(engine, backend, measurements).also { session ->
                cancellation.attachAbortHandler(session::requestAbort)
            }
        },
        isEligible = isEligible,
        strongCoreIdsProvider = strongCoreIdsProvider,
        waitBetweenRuns = waitBetweenRuns
    )

    private fun installedStore(): ModelAssetStore {
        val payload = "native fixture".toByteArray()
        val store = ModelAssetStore(
            temporaryFolder.newFolder(),
            ChecksumUtil.sha256(payload),
            "fixture.gguf"
        )
        assertTrue(store.importModel(ByteArrayInputStream(payload)) is ModelAssetStore.ImportResult.Installed)
        return store
    }

    private fun request(outputSchema: String? = null): InferenceRequest {
        return InferenceRequest(
            prompt = "user prompt",
            context = "system context",
            params = GenerateParams(
                max_tokens = FAKE_MAX_TOKENS,
                temperature = FAKE_TEMPERATURE,
                top_p = FAKE_TOP_P
            ),
            outputSchema = outputSchema
        )
    }

    private companion object {
        // PROVISIONAL - pending GEN-PERF-1
        const val INITIAL_CALL_COUNT = 0
        // PROVISIONAL - pending GEN-PERF-1
        const val CALL_COUNT_INCREMENT = 1
        // PROVISIONAL - pending GEN-PERF-1
        const val SINGLE_CALL_COUNT = 1
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_NATIVE_HANDLE = 41L
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_PROMPT_TOKEN_COUNT = 3
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_PREFILL_PROMPT_TOKEN_COUNT = 530
        // PROVISIONAL - pending GEN-PERF-1
        const val PREFILL_PROMPT_MIN_CHARS = 2_000
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_COMPLETION_TOKEN_COUNT = 2
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_PREFILL_ELAPSED_NANOS = 20_000_000L
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_FIRST_TOKEN_ELAPSED_NANOS = 40_000_000L
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_DECODE_ELAPSED_NANOS = 30_000_000L
        // PROVISIONAL - pending GEN-PERF-1: arbitrary fixture CPU IDs, not device-specific assignments.
        val FAKE_BASELINE_CORES = listOf(101, 102, 103, 104)
        // PROVISIONAL - pending GEN-PERF-1: arbitrary fixture IDs standing in for discovered top-frequency cores.
        val FAKE_STRONG_CORE_IDS = listOf(201, 202)
        // PROVISIONAL - pending GEN-PERF-1
        val SELF_TEST_CONFIGURATION_ORDER = listOf(
            LocalModelSelfTestConfiguration.WARM_UP_B,
            LocalModelSelfTestConfiguration.A,
            LocalModelSelfTestConfiguration.B,
            LocalModelSelfTestConfiguration.D,
            LocalModelSelfTestConfiguration.B,
            LocalModelSelfTestConfiguration.D,
            LocalModelSelfTestConfiguration.A,
            LocalModelSelfTestConfiguration.D,
            LocalModelSelfTestConfiguration.A,
            LocalModelSelfTestConfiguration.B,
            LocalModelSelfTestConfiguration.PREFILL_B,
            LocalModelSelfTestConfiguration.PREFILL_B
        )
        // PROVISIONAL - pending GEN-PERF-1
        const val SWEEP_RUN_COUNT = 9
        // PROVISIONAL - pending GEN-PERF-1
        val SELF_TEST_RUN_NUMBERS = (1..12).toList()
        // PROVISIONAL - pending GEN-PERF-1
        val SELF_TEST_THREAD_COUNTS = listOf(2, 1, 2, 2, 2, 2, 1, 2, 1, 2, 2, 2)
        // PROVISIONAL - pending GEN-PERF-1
        val SELF_TEST_ALLOWED_MASKS = listOf(
            FAKE_STRONG_CORE_IDS,
            listOf(FAKE_STRONG_CORE_IDS.first()),
            FAKE_STRONG_CORE_IDS,
            FAKE_BASELINE_CORES,
            FAKE_STRONG_CORE_IDS,
            FAKE_BASELINE_CORES,
            listOf(FAKE_STRONG_CORE_IDS.first()),
            FAKE_BASELINE_CORES,
            listOf(FAKE_STRONG_CORE_IDS.first()),
            FAKE_STRONG_CORE_IDS,
            FAKE_STRONG_CORE_IDS,
            FAKE_STRONG_CORE_IDS
        )
        // PROVISIONAL - pending GEN-PERF-1
        val SELF_TEST_STATUS_ORDER = listOf(
            LocalModelSelfTestRunStatus.PINNED,
            LocalModelSelfTestRunStatus.PINNED,
            LocalModelSelfTestRunStatus.PINNED,
            LocalModelSelfTestRunStatus.UNPINNED,
            LocalModelSelfTestRunStatus.PINNED,
            LocalModelSelfTestRunStatus.UNPINNED,
            LocalModelSelfTestRunStatus.PINNED,
            LocalModelSelfTestRunStatus.UNPINNED,
            LocalModelSelfTestRunStatus.PINNED,
            LocalModelSelfTestRunStatus.PINNED,
            LocalModelSelfTestRunStatus.PINNED,
            LocalModelSelfTestRunStatus.PINNED
        )
        // PROVISIONAL - pending GEN-PERF-1
        val SELF_TEST_FAILED_PIN_STATUS_ORDER = listOf(
            LocalModelSelfTestRunStatus.PIN_FAILED,
            LocalModelSelfTestRunStatus.PIN_FAILED,
            LocalModelSelfTestRunStatus.PIN_FAILED,
            LocalModelSelfTestRunStatus.UNPINNED,
            LocalModelSelfTestRunStatus.PIN_FAILED,
            LocalModelSelfTestRunStatus.UNPINNED,
            LocalModelSelfTestRunStatus.PIN_FAILED,
            LocalModelSelfTestRunStatus.UNPINNED,
            LocalModelSelfTestRunStatus.PIN_FAILED,
            LocalModelSelfTestRunStatus.PIN_FAILED,
            LocalModelSelfTestRunStatus.PIN_FAILED,
            LocalModelSelfTestRunStatus.PIN_FAILED
        )
        // PROVISIONAL - pending GEN-PERF-1
        val SELF_TEST_NO_FREQUENCY_STATUS_ORDER = listOf(
            LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE,
            LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE,
            LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE,
            LocalModelSelfTestRunStatus.UNPINNED,
            LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE,
            LocalModelSelfTestRunStatus.UNPINNED,
            LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE,
            LocalModelSelfTestRunStatus.UNPINNED,
            LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE,
            LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE,
            LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE,
            LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE
        )
        // PROVISIONAL - pending GEN-PERF-1
        const val SHORT_PROMPT_RUN_COUNT = 10
        // PROVISIONAL - pending GEN-PERF-1
        const val PREFILL_RUN_COUNT = 2
        // PROVISIONAL - pending GEN-PERF-1
        const val FALLBACK_THREAD_COUNT = 2
        // PROVISIONAL - pending GEN-PERF-1
        const val LOCAL_SELF_TEST_MAX_OUTPUT_TOKENS = 8
        // PROVISIONAL - pending GEN-PERF-1
        const val WAIT_TIMEOUT_SECONDS = 5L
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_MAX_TOKENS = 8
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_TEMPERATURE = 0.0f
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_TOP_P = 0.9f
    }
}
