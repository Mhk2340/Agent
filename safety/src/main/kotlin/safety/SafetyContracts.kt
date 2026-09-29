package safety

import kotlinx.serialization.Serializable

/**
 * Risk levels defined in Master Roadmap v4.0.
 * Level 1: Low risk, autonomous execution allowed.
 * Level 2: Medium risk, guarded execution.
 * Level 3: High risk, explicit user confirmation strictly required.
 */
enum class RiskLevel(val value: Int) {
    LEVEL_1(1),
    LEVEL_2(2),
    LEVEL_3(3);

    companion object {
        fun fromInt(value: Int?): RiskLevel {
            return when (value) {
                1 -> LEVEL_1
                2 -> LEVEL_2
                3 -> LEVEL_3
                else -> LEVEL_3 // Default pessimistic fallback as per Invariants
            }
        }
    }
}

/**
 * Contract #2 — Skill Contract (Phase IX, GEN-317)
 */
@Serializable
data class SkillContract(
    val name: String,
    val input_schema: String,
    val output_schema: String,
    val risk_level: Int
) {
    init {
        require(risk_level in 1..3) { "risk_level must be 1, 2, or 3" }
    }
}

/**
 * Contract #5 — Tool Registry Row (Phase XVI, GEN-MA-5) - Safety-Critical
 */
@Serializable
data class ToolRegistryRow(
    val tool_name: String,
    val domain: String,
    val description: String,
    val access_method: AccessMethod,
    val risk_level: Int
) {
    init {
        require(risk_level in 1..3) { "risk_level must be 1, 2, or 3" }
    }
}

@Serializable
enum class AccessMethod {
    API,
    SKILL,
    EXTERNAL_AI
}

/**
 * Result of Safety Guard validation.
 */
sealed class GuardDecision {
    data object Approved : GuardDecision()
    data class RequiresConfirmation(val reason: String, val toolName: String) : GuardDecision()
    data class Rejected(val reason: String) : GuardDecision()
}
