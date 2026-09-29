package reasoning

import contracts.ExecutionDetails
import contracts.ExperienceRecord
import contracts.GenerateParams
import contracts.InferenceProvider
import contracts.InferenceRequest
import contracts.StructuredCognitiveCall
import memory.LongTermMemoryEntry
import memory.MemoryCallerRole
import memory.MemoryManager
import memory.MemoryType
import runtime.AgentLogger
import runtime.Identity
import skills.SkillExecutionResult

/**
 * GEN-NEW-B — Response Generation Call
 *
 * MANDATORY ARCHITECTURAL REQUIREMENTS:
 * 1. Second LLM Call of the Turn: Produces user-facing final text taking into account:
 *    - User instruction / query
 *    - Cognitive Call 1 plan and intent
 *    - Tool Execution Result (if a tool was called)
 *    - Optional Critic feedback (if Critic was triggered)
 * 2. Contract #4 (Experience Record v1.1) Integration:
 *    Persists the full interaction turn as an ExperienceRecord into shared memory storage
 *    so Phase X learning (GEN-361/362) has complete feedback provenance.
 * 3. Governed by Turn Budget Governor (GEN-NEW-D).
 */
class ResponseGenerationCall(
    private val inferenceProvider: InferenceProvider,
    private val governor: TurnBudgetGovernor,
    private val memoryManager: MemoryManager,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    data class GenerationResponse(
        val finalText: String,
        val experienceRecord: ExperienceRecord
    )

    /**
     * Executes Call 2 of the turn to generate user-facing response and log Experience Record.
     */
    fun generateResponse(
        userMessage: String,
        cognitiveCall: StructuredCognitiveCall,
        toolResult: SkillExecutionResult? = null,
        criticFeedback: String? = null
    ): GenerationResponse {
        val startTime = clock()

        // 1. Record & Check Budget via TurnBudgetGovernor
        governor.recordAndCheckCall("GEN-NEW-B: Response Generation Call")

        // 2. Assemble prompt using Plan and Tool Execution Result
        val prompt = buildString {
            append("System: You are an autonomous Android Agent assisting the user.\n")
            append("User Query: ").append(userMessage).append("\n")
            append("Goal: ").append(cognitiveCall.goal).append("\n")
            append("Plan: ").append(cognitiveCall.plan.joinToString(", ")).append("\n")

            if (toolResult != null) {
                append("\n[Tool Execution Result]:\n")
                append("- Skill: ").append(toolResult.skillName).append("\n")
                append("- Status: ").append(toolResult.status.name).append("\n")
                if (toolResult.output != null) {
                    append("- Output: ").append(toolResult.output).append("\n")
                }
                if (toolResult.failureReason != null) {
                    append("- Failure: ").append(toolResult.failureReason).append("\n")
                }
            }

            if (criticFeedback != null) {
                append("\n[Critic Guidance]:\n").append(criticFeedback).append("\n")
            }

            append("\nProvide a clear, helpful, and concise final response to the user.")
        }

        val request = InferenceRequest(
            prompt = prompt,
            context = "turn_goal=generate_final_response",
            params = GenerateParams(
                max_tokens = 512,
                temperature = 0.3f,
                top_p = 0.9f,
                mode = "response_generation"
            )
        )

        val inferenceResult = inferenceProvider.generate(request)
        val responseText = if (inferenceResult.isSuccess) {
            inferenceResult.getOrNull()!!.text
        } else {
            "I completed the analysis for '${cognitiveCall.intent}', but encountered an issue formatting the output."
        }

        val duration = clock() - startTime

        // 3. Connect to Contract #4: ExperienceRecord v1.1
        val interactionId = Identity.nextId("exp")
        val isSuccessful = toolResult == null || toolResult.status == skills.ExecutionStatus.SUCCEEDED
        val experience = ExperienceRecord(
            interaction_id = interactionId,
            timestamp = startTime,
            input_summary = userMessage,
            output_summary = responseText,
            signal_type = "user_turn_outcome",
            signal_value = if (isSuccessful) 1.0f else 0.0f,
            signal_source = "GEN-NEW-B",
            execution = ExecutionDetails(
                strategy = toolResult?.strategy ?: "direct_cognitive_response",
                cost_ms = duration,
                failure_reason = toolResult?.failureReason
            )
        )

        // Persist to shared long_term_memory
        memoryManager.saveLongTermMemory(
            memoryType = if (isSuccessful) MemoryType.EPISODIC else MemoryType.FAILURE,
            content = "[EXPERIENCE] $userMessage -> $responseText",
            embedding = null,
            importance = if (isSuccessful) 0.6f else 0.8f,
            origin = "real",
            guidedByProcedureId = null,
            retryAfter = null,
            schemaVersion = 1,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )

        return GenerationResponse(
            finalText = responseText,
            experienceRecord = experience
        )
    }
}
