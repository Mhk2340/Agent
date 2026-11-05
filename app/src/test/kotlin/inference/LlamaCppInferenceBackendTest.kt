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

        override fun loadModel(
            modelPath: String,
            contextTokens: Int,
            nThreads: Int,
            nThreadsBatch: Int
        ): Long {
            loadCalls += CALL_COUNT_INCREMENT
            recordedThreadCounts += nThreads
            recordedBatchThreadCounts += nThreadsBatch
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
            recordedContext = context
            recordedPrompt = prompt
            recordedGrammar = grammar
            recordedMaxTokens = maxTokens
            recordedMaxTokenBudgets += maxTokens
            return generateOverride?.invoke() ?: NativeGenerationResult(
                "native output",
                promptTokens = FAKE_PROMPT_TOKEN_COUNT,
                completionTokens = FAKE_COMPLETION_TOKEN_COUNT,
                finishReason = "stop",
                firstTokenElapsedNanos = FAKE_FIRST_TOKEN_ELAPSED_NANOS,
                generationElapsedNanos = FAKE_GENERATION_ELAPSED_NANOS
            )
        }

        override fun releaseModel(handle: Long) {
            releaseCalls += CALL_COUNT_INCREMENT
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
    fun selfTestSweepsThreadCountsAndRetainsOnlyApprovedMeasurements() {
        val bridge = FakeBridge()
        val outcome = selfTestRunner(installedStore(), bridge).run(LocalModelSelfTestCancellation())

        assertTrue(outcome is LocalModelSelfTestOutcome.Success)
        assertEquals(LOCAL_SELF_TEST_MAX_OUTPUT_TOKENS, bridge.recordedMaxTokens)
        assertEquals(listOf(LOCAL_SELF_TEST_MAX_OUTPUT_TOKENS, LOCAL_SELF_TEST_MAX_OUTPUT_TOKENS, LOCAL_SELF_TEST_MAX_OUTPUT_TOKENS), bridge.recordedMaxTokenBudgets)
        assertEquals("Say hello in a few words.", bridge.recordedPrompt)
        assertEquals(
            "Local runtime self-test only. Reply with a short harmless greeting. Do not use tools or take actions.",
            bridge.recordedContext
        )
        assertEquals(SELF_TEST_THREAD_COUNTS, bridge.recordedThreadCounts)
        assertEquals(SELF_TEST_THREAD_COUNTS, bridge.recordedBatchThreadCounts)
        assertEquals(SELF_TEST_THREAD_COUNTS.size, bridge.loadCalls)
        assertEquals(SELF_TEST_THREAD_COUNTS.size, bridge.releaseCalls)
        assertEquals(SINGLE_CALL_COUNT, bridge.systemInfoCalls)
        assertFalse(outcome.toString().contains("native output"))

        val metrics = (outcome as LocalModelSelfTestOutcome.Success).metrics
        assertEquals(SELF_TEST_THREAD_COUNTS, metrics.cases.map { it.nThreads })
        assertEquals(
            listOf(FAKE_PROMPT_TOKEN_COUNT, FAKE_PROMPT_TOKEN_COUNT, FAKE_PROMPT_TOKEN_COUNT),
            metrics.cases.map { it.promptTokenCount }
        )
        // PROVISIONAL - pending GEN-PERF-1
        assertTrue(metrics.cases.all { it.firstTokenTimeMs > 0L })
        // PROVISIONAL - pending GEN-PERF-1
        assertTrue(metrics.cases.all { it.tokensPerSecond > 0.0 })
        assertEquals(bridge.fakeSystemInfo, metrics.systemInfo)
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
                FAKE_FIRST_TOKEN_ELAPSED_NANOS,
                FAKE_GENERATION_ELAPSED_NANOS
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
        isEligible: () -> Boolean = { true }
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
        isEligible = isEligible
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
        const val FAKE_COMPLETION_TOKEN_COUNT = 2
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_FIRST_TOKEN_ELAPSED_NANOS = 10_000_000L
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_GENERATION_ELAPSED_NANOS = 40_000_000L
        // PROVISIONAL - pending GEN-PERF-1
        val SELF_TEST_THREAD_COUNTS = listOf(2, 4, 6)
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
