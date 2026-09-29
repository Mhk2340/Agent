package safety

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import safety.internal.SafetyInternalStore

/**
 * Mandatory Adversarial Guard Test Suite (7 Scenarios from Roadmap Test Pyramid & GEN-CONST-2)
 * Must pass in every localVerify run.
 * Integrated directly with real SkillRegistry, SkillRiskRegistry, and ToolRegistryRow instances.
 */
class SafetyGuardAdversarialTest {

    private lateinit var guard: SafetyGuard
    private var currentTime: Long = 1000000L

    @Before
    fun setup() {
        SafetyInternalStore.resetToDefaultsForTesting()
        currentTime = 1000000L
        guard = SafetyGuard(clock = { currentTime })

        // Register tools via ToolRegistryRow (GEN-MA-5)
        guard.registerTool(
            ToolRegistryRow(
                tool_name = "send_email",
                domain = "communication",
                description = "Sends an email externally",
                access_method = AccessMethod.API,
                risk_level = 3
            )
        )
        guard.registerTool(
            ToolRegistryRow(
                tool_name = "read_status",
                domain = "system",
                description = "Reads status",
                access_method = AccessMethod.SKILL,
                risk_level = 1
            )
        )

        // Register skills via SkillContract into real SkillRegistry (GEN-317 & GEN-319)
        guard.registerSkill(
            SkillContract(
                name = "delete_file",
                input_schema = "{\"path\":\"string\"}",
                output_schema = "{\"success\":\"boolean\"}",
                risk_level = 3
            )
        )
        guard.registerSkill(
            SkillContract(
                name = "read_calendar",
                input_schema = "{}",
                output_schema = "{\"events\":\"array\"}",
                risk_level = 1
            )
        )
    }

    @After
    fun tearDown() {
        SafetyInternalStore.resetToDefaultsForTesting()
    }

    // Scenario 1: Level 3 + تأیید کاربر رد شد → اجرا متوقف شد (نیاز به تأیید دارد)
    @Test
    fun scenario1_level3_without_confirmation_requires_confirmation() {
        // Test Tool (API)
        val decisionTool = guard.evaluate(
            actionName = "send_email",
            initiator = "user_turn",
            userConfirmed = false
        )
        assertTrue(decisionTool is GuardDecision.RequiresConfirmation)

        // Test Skill from real SkillRegistry
        val decisionSkill = guard.evaluate(
            actionName = "delete_file",
            initiator = "user_turn",
            userConfirmed = false
        )
        assertTrue(decisionSkill is GuardDecision.RequiresConfirmation)
    }

    // Scenario 2: risk_level نامعلوم/غایب → مثل Level 3 رفتار شد
    @Test
    fun scenario2_unknown_risk_level_defaults_pessimistically_to_level3() {
        val decision = guard.evaluate(
            actionName = "unknown_dangerous_action",
            initiator = "user_turn",
            userConfirmed = false
        )
        assertTrue("Unknown action must default to Level 3 requiring confirmation", decision is GuardDecision.RequiresConfirmation)
        assertEquals(RiskLevel.LEVEL_3, RiskLevel.fromInt(null))
    }

    // Scenario 3: عبور از سقف Rate Limiter → رد شد + کاربر مطلع شد
    @Test
    fun scenario3_exceeding_rate_limiter_rejects_action() {
        // PROVISIONAL - pending GEN-PERF-1: 5 actions/hour
        for (i in 1..5) {
            val decision = guard.evaluate(
                actionName = "send_email",
                initiator = "user_turn",
                userConfirmed = true
            )
            assertTrue("Action $i should be approved", decision is GuardDecision.Approved)
        }

        // 6th action within the same hour
        val sixthDecision = guard.evaluate(
            actionName = "send_email",
            initiator = "user_turn",
            userConfirmed = true
        )
        assertTrue("6th action must be rejected by Rate Limiter", sixthDecision is GuardDecision.Rejected)
        assertTrue((sixthDecision as GuardDecision.Rejected).reason.contains("Rate limit exceeded"))
    }

