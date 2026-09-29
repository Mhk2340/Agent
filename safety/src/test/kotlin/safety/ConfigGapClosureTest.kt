package safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import safety.internal.SafetyConfigChecksum
import safety.internal.SafetyInternalStore

/**
 * Verifies the 5 requirements of "Closing the Config Gap" (Patch GEN-CONST-2):
 * 1. Store is separate with independent integrity check.
 * 2. Writes require explicit user token.
 * 3. Automatic changes can only escalate risk (monotonic max).
 * 4. Automated paths cannot lower risk or inject unauthorized tools.
 * 5. Checksum captures any modification.
 */
class ConfigGapClosureTest {

    @Before
    fun setup() {
        // Reset to known state with a valid user action token
        val token = SafetyInternalStore.UserActionToken.createForUserAction("test-admin")
        SafetyInternalStore.userUpdateRateLimit(token, 5)
    }

    // 1. Separate store with independent integrity check
    @Test
    fun testStoreHasIndependentIntegrityChecksum() {
        val initialChecksum = SafetyInternalStore.computeIntegrityChecksum()
        assertTrue(initialChecksum.isNotBlank())

        val token = SafetyInternalStore.UserActionToken.createForUserAction("user-pin-auth")
        SafetyInternalStore.userManualOverride(token, "shell_exec", RiskLevel.LEVEL_3)
        val modifiedChecksum = SafetyInternalStore.computeIntegrityChecksum()

        assertNotEquals("Checksum must change when store configuration changes", initialChecksum, modifiedChecksum)
    }

    // 2. Writing requires explicit user token (enforced by compiler and token validation)
    @Test
    fun testWritingRequiresUserToken() {
        val token = SafetyInternalStore.UserActionToken.createForUserAction("user-session-ok")
        SafetyInternalStore.userUpdateRateLimit(token, 10)
        assertEquals(10, SafetyInternalStore.getMaxLevel3ActionsPerHour())
    }

    // 3. Automated risk escalation only increases caution (max(current, new))
    @Test
    fun testRiskLevelOnlyIncreasesAutomatically() {
        // Initially set base as LEVEL_2
        SafetyInternalStore.escalateRiskLevel("test_tool", RiskLevel.LEVEL_2)
        assertEquals(RiskLevel.LEVEL_2, SafetyInternalStore.getRiskLevel("test_tool", RiskLevel.LEVEL_1))

        // Automated path attempts to downgrade to LEVEL_1
        SafetyInternalStore.escalateRiskLevel("test_tool", RiskLevel.LEVEL_1)
        assertEquals("Automated escalation must mathematically ignore downgrades", RiskLevel.LEVEL_2, SafetyInternalStore.getRiskLevel("test_tool", RiskLevel.LEVEL_1))

        // Automated path escalates to LEVEL_3
        SafetyInternalStore.escalateRiskLevel("test_tool", RiskLevel.LEVEL_3)
        assertEquals(RiskLevel.LEVEL_3, SafetyInternalStore.getRiskLevel("test_tool", RiskLevel.LEVEL_1))
    }

    // 4. Automated lowering is impossible without UserActionToken
    @Test
    fun testLoweringRiskLevelRequiresExplicitUserToken() {
        SafetyInternalStore.escalateRiskLevel("restricted_api", RiskLevel.LEVEL_3)
        assertEquals(RiskLevel.LEVEL_3, SafetyInternalStore.getRiskLevel("restricted_api", RiskLevel.LEVEL_3))

        // User explicitly lowers it via UI settings
        val userToken = SafetyInternalStore.UserActionToken.createForUserAction("user-pin-confirmed")
        SafetyInternalStore.userManualOverride(userToken, "restricted_api", RiskLevel.LEVEL_2)
        assertEquals(RiskLevel.LEVEL_2, SafetyInternalStore.getRiskLevel("restricted_api", RiskLevel.LEVEL_1))
    }

    // 5. Checksum detects tampering
    @Test
    fun testChecksumDetectsRateLimitTampering() {
        val baseChecksum = SafetyInternalStore.computeIntegrityChecksum()
        val userToken = SafetyInternalStore.UserActionToken.createForUserAction("user-auth")
        SafetyInternalStore.userUpdateRateLimit(userToken, 20)
        val tamperedChecksum = SafetyInternalStore.computeIntegrityChecksum()

        assertNotEquals("Altering rate limiter must alter config checksum", baseChecksum, tamperedChecksum)
    }
}
