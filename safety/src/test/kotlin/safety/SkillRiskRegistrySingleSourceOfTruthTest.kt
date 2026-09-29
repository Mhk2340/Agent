package safety

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import safety.internal.SafetyInternalStore

/**
 * Verifies Single Source of Truth for Risk Levels:
 * SkillRiskRegistry & SafetyGuard MUST reflect direct changes in SafetyInternalStore.
 * Proves there are no un-synchronized parallel stores or config bypasses.
 */
class SkillRiskRegistrySingleSourceOfTruthTest {

    private lateinit var guard: SafetyGuard
    private lateinit var riskRegistry: SkillRiskRegistry
    private lateinit var skillRegistry: SkillRegistry

    @Before
    fun setup() {
        SafetyInternalStore.resetToDefaultsForTesting()
        riskRegistry = SkillRiskRegistry()
        skillRegistry = SkillRegistry(riskRegistry)
        guard = SafetyGuard(riskRegistry = riskRegistry, skillRegistry = skillRegistry)

        // Register a base Level 1 skill
        guard.registerSkill(
            SkillContract(
                name = "read_device_time",
                input_schema = "{}",
                output_schema = "{\"time\":\"long\"}",
                risk_level = 1
            )
        )
    }

    @After
    fun tearDown() {
        SafetyInternalStore.resetToDefaultsForTesting()
    }

    @Test
    fun testDirectSafetyInternalStoreUpdateReflectedInSkillRiskRegistryAndGuard() {
        // Initial state: Level 1 in Registry and Guard
        assertEquals(RiskLevel.LEVEL_1, riskRegistry.getEffectiveRiskLevel("read_device_time"))
        val initialDecision = guard.evaluate("read_device_time", initiator = "user_turn")
        assertEquals(GuardDecision.Approved, initialDecision)

        // 1. Direct mutation in SafetyInternalStore via UserActionToken (NOT via SkillRiskRegistry)
        val userToken = SafetyInternalStore.UserActionToken.createForUserAction("admin-ui-settings")
        SafetyInternalStore.userManualOverride(userToken, "read_device_time", RiskLevel.LEVEL_3)

        // 2. SkillRiskRegistry MUST see this change immediately because it queries SafetyInternalStore
        val effectiveRisk = riskRegistry.getEffectiveRiskLevel("read_device_time")
        assertEquals("SkillRiskRegistry must immediately reflect the direct update in SafetyInternalStore", RiskLevel.LEVEL_3, effectiveRisk)

        // 3. SafetyGuard MUST also see this change immediately and enforce confirmation
        val updatedDecision = guard.evaluate("read_device_time", initiator = "user_turn", userConfirmed = false)
        assertTrue("SafetyGuard must immediately require confirmation after direct SafetyInternalStore update", updatedDecision is GuardDecision.RequiresConfirmation)
        assertEquals("read_device_time", (updatedDecision as GuardDecision.RequiresConfirmation).toolName)

        // 4. Escalation via SafetyInternalStore directly
        SafetyInternalStore.escalateRiskLevel("read_device_time", RiskLevel.LEVEL_2) // Ignored because 3 > 2 (monotonic)
        assertEquals(RiskLevel.LEVEL_3, riskRegistry.getEffectiveRiskLevel("read_device_time"))
    }
}
