package safety

import safety.internal.SafetyInternalStore
import java.util.concurrent.ConcurrentHashMap

/**
 * GEN-NEW-GUARD, GEN-NEW-RISK, GEN-NEW-RATE implementation.
 * Independent Safety Guard - strictly non-bypassable and immutable to self-evolution.
 *
 * Integrated with SkillRegistry, SkillRiskRegistry and ToolRegistryRow.
 */
class SafetyGuard(
    private val clock: () -> Long = { System.currentTimeMillis() },
    val riskRegistry: SkillRiskRegistry = SkillRiskRegistry(),
    val skillRegistry: SkillRegistry = SkillRegistry(riskRegistry)
) {
    private val actionTimestamps = mutableListOf<Long>()
    private val toolRegistry = ConcurrentHashMap<String, ToolRegistryRow>()

    fun registerTool(tool: ToolRegistryRow) {
        toolRegistry[tool.tool_name] = tool
        riskRegistry.registerSkillRisk(tool.tool_name, RiskLevel.fromInt(tool.risk_level))
    }

    fun registerSkill(skill: SkillContract) {
        skillRegistry.register(skill)
    }

    /**
     * Inspects and validates an action execution request against the 7 mandatory invariant rules.
     */
    fun evaluate(
        actionName: String,
        initiator: String,
        userConfirmed: Boolean = false,
        rawProcedureSteps: List<String> = emptyList()
    ): GuardDecision {
        // 1. Check for malicious procedural injection (GEN-CI-1 tampering attempt)
        for (step in rawProcedureSteps) {
            if (step.contains("risk_level", ignoreCase = true) && 
                (step.contains("1") || step.contains("lower") || step.contains("bypass"))) {
                return GuardDecision.Rejected("Malicious procedure detected: attempt to manipulate risk_level")
            }
        }

        // 2. Resolve Effective Risk Level (Checks ToolRegistry or SkillRegistry or defaults to LEVEL_3)
        val tool = toolRegistry[actionName]
        val skill = skillRegistry.get(actionName)
        val baseRisk = when {
            tool != null -> RiskLevel.fromInt(tool.risk_level)
            skill != null -> RiskLevel.fromInt(skill.risk_level)
            else -> RiskLevel.LEVEL_3 // Default pessimistic fallback for unknown
        }
        val effectiveRisk = SafetyInternalStore.getRiskLevel(actionName, baseRisk)

        // 3. Rule: Level 3 actions MUST have initiator == "user_turn"
        if (effectiveRisk == RiskLevel.LEVEL_3 && initiator != "user_turn") {
            return GuardDecision.Rejected("Level 3 actions strictly forbidden when initiator is not 'user_turn' (attempted by: $initiator)")
        }

        // 4. Rate Limiter check for Level 3 actions
        if (effectiveRisk == RiskLevel.LEVEL_3) {
            pruneExpiredTimestamps()
            // PROVISIONAL - pending GEN-PERF-1: default 5/hour
            val maxAllowed = SafetyInternalStore.getMaxLevel3ActionsPerHour()
            if (actionTimestamps.size >= maxAllowed) {
                return GuardDecision.Rejected("Rate limit exceeded for Level 3 actions: max $maxAllowed per hour")
            }
        }

        // 5. Level 3 Confirmation Check
        if (effectiveRisk == RiskLevel.LEVEL_3) {
            if (!userConfirmed) {
                return GuardDecision.RequiresConfirmation(
                    reason = "Action '$actionName' is Level 3 (high risk) and requires explicit user confirmation",
                    toolName = actionName
                )
            }
        }

        // 6. Record timestamp upon successful evaluation for Level 3
        if (effectiveRisk == RiskLevel.LEVEL_3) {
            actionTimestamps.add(clock())
        }

        return GuardDecision.Approved
    }

    private fun pruneExpiredTimestamps() {
        val oneHourAgo = clock() - 3600_000L
        actionTimestamps.removeAll { it < oneHourAgo }
    }
}
