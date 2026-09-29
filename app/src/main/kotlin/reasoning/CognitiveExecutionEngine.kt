package reasoning

import contracts.GenerateParams
import contracts.InferenceProvider
import contracts.InferenceRequest
import contracts.StructuredCognitiveCall
import runtime.AgentLogger

/**
 * GEN-NEW-A — Structured Cognitive Call
 * Executes the primary reasoning call of the turn using Contract #1, constrained by GEN-NEW-E.
 * Enforces the architecture invariant: Max 3 LLM calls per turn.
 * Includes Model Failure Recovery (GEN-310) with constrained retry logic.
 */
class CognitiveExecutionEngine(
    private val inferenceProvider: InferenceProvider,
    private val contextManager: ContextManager,
    private val governor: TurnBudgetGovernor? = null,
    private val maxParseRetries: Int = 1 // Strict bound: max 1 retry within turn budget
) {
    sealed class CognitiveCallResult {
        data class Success(val call: StructuredCognitiveCall, val rawResponse: String, val attemptsMade: Int) : CognitiveCallResult()
        data class Failure(val reason: String, val attemptsMade: Int, val cause: Throwable? = null) : CognitiveCallResult()
    }

    /**
     * Executes Call 1 of the turn: Intent, Goal, Plan, Tool Needs, Confidence.
     * In the absence of native GBNF token-level decoding, activates GEN-310 failure recovery
     * to safely retry or reject corrupted outputs within the turn budget.
     */
    fun executeCognitiveCall(
        userMessage: String,
        sessionHistory: List<String> = emptyList()
    ): CognitiveCallResult {
        val assembled = contextManager.assembleContext(userMessage, sessionHistory)

        var attempts = 0
        var lastError: Throwable? = null

        while (attempts <= maxParseRetries) {
            attempts++
            governor?.recordAndCheckCall("GEN-NEW-A: Structured Cognitive Call (Attempt $attempts)")
            val request = InferenceRequest(
                prompt = assembled.fullPrompt,
                context = if (attempts == 1) "turn_goal=understand_intent_and_plan" 
                          else "turn_goal=understand_intent_and_plan;retry=output_must_strictly_be_valid_json",
                params = GenerateParams(
                    max_tokens = 512,
                    temperature = 0.0f, // Greedy decoding for structured consistency
                    top_p = 0.9f,
                    mode = "structured_cognitive_call"
                ),
                outputSchema = StructuredGrammarConstraint.cognitiveCallSchemaJson
            )

            AgentLogger.i("CognitiveExecutionEngine", "Executing Structured Cognitive Call (Attempt $attempts)...")
            val inferenceResult = inferenceProvider.generate(request)

            if (inferenceResult.isFailure) {
                lastError = inferenceResult.exceptionOrNull()
                AgentLogger.w("CognitiveExecutionEngine", "Inference attempt $attempts failed: ${lastError?.message}")
                continue
            }

            val rawText = inferenceResult.getOrNull()!!.text
            val validation = StructuredGrammarConstraint.validateAndParse(rawText)

            if (validation.isSuccess) {
                return CognitiveCallResult.Success(
                    call = validation.getOrNull()!!,
                    rawResponse = rawText,
                    attemptsMade = attempts
                )
            } else {
                lastError = validation.exceptionOrNull()
                AgentLogger.w("CognitiveExecutionEngine", "Grammar violation on attempt $attempts: ${lastError?.message}")
            }
        }

        return CognitiveCallResult.Failure(
            reason = "Model failure recovery exhausted: Malformed output could not be parsed into Contract #3 after $attempts attempts",
            attemptsMade = attempts,
            cause = lastError
        )
    }
}
