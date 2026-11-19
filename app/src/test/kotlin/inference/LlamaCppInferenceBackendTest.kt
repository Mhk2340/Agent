package inference

import contracts.GenerateParams
import contracts.InferenceError
import contracts.InferenceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        override fun countPromptTokens(handle: Long, context: String, prompt: String): Int =
            fakePromptTokenCount(context, prompt)

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
                promptTokens = fakePromptTokenCount(context, prompt),
                completionTokens = FAKE_COMPLETION_TOKEN_COUNT,
                finishReason = "stop",
                prefillElapsedNanos = FAKE_PREFILL_ELAPSED_NANOS,
                firstTokenElapsedNanos = FAKE_FIRST_TOKEN_ELAPSED_NANOS,
                decodeElapsedNanos = FAKE_DECODE_ELAPSED_NANOS
            )
        }

        private fun fakePromptTokenCount(context: String, prompt: String): Int =
            FAKE_CHAT_TEMPLATE_TOKEN_COUNT +
                context.trim().split(Regex("\\s+")).size +
                prompt.trim().split(Regex("\\s+")).size

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
    fun defaultQuickSelfTestRunsOnePinnedBPassWithExact41TokenPrompt() {
        val bridge = FakeBridge()
        val waits = mutableListOf<Long>()
        val progress = mutableListOf<LocalModelSelfTestProgress>()
        val outcome = selfTestRunner(
            store = installedStore(),
            bridge = bridge,
            waitBetweenRuns = { _, restMs -> waits += restMs; true }
        ).run(
            cancellation = LocalModelSelfTestCancellation(),
            onProgress = progress::add
        )

        assertTrue(outcome is LocalModelSelfTestOutcome.Success)
        val metrics = (outcome as LocalModelSelfTestOutcome.Success).metrics
        val case = metrics.cases.single()
        assertEquals(LocalModelSelfTestMode.QUICK, metrics.mode)
        assertEquals(LocalModelSelfTestConfiguration.B, case.configuration)
        assertEquals(1, case.runNumber)
        assertEquals(2, case.nThreads)
        assertEquals(FAKE_BASELINE_CORES, case.affinityBeforeCoreIds)
        assertEquals(FAKE_STRONG_CORE_IDS, case.allowedCoreIds)
        assertEquals(FAKE_BASELINE_CORES, case.affinityAfterCoreIds)
        assertEquals(LocalModelSelfTestMode.QUICK.targetPromptTokens, case.promptTokenCount)
        assertTrue((case.firstTokenTimeMs ?: 0L) > 0L)
        assertTrue((case.prefillTokensPerSecond ?: 0.0) > 0.0)
        assertTrue((case.decodeTokensPerSecond ?: 0.0) > 0.0)
        assertEquals(LocalModelSelfTestRunStatus.PINNED, case.status)
        assertEquals(listOf(QUICK_MAX_OUTPUT_TOKENS), bridge.recordedMaxTokenBudgets)
        assertEquals(1, bridge.generateCalls)
        assertEquals(1, bridge.loadCalls)
        assertEquals(1, bridge.releaseCalls)
        assertEquals(listOf(2), bridge.recordedThreadCounts)
        assertEquals(listOf(2), bridge.recordedBatchThreadCounts)
        assertTrue(bridge.recordedPrompts.single().startsWith(LocalModelSelfTestMode.QUICK_BASE_PROMPT))
        assertEquals(0, waits.size)
        assertEquals(
            listOf(LocalModelSelfTestProgress(LocalModelSelfTestMode.QUICK, 1, 1, LocalModelSelfTestConfiguration.B)),
            progress
        )
        assertEquals(bridge.fakeSystemInfo, metrics.systemInfo)
        assertFalse(outcome.toString().contains("native output"))
    }

    @Test
    fun longPrefillIsASeparateSingleBRunWithExactly606PromptTokensAndFourOutputTokens() {
        val bridge = FakeBridge()
        val waits = mutableListOf<Long>()
        val outcome = selfTestRunner(
            store = installedStore(),
            bridge = bridge,
            waitBetweenRuns = { _, restMs -> waits += restMs; true }
        ).run(LocalModelSelfTestCancellation(), LocalModelSelfTestMode.PREFILL_606)

        assertTrue(outcome is LocalModelSelfTestOutcome.Success)
        val metrics = (outcome as LocalModelSelfTestOutcome.Success).metrics
        val case = metrics.cases.single()
        assertEquals(LocalModelSelfTestMode.PREFILL_606, metrics.mode)
        assertEquals(LocalModelSelfTestConfiguration.B, case.configuration)
        assertEquals(1, bridge.generateCalls)
        assertEquals(listOf(PREFILL_MAX_OUTPUT_TOKENS), bridge.recordedMaxTokenBudgets)
        assertEquals(LocalModelSelfTestMode.PREFILL_606.targetPromptTokens, case.promptTokenCount)
        assertTrue(bridge.recordedPrompts.single().contains("no user data"))
        assertTrue(bridge.recordedPrompts.single().length > PREFILL_PROMPT_MIN_CHARS)
        assertTrue((case.prefillTokensPerSecond ?: 0.0) > 0.0)
        assertEquals(0, waits.size)
    }

    @Test
    fun debugSweepIsOneABDSweepWithAtMostFiveSecondRestAndExplainsBaselineD() {
        val bridge = FakeBridge().apply {
            // The saved worker mask equals the discovered strong-core pair on this fixture.
            fakeAllowedCores = FAKE_STRONG_CORE_IDS
        }
        val waits = mutableListOf<Long>()
        val progress = mutableListOf<LocalModelSelfTestProgress>()
        val outcome = selfTestRunner(
            store = installedStore(),
            bridge = bridge,
            waitBetweenRuns = { _, restMs -> waits += restMs; true }
        ).run(
            cancellation = LocalModelSelfTestCancellation(),
            mode = LocalModelSelfTestMode.SWEEP_ABD,
            onProgress = progress::add
        )

        assertTrue(outcome is LocalModelSelfTestOutcome.Success)
        val metrics = (outcome as LocalModelSelfTestOutcome.Success).metrics
        assertEquals(
            listOf(LocalModelSelfTestConfiguration.A, LocalModelSelfTestConfiguration.B, LocalModelSelfTestConfiguration.D),
            metrics.cases.map { it.configuration }
        )
        assertEquals(listOf(1, 2, 2), metrics.cases.map { it.nThreads })
        assertEquals(List(3) { QUICK_PROMPT_TOKEN_TARGET }, metrics.cases.map { it.promptTokenCount })
        assertEquals(List(3) { SWEEP_MAX_OUTPUT_TOKENS }, bridge.recordedMaxTokenBudgets)
        assertEquals(3, bridge.generateCalls)
        assertEquals(listOf(5_000L, 5_000L), waits)
        assertTrue(waits.all { it <= 5_000L })
        assertEquals((1..3).toList(), progress.map { it.runNumber })
        assertTrue(progress.all { it.totalRuns == 3 })

        val bCase = metrics.cases[1]
        val dCase = metrics.cases[2]
        assertEquals(LocalModelSelfTestRunStatus.PINNED, bCase.status)
        assertEquals(LocalModelSelfTestRunStatus.BASELINE_UNPINNED, dCase.status)
        assertEquals(FAKE_STRONG_CORE_IDS, bCase.allowedCoreIds)
        assertEquals(bCase.allowedCoreIds, dCase.allowedCoreIds)
        assertEquals(FAKE_STRONG_CORE_IDS, dCase.affinityBeforeCoreIds)
        assertEquals(FAKE_STRONG_CORE_IDS, dCase.affinityAfterCoreIds)
    }

    @Test
    fun expectedPlanBudgetsAreCalculatedFromRunCountsOutputCapsAndRestConstants() {
        assertEquals(43_637L, LocalModelSelfTestTiming.expectedDurationMs(LocalModelSelfTestMode.QUICK))
        assertEquals(32_728L, LocalModelSelfTestTiming.expectedDurationMs(LocalModelSelfTestMode.PREFILL_606))
        assertEquals(113_184L, LocalModelSelfTestTiming.expectedDurationMs(LocalModelSelfTestMode.SWEEP_ABD))
        assertTrue(LocalModelSelfTestMode.values().all {
            LocalModelSelfTestTiming.expectedDurationMs(it) < 120_000L
        })
        assertEquals(180_000L, LocalModelSelfTestTiming.TIMEOUT_MS)
    }

    @Test
    fun failedPinsAreReportedAndTheDebugSweepContinuesToBaselineD() {
        val bridge = FakeBridge().apply {
            rejectedAffinityMasks = setOf(
                listOf(FAKE_STRONG_CORE_IDS.first()),
                FAKE_STRONG_CORE_IDS
            )
        }

        val outcome = selfTestRunner(installedStore(), bridge).run(
            LocalModelSelfTestCancellation(),
            LocalModelSelfTestMode.SWEEP_ABD
        )

        assertTrue(outcome is LocalModelSelfTestOutcome.PartialFailure)
        val cases = (outcome as LocalModelSelfTestOutcome.PartialFailure).metrics.cases
        assertEquals(3, cases.size)
        assertEquals(
            listOf(
                LocalModelSelfTestRunStatus.PIN_FAILED,
                LocalModelSelfTestRunStatus.PIN_FAILED,
                LocalModelSelfTestRunStatus.BASELINE_UNPINNED
            ),
            cases.map { it.status }
        )
        assertEquals(listOf(1, 2, 2), cases.map { it.nThreads })
        assertTrue(cases.all { it.affinityBeforeCoreIds == FAKE_BASELINE_CORES })
        assertTrue(cases.all { it.allowedCoreIds == FAKE_BASELINE_CORES })
        assertTrue(cases.all { it.affinityAfterCoreIds == FAKE_BASELINE_CORES })
        assertEquals(1, bridge.generateCalls)
        assertEquals(FAKE_BASELINE_CORES, bridge.fakeAllowedCores)
    }

    @Test
    fun effectiveAffinityIsReadBackAndQuickBDoesNotGenerateWhenPinFails() {
        val bridge = FakeBridge().apply {
            effectiveAffinityOverrides = mapOf(FAKE_STRONG_CORE_IDS to listOf(FAKE_STRONG_CORE_IDS.first()))
        }

        val outcome = selfTestRunner(installedStore(), bridge).run(LocalModelSelfTestCancellation())

        assertTrue(outcome is LocalModelSelfTestOutcome.PartialFailure)
        val cases = (outcome as LocalModelSelfTestOutcome.PartialFailure).metrics.cases
        val failedB = cases.single()
        assertEquals(LocalModelSelfTestConfiguration.B, failedB.configuration)
        assertEquals(LocalModelSelfTestRunStatus.PIN_FAILED, failedB.status)
        assertEquals(FAKE_BASELINE_CORES, failedB.allowedCoreIds)
        assertEquals(0, bridge.generateCalls)
        assertEquals(FAKE_BASELINE_CORES, bridge.fakeAllowedCores)
    }

    @Test
    fun failedAffinityRestoreIsReportedAsUnpinFailedAndNeverAsUnpinned() {
        val bridge = FakeBridge().apply { rejectedAffinityMasks = setOf(FAKE_BASELINE_CORES) }

        val outcome = selfTestRunner(installedStore(), bridge).run(LocalModelSelfTestCancellation())

        assertTrue(outcome is LocalModelSelfTestOutcome.PartialFailure)
        val metrics = (outcome as LocalModelSelfTestOutcome.PartialFailure).metrics
        val failedB = metrics.cases.single()
        assertEquals(LocalModelSelfTestRunStatus.UNPIN_FAILED, failedB.status)
        assertEquals(FAKE_BASELINE_CORES, failedB.affinityBeforeCoreIds)
        assertEquals(FAKE_STRONG_CORE_IDS, failedB.allowedCoreIds)
        assertEquals(FAKE_STRONG_CORE_IDS, failedB.affinityAfterCoreIds)
        assertFalse(metrics.cases.any { it.status == LocalModelSelfTestRunStatus.BASELINE_UNPINNED })
        assertEquals(bridge.fakeSystemInfo, metrics.systemInfo)
        assertEquals(SINGLE_CALL_COUNT, bridge.generateCalls)
    }

    @Test
    fun quickBRequiresDiscoverableStrongCoresAndFailsClosedWithoutUnpinnedGeneration() {
        val bridge = FakeBridge()
        val waitCalls = mutableListOf<Long>()
        val outcome = selfTestRunner(
            store = installedStore(),
            bridge = bridge,
            strongCoreIdsProvider = { null },
            waitBetweenRuns = { _, restMs -> waitCalls += restMs; true }
        ).run(LocalModelSelfTestCancellation())

        assertTrue(outcome is LocalModelSelfTestOutcome.PartialFailure)
        val case = (outcome as LocalModelSelfTestOutcome.PartialFailure).metrics.cases.single()
        assertEquals(LocalModelSelfTestConfiguration.B, case.configuration)
        assertEquals(LocalModelSelfTestRunStatus.CPU_FREQUENCY_UNAVAILABLE, case.status)
        assertEquals(2, case.nThreads)
        assertNull(case.promptTokenCount)
        assertEquals(0, bridge.generateCalls)
        assertEquals(0, waitCalls.size)
        assertEquals(FAKE_BASELINE_CORES, case.affinityAfterCoreIds)
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
                    waitBetweenRuns = { currentCancellation, restMs ->
                        assertEquals(LocalModelSelfTestMode.SWEEP_ABD.interRunRestMs, restMs)
                        cooldownStarted.countDown()
                        !currentCancellation.awaitCancellation(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }
                ).run(cancellation, LocalModelSelfTestMode.SWEEP_ABD)
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
                QUICK_PROMPT_TOKEN_TARGET,
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

        assertTrue(outcome is LocalModelSelfTestOutcome.PartialFailure)
        assertFalse(outcome.toString().contains("private native failure details"))
        assertEquals(SINGLE_CALL_COUNT, bridge.releaseCalls)
    }

    private fun selfTestRunner(
        store: ModelAssetStore,
        bridge: FakeBridge,
        isEligible: () -> Boolean = { true },
        strongCoreIdsProvider: () -> List<Int>? = { FAKE_STRONG_CORE_IDS },
        waitBetweenRuns: (LocalModelSelfTestCancellation, Long) -> Boolean = { _, _ -> true }
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
        const val INITIAL_CALL_COUNT = 0
        const val CALL_COUNT_INCREMENT = 1
        const val SINGLE_CALL_COUNT = 1
        const val FAKE_NATIVE_HANDLE = 41L
        const val FAKE_CHAT_TEMPLATE_TOKEN_COUNT = 6
        // General backend request fixture: six template tokens + two context + two prompt words.
        const val FAKE_PROMPT_TOKEN_COUNT = 10
        const val FAKE_COMPLETION_TOKEN_COUNT = 2
        const val FAKE_PREFILL_ELAPSED_NANOS = 20_000_000L
        const val FAKE_FIRST_TOKEN_ELAPSED_NANOS = 40_000_000L
        const val FAKE_DECODE_ELAPSED_NANOS = 30_000_000L
        const val PREFILL_PROMPT_MIN_CHARS = 2_000
        const val QUICK_PROMPT_TOKEN_TARGET = LocalModelSelfTestMode.QUICK_PROMPT_TOKEN_TARGET
        const val PREFILL_PROMPT_TOKEN_TARGET = LocalModelSelfTestMode.PREFILL_PROMPT_TOKEN_TARGET
        const val QUICK_MAX_OUTPUT_TOKENS = LocalModelSelfTestMode.QUICK_MAX_OUTPUT_TOKENS
        const val PREFILL_MAX_OUTPUT_TOKENS = LocalModelSelfTestMode.PREFILL_MAX_OUTPUT_TOKENS
        const val SWEEP_MAX_OUTPUT_TOKENS = LocalModelSelfTestMode.SWEEP_MAX_OUTPUT_TOKENS
        const val WAIT_TIMEOUT_SECONDS = 5L
        const val FAKE_MAX_TOKENS = 8
        const val FAKE_TEMPERATURE = 0.0f
        const val FAKE_TOP_P = 0.9f
        // Arbitrary fixture IDs only; these are not assignments for a target device.
        val FAKE_BASELINE_CORES = listOf(101, 102, 103, 104)
        val FAKE_STRONG_CORE_IDS = listOf(201, 202)
    }
}
