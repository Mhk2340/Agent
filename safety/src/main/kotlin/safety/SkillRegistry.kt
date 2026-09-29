package safety

import java.util.concurrent.ConcurrentHashMap

/**
 * GEN-319 — Skill Registry
 * Canonical registry for verified skills.
 *
 * Invariant Enforced:
 * Every skill MUST have an explicit non-null RiskLevel (1, 2, or 3).
 * Unvalidated or missing risk level skills are rejected at registration time.
 */
class SkillRegistry(
    private val riskRegistry: SkillRiskRegistry = SkillRiskRegistry()
) {
    private val registeredSkills = ConcurrentHashMap<String, SkillContract>()

    fun register(contract: SkillContract) {
        val level = RiskLevel.fromInt(contract.risk_level)
        registeredSkills[contract.name] = contract
        riskRegistry.registerSkillRisk(contract.name, level)
    }

    fun get(skillName: String): SkillContract? = registeredSkills[skillName]

    fun getAllSkills(): List<SkillContract> = registeredSkills.values.toList()

    fun getEffectiveRisk(skillName: String): RiskLevel = riskRegistry.getEffectiveRiskLevel(skillName)

    fun contains(skillName: String): Boolean = registeredSkills.containsKey(skillName)
}
