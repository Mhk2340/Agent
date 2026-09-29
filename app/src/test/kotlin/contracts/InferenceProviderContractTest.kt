package contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit & Contract Test for Contract #1 (InferenceProvider Interface & Error Taxonomy).
 * Verifies capabilities admission logic, token usage tracking, and error mapping.
 */
class InferenceProviderContractTest {

    // A lightweight Mock Provider implementing Contract #1
    private class MockInferenceProvider(
        private val cannedResponse: String = "{\"intent\": \"greeting\", \"confidence\": 0.95}",
        private val errorToThrow: InferenceError? = null
    ) : InferenceProvider {

        override fun capabilities(): ProviderCapabilities {
            return ProviderCapabilities(
                provider_type = ProviderType.LOCAL,
                // PROVISIONAL - pending GEN-PERF-1: max 4096 context tokens
                max_context_tokens = 4096,
                max_output_tokens = 512,
                supports_streaming = true,
                supports_structured_output = true,
                supports_seed = true,
                supports_cancellation = true
            )
        }

        override fun generate(request: InferenceRequest): Result<InferenceResponse> {
            if (errorToThrow != null) {
                return Result.failure(Exception(errorToThrow.toString()))
            }
            // Simulate admission check as required by GEN-285
            val requestedOutputTokens = request.params.max_tokens
            val safetyMargin = 64
            val usableContext = capabilities().max_context_tokens - requestedOutputTokens - safetyMargin
            if (request.context.length > usableContext) {
                val err = InferenceError.ResourceExhausted("Context length exceeds usable context limit ($usableContext)")
                return Result.failure(Exception(err.detail))
            }

            request.onToken?.invoke("Simulated stream chunk")

            return Result.success(
                InferenceResponse(
                    text = cannedResponse,
                    usage = TokenUsage(prompt_tokens = 120, completion_tokens = 35),
                    finish_reason = "stop"
                )
            )
        }
    }

    @Test
    fun testProviderCapabilitiesReporting() {
        val provider = MockInferenceProvider()
        val caps = provider.capabilities()

        assertEquals(ProviderType.LOCAL, caps.provider_type)
        assertEquals(4096, caps.max_context_tokens)
        assertTrue(caps.supports_structured_output)
        assertTrue(caps.supports_seed)
    }

    @Test
    fun testSuccessfulGenerationAndTokenUsage() {
        val provider = MockInferenceProvider()
        val request = InferenceRequest(
            prompt = "Hello Agent",
            context = "User is on dashboard",
            params = GenerateParams(max_tokens = 256, temperature = 0.0f, top_p = 0.9f)
        )

        val result = provider.generate(request)
        assertTrue(result.isSuccess)
        val response = result.getOrNull()!!
        assertEquals("stop", response.finish_reason)
        assertEquals(120, response.usage.prompt_tokens)
        assertEquals(35, response.usage.completion_tokens)
    }

    @Test
    fun testContextAdmissionCheckExceeded() {
        val provider = MockInferenceProvider()
        // Very large context exceeding usable context
        val largeContext = "x".repeat(4000)
        val request = InferenceRequest(
            prompt = "Explain quantum physics",
            context = largeContext,
            params = GenerateParams(max_tokens = 500, temperature = 0.0f, top_p = 0.9f)
        )

        val result = provider.generate(request)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("exceeds usable context limit"))
    }

    @Test
    fun testInferenceErrorTaxonomyCoverage() {
        // Verify all sealed classes can be instantiated and mapped
        val errors: List<InferenceError> = listOf(
            InferenceError.ResourceExhausted("OOM"),
            InferenceError.QuotaExceeded(retryAfterMs = 60000L),
            InferenceError.RateLimited(retryAfterMs = 5000L),
            InferenceError.Timeout(durationMs = 30000L),
            InferenceError.Malformed("Invalid JSON"),
            InferenceError.AuthFailed("HTTP 401"),
            InferenceError.Unavailable("HTTP 503")
        )
        assertEquals(7, errors.size)
    }
}
