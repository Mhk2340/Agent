package reasoning

import contracts.GenerateParams
import contracts.InferenceProvider
import contracts.InferenceRequest
import contracts.StructuredCognitiveCall
import runtime.AgentLogger

/**
 * GEN-NEW-C — Conditional Critic Call
 *
 * MANDATORY ARCHITECTURAL REQUIREMENTS:
 * 1. Strictly Conditional: Runs ONLY when:
 *    - `confidence < threshold` (PROVISIONAL - default 0.6)
 *    - OR tool execution result contains an error/failure.
 *    If confidence is high and tool executed successfully, Critic is strictly SKIPPED.
 * 2. Unified Reflection/Critic/Verifier: Consolidates GEN-248, 249, 250 into a single conditional call.
 * 3. Turn Budget Governed: Governed by GEN-NEW-D (max 3 calls/turn).
 */
class ConditionalCriticCall(
    private val inferenceProvider: InferenceProvider,
    private val governor: TurnBudgetGovernor,
    // PROVISIONAL - pending GEN-PERF-1: confidence threshold 0.6
    val confidenceThreshold: Float = 0.6f
) {
    data class CriticEvaluation(
        val triggered: Boolean,
        val triggerReason: String?,
        val criticFeedback: String?,
        val shouldRevisePlan: Boolean,
        val revisedPlan: List<String>? = null
    )

    fun shouldTriggerCritic(
        cognitiveCall: StructuredCognitiveCall,
        toolFailed: Boolean,
        toolError: String?
    ): Pair<Boolean, String?> {
        return when {
            toolFailed -> Pair(true, "Tool execution failed: $toolError")
            cognitiveCall.confidence < confidenceThreshold -> Pair(
                true,
                "Confidence (${cognitiveCall.confidence}) is below threshold ($confidenceThreshold)"
            )
            else -> Pair(false, null)
        }
    }

    fun evaluate(
        cognitiveCall: StructuredCognitiveCall,
        toolFailed: Boolean = false,
        toolError: String? = null,
        draftResponse: String? = null
    ): CriticEvaluation {
        val (shouldRun, reason) = shouldTriggerCritic(cognitiveCall, toolFailed, toolError)

        if (!shouldRun) {
            AgentLogger.i("ConditionalCriticCall", "Critic evaluation skipped: Confidence high (${cognitiveCall.confidence} >= $confidenceThreshold) and tool succeeded")
            return CriticEvaluation(
                triggered = false,
                triggerReason = null,
                criticFeedback = null,
                shouldRevisePlan = false
            )
        }

        // Check & record against Turn Budget Governor (Call 3 of turn)
        governor.recordAndCheckCall("GEN-NEW-C: Conditional Critic Call")

        val prompt = buildString {
            append("System: You are an autonomous Agent Critic verifying reasoning quality and errors.\n")
            append("Detected issue: ").append(reason).append("\n")
            append("Original Goal: ").append(cognitiveCall.goal).append("\n")
            append("Original Plan: ").append(cognitiveCall.plan).append("\n")
            if (toolFailed) {
                append("Tool Failure: ").append(toolError).append("\n")
            }
            if (draftResponse != null) {
                append("Draft Response: ").append(draftResponse).append("\n")
            }
            append("\nProvide reflection and error diagnosis to formulate safe fallback guidance.")
        }

        val request = InferenceRequest(
            prompt = prompt,
            context = "turn_goal=conditional_reflection_and_verification",
            params = GenerateParams(
                max_tokens = 256,
                temperature = 0.1f,
                top_p = 0.9f,
                mode = "critic_reflection"
            )
        )

        val result = inferenceProvider.generate(request)
        val feedbackText = if (result.isSuccess) {
            result.getOrNull()!!.text
        } else {
            "Critic inference failed, using rule-based fallback advice: Verify parameters and check network connectivity."
        }

        return CriticEvaluation(
            triggered = true,
            triggerReason = reason,
            criticFeedback = feedbackText,
            shouldRevisePlan = true,
            revisedPlan = listOf("Verify fallback state", "Notify user transparently of obstacle")
        )
    }
}
