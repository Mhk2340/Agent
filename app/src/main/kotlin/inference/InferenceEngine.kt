package inference

import contracts.GenerateParams
import contracts.InferenceError
import contracts.InferenceProvider
import contracts.InferenceRequest
import contracts.InferenceResponse
import contracts.ProviderCapabilities
import contracts.ProviderType
import contracts.TokenUsage

/**
 * GEN-271 & GEN-278 — Model Abstraction Layer & Inference Engine Implementation
 * Abstraction for Single Local Model (Qwen3-4B Q4_K_M).
 * Hides hardware delegate selection (GEN-EDGE-1) behind standard Contract #1.
 */
class LocalInferenceEngine(
    private val budgetPolicy: ContextWindowBudgetPolicy = ContextWindowBudgetPolicy(),
    private val delegateSelector: HardwareDelegateSelector = HardwareDelegateSelector(),
    private val deviceFingerprint: String? = null,
    private val deviceProfile: DevicePerformanceProfile? = null,
    private val backendExecutor: ((InferenceRequest) -> Result<InferenceResponse>)? = null
) : InferenceProvider {

    val selectedDelegate: HardwareDelegate = delegateSelector.selectDelegate(deviceFingerprint, deviceProfile)

    override fun capabilities(): ProviderCapabilities {
        return ProviderCapabilities(
            provider_type = ProviderType.LOCAL,
            // PROVISIONAL - pending GEN-PERF-1: max 4096 tokens
            max_context_tokens = budgetPolicy.maxContextTokens,
            max_output_tokens = 512,
            supports_streaming = true,
            supports_structured_output = true,
            supports_seed = true,
            supports_cancellation = true
        )
    }

    override fun generate(request: InferenceRequest): Result<InferenceResponse> {
        // Step 1: Context Admission Check (GEN-285)
        val estimatedContextTokens = request.context.length // approximate 1 token ~= 1 char in test logic
        val requestedMaxTokens = request.params.max_tokens

        if (!budgetPolicy.checkAdmission(estimatedContextTokens, requestedMaxTokens)) {
            val usable = budgetPolicy.calculateUsableContextTokens(requestedMaxTokens)
            return Result.failure(
                AgentModelException(
                    InferenceError.ResourceExhausted(
                        "Context admission rejected: context ($estimatedContextTokens tokens) exceeds usable context ($usable tokens)"
                    )
                )
            )
        }

        // Step 2: Execution via Backend (Mock or delegate execution)
        if (backendExecutor != null) {
            return backendExecutor.invoke(request)
        }

        // Default local response stub (deterministic execution)
        return Result.success(
            InferenceResponse(
                text = "{\"intent\": \"default_response\", \"confidence\": 0.9}",
                usage = TokenUsage(prompt_tokens = estimatedContextTokens, completion_tokens = 20),
                finish_reason = "stop"
            )
        )
    }
}

class AgentModelException(val error: InferenceError) : Exception(error.toString())