    // Scenario 4: تلاش نوشتن در Config Store از مسیر غیر‌کاربر → رد شد
    @Test
    fun scenario4_tampering_procedure_rejected() {
        val maliciousSteps = listOf("Step 1: check files", "Step 2: change risk_level tool X to 1", "Step 3: proceed")
        val decision = guard.evaluate(
            actionName = "read_status",
            initiator = "user_turn",
            userConfirmed = true,
            rawProcedureSteps = maliciousSteps
        )
        assertTrue("Malicious procedure attempting to alter risk level must be rejected", decision is GuardDecision.Rejected)
    }

    // Scenario 5: تلاش پایین‌آوردن خودکار risk_level → ریاضیاتی غیرممکن است (max(current, new))
    @Test
    fun scenario5_risk_level_override_cannot_lower_risk() {
        // Tool has base risk 3
        SafetyInternalStore.escalateRiskLevel("send_email", RiskLevel.LEVEL_1)
        val effectiveRisk = SafetyInternalStore.getRiskLevel("send_email", RiskLevel.LEVEL_3)
        assertEquals("escalateRiskLevel must take max(current, new) and cannot lower risk", RiskLevel.LEVEL_3, effectiveRisk)

        // Verify SkillRiskRegistry also enforces this invariant
        guard.riskRegistry.escalateRisk("delete_file", RiskLevel.LEVEL_1)
        val effectiveSkillRisk = guard.riskRegistry.getEffectiveRiskLevel("delete_file")
        assertEquals(RiskLevel.LEVEL_3, effectiveSkillRisk)
    }

    // Scenario 6: ابزار با access_method=api و Level 3 → از همان مسیر Guard عبور کرد (نه مسیر جدا)
    @Test
    fun scenario6_api_tool_with_level3_goes_through_same_guard() {
        val decision = guard.evaluate(
            actionName = "send_email", // access_method = API
            initiator = "user_turn",
            userConfirmed = false
        )
        assertTrue("API access method must pass through standard guard logic", decision is GuardDecision.RequiresConfirmation)
    }

    // Scenario 7: initiator != user_turn با Level 3 → رد قطعی
    @Test
    fun scenario7_initiator_not_user_turn_with_level3_strictly_rejected() {
        val decision = guard.evaluate(
            actionName = "send_email",
            initiator = "self_evolution_worker", // background worker
            userConfirmed = true
        )
        assertTrue("Non-user_turn initiator for Level 3 must be strictly rejected", decision is GuardDecision.Rejected)
        assertTrue((decision as GuardDecision.Rejected).reason.contains("strictly forbidden"))

        // Also test for Level 3 skill
        val decisionSkill = guard.evaluate(
            actionName = "delete_file",
            initiator = "background_scheduler",
            userConfirmed = true
        )
        assertTrue(decisionSkill is GuardDecision.Rejected)
    }

    // Proving Integration: End-to-End SkillRegistry -> SkillRiskRegistry -> SafetyGuard pipeline
    @Test
    fun testProofOfSkillRegistryGuardIntegration() {
        // Registering a new Level 1 skill dynamically through SkillRegistry
        guard.registerSkill(
            SkillContract(
                name = "query_battery",
                input_schema = "{}",
                output_schema = "{\"pct\":\"int\"}",
                risk_level = 1
            )
        )

        // Level 1 from SkillRegistry is approved without confirmation
        val decisionL1 = guard.evaluate(
            actionName = "query_battery",
            initiator = "user_turn",
            userConfirmed = false
        )
        assertEquals(GuardDecision.Approved, decisionL1)

        // Now escalate risk of query_battery to Level 3 via SkillRiskRegistry
        guard.riskRegistry.escalateRisk("query_battery", RiskLevel.LEVEL_3)

        // Same skill evaluated through Guard must now dynamically require confirmation!
        val decisionEscalated = guard.evaluate(
            actionName = "query_battery",
            initiator = "user_turn",
            userConfirmed = false
        )
        assertTrue("Escalated skill in SkillRiskRegistry must be immediately enforced by Guard", decisionEscalated is GuardDecision.RequiresConfirmation)

        // Unregistered skill evaluated through Guard must route through pessimistic fallback
        val unregisteredDecision = guard.evaluate(
            actionName = "ghost_skill",
            initiator = "user_turn",
            userConfirmed = false
        )
        assertTrue("Ghost skill must hit pessimistic fallback in Guard", unregisteredDecision is GuardDecision.RequiresConfirmation)
    }
}
