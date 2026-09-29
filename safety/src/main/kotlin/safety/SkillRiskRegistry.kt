package safety

import safety.internal.SafetyInternalStore
import java.util.concurrent.ConcurrentHashMap

/**
 * GEN-NEW-RISK — Skill Risk Registry
 * Classifies skills into Risk Level 1, 2, or 3.
 *
 * Enforces Invariants:
 * 1. Monotonic automatic updates (max(current, new) only).
 * 2. Unregistered / unknown skills fallback pessimistically to LEVEL_3.
 * 3. Integrated directly with SafetyInternalStore (Config Gap Closure).
 */
class SkillRiskRegistry {
    private val skillRisks = ConcurrentHashMap<String, RiskLevel>()

    fun registerSkillRisk(skillName: String, declaredLevel: RiskLevel) {
        skillRisks[skillName] = declaredLevel
    }

    fun getEffectiveRiskLevel(skillName: String): RiskLevel {
        val baseRisk = skillRisks[skillName] ?: RiskLevel.LEVEL_3 // Pessimistic fallback
        return SafetyInternalStore.getRiskLevel(skillName, baseRisk)
    }

    /**
     * Automated escalation path (mathematically monotonic max(current, new)).
     */
    fun escalateRisk(skillName: String, newLevel: RiskLevel) {
        SafetyInternalStore.escalateRiskLevel(skillName, newLevel)
    }
}
