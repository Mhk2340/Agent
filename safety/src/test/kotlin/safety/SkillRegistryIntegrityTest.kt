package safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies GEN-317, GEN-319, GEN-NEW-RISK invariants:
 * 1. Skill Contract requires explicit risk_level in 1..3.
 * 2. Skill Registry indexes declared skills and exposes effective risk.
 * 3. Unknown skills default pessimistically to LEVEL_3.
 */
class SkillRegistryIntegrityTest {

    private lateinit var skillRegistry: SkillRegistry
    private lateinit var riskRegistry: SkillRiskRegistry

    @Before
    fun setup() {
        riskRegistry = SkillRiskRegistry()
        skillRegistry = SkillRegistry(riskRegistry)
    }

    @Test
    fun testValidSkillRegistration() {
        val skill = SkillContract(
            name = "read_battery",
            input_schema = "{}",
            output_schema = "{\"level\":\"int\"}",
            risk_level = 1
        )
        skillRegistry.register(skill)

        assertTrue(skillRegistry.contains("read_battery"))
        val retrieved = skillRegistry.get("read_battery")
        assertNotNull(retrieved)
        assertEquals(RiskLevel.LEVEL_1, skillRegistry.getEffectiveRisk("read_battery"))
    }

    @Test
    fun testSkillContractEnforcesValidRiskLevelRange() {
        // Valid risk level 1, 2, 3
        SkillContract("s1", "{}", "{}", 1)
        SkillContract("s2", "{}", "{}", 2)
        SkillContract("s3", "{}", "{}", 3)

        // Invalid risk level 0 or 4 must throw IllegalArgumentException
        try {
            SkillContract("s0", "{}", "{}", 0)
            assertTrue("risk_level 0 must be rejected", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("risk_level must be 1, 2, or 3"))
        }

        try {
            SkillContract("s4", "{}", "{}", 4)
            assertTrue("risk_level 4 must be rejected", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("risk_level must be 1, 2, or 3"))
        }
    }

    @Test
    fun testUnknownSkillDefaultsToLevel3() {
        val risk = skillRegistry.getEffectiveRisk("non_existent_skill")
        assertEquals("Unregistered skill must default to Level 3", RiskLevel.LEVEL_3, risk)
    }
}
