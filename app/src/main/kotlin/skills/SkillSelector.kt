package skills

import safety.RiskLevel
import safety.SafetyGuard
import safety.SkillContract
import safety.ToolRegistryRow

/**
 * GEN-327 — Skill Selector
 * Matches cognitive call output intent/tool with registered skills and tools in SafetyGuard.
 */
class SkillSelector(
    private val safetyGuard: SafetyGuard
) {
    data class SelectionResult(
        val matched: Boolean,
        val skillName: String,
        val riskLevel: RiskLevel,
        val isToolRegistryRow: Boolean
    )

    fun selectSkill(toolName: String): SelectionResult {
        val skillContract: SkillContract? = safetyGuard.skillRegistry.get(toolName)
        if (skillContract != null) {
            return SelectionResult(
                matched = true,
                skillName = skillContract.name,
                riskLevel = RiskLevel.fromInt(skillContract.risk_level),
                isToolRegistryRow = false
            )
        }

        // Unknown tool or external tool: resolved risk defaults pessimistically to Level 3
        val risk = safetyGuard.riskRegistry.getEffectiveRiskLevel(toolName)
        return SelectionResult(
            matched = false,
            skillName = toolName,
            riskLevel = risk,
            isToolRegistryRow = false
        )
    }
}
