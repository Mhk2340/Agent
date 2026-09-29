package skills

import safety.RiskLevel

/**
 * GEN-327: Skill Selector
 * GEN-329: Execution Request
 * GEN-330: Execution Context
 * GEN-331: Execution Result
 * GEN-350: Action Simulation Result
 */

enum class ActionInitiator {
    USER_TURN,
    BACKGROUND,
    SELF_PRACTICE,
    EVOLUTION;

    companion object {
        fun fromString(value: String): ActionInitiator {
            return when (value.lowercase()) {
                "user_turn" -> USER_TURN
                "background" -> BACKGROUND
                "self_practice" -> SELF_PRACTICE
                "evolution" -> EVOLUTION
                else -> BACKGROUND // Pessimistic non-user fallback
            }
        }
    }
}

data class SkillExecutionRequest(
    val actionId: String,
    val skillName: String,
    val params: Map<String, String>,
    val initiator: ActionInitiator = ActionInitiator.USER_TURN,
    val idempotencyKey: String? = null,
    val userConfirmed: Boolean = false,
    val guidedByProcedureId: String? = null,
    val origin: String = "real" // "real" | "self_practice"
)

/**
 * GEN-350 — Action Simulation (Dry-Run)
 * Mandatory for ALL risk levels (Level 1, 2, 3), not just Level 3.
 */
data class ActionSimulationResult(
    val skillName: String,
    val resolvedRiskLevel: RiskLevel,
    val simulatedImpact: String,
    val targetRecipient: String?,
    val willMutateExternalState: Boolean,
    val detailedSummary: String,
    val estimatedDurationMs: Long = 100L
)

/**
 * GEN-331 — Execution Result
 */
data class SkillExecutionResult(
    val actionId: String,
    val skillName: String,
    val status: ExecutionStatus,
    val output: String?,
    val failureReason: String?,
    val executionCostMs: Long,
    val strategy: String = "standard_bridge"
)

enum class ExecutionStatus {
    SUCCEEDED,
    FAILED,
    REJECTED_BY_GUARD,
    REQUIRES_CONFIRMATION,
    IDEMPOTENT_SKIPPED
}
