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

class LlamaCppInferenceBackendTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class FakeBridge : LlamaNativeBridge {
        // PROVISIONAL - pending GEN-PERF-1
        var loadCalls = INITIAL_CALL_COUNT
        // PROVISIONAL - pending GEN-PERF-1
        var releaseCalls = INITIAL_CALL_COUNT
        var recordedContext: String? = null
        var recordedPrompt: String? = null
        var recordedGrammar: String? = null
        var recordedMaxTokens: Int? = null

        override fun loadModel(modelPath: String, contextTokens: Int): Long {
            loadCalls += CALL_COUNT_INCREMENT
            return FAKE_NATIVE_HANDLE
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
            recordedContext = context
            recordedPrompt = prompt
            recordedGrammar = grammar
            recordedMaxTokens = maxTokens
            return NativeGenerationResult(
                "native output",
                promptTokens = FAKE_PROMPT_TOKEN_COUNT,
                completionTokens = FAKE_COMPLETION_TOKEN_COUNT,
                finishReason = "stop"
            )
        }

        override fun releaseModel(handle: Long) {
            releaseCalls += CALL_COUNT_INCREMENT
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
        const val FAKE_MAX_TOKENS = 8
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_TEMPERATURE = 0.0f
        // PROVISIONAL - pending GEN-PERF-1
        const val FAKE_TOP_P = 0.9f
    }
}
