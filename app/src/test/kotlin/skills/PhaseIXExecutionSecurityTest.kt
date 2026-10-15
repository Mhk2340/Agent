package skills

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import memory.AesGcmCipher
import memory.EncryptedMemoryStorage
import memory.MemoryCallerRole
import memory.MemoryManager
import memory.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import persistence.AgentDatabase
import testutil.RoomTestEnvironment
import testutil.TestUnlockedAppLockAccess
import safety.RiskLevel
import safety.SafetyGuard
import safety.SkillContract

/**
 * Phase IX Tier 1 Test Suite:
 * - GEN-327: Skill Selector
 * - GEN-328: Skill Execution Engine (Mandatory non-bypassable Safety Guard)
 * - GEN-350: Action Simulation (Mandatory for all risk levels 1, 2, 3)
 * - GEN-NEW-CONFIRM: Precise Non-Generic Confirmation Prompt Generator
 * - GEN-NEW-RATE: Action Rate Limiter (Level 3 actions)
 * - GEN-NEW-AUDIT: Action Audit Log & Projection to Contract #4 (Experience Record v1.1)
 * - GEN-353/354: Idempotency Key Reservation Pattern
 */
@RunWith(RobolectricTestRunner::class)
class PhaseIXExecutionSecurityTest {

    private lateinit var database: AgentDatabase
    private lateinit var testCipher: AesGcmCipher
    private lateinit var safetyGuard: SafetyGuard
    private lateinit var memoryManager: MemoryManager
    private lateinit var auditLog: ActionAuditLog
    private lateinit var engine: SkillExecutionEngine
    private lateinit var selector: SkillSelector
    private lateinit var simulator: ActionSimulator
    private lateinit var promptGenerator: ConfirmationPromptGenerator
    private lateinit var rateLimiter: ActionRateLimiter

    @Before
    fun setup() {
        testCipher = RoomTestEnvironment.newCipher()
        database = RoomTestEnvironment.inMemoryDatabase(
            ApplicationProvider.getApplicationContext<Context>(),
            testCipher
        )
        safetyGuard = SafetyGuard()
        val storage = EncryptedMemoryStorage(database, testCipher)
        memoryManager = MemoryManager(storage = storage, appLockAccess = TestUnlockedAppLockAccess)
        auditLog = ActionAuditLog(memoryManager, database, testCipher)
        simulator = ActionSimulator(safetyGuard)
        promptGenerator = ConfirmationPromptGenerator()
        rateLimiter = ActionRateLimiter(maxLevel3ActionsPerHour = 3)

        engine = SkillExecutionEngine(
            safetyGuard = safetyGuard,
            auditLog = auditLog,
            appLockAccess = TestUnlockedAppLockAccess,
            simulator = simulator,
            promptGenerator = promptGenerator,
            rateLimiter = rateLimiter
        )
        selector = SkillSelector(safetyGuard)

        // Register test skills
        safetyGuard.registerSkill(
            SkillContract(
                name = "read_calendar",
                input_schema = "{}",
                output_schema = "{}",
                risk_level = 1
            )
        )
        safetyGuard.registerSkill(
            SkillContract(
                name = "create_note",
                input_schema = "{}",
                output_schema = "{}",
                risk_level = 2
            )
        )
        safetyGuard.registerSkill(
            SkillContract(
                name = "send_sms",
                input_schema = "{}",
                output_schema = "{}",
                risk_level = 3
            )
        )

        // Register execution handlers
        engine.registerHandler("read_calendar") { "Event: Team sync at 10 AM" }
        engine.registerHandler("create_note") { params -> "Note created: ${params["title"]}" }
        engine.registerHandler("send_sms") { params -> "SMS delivered to ${params["recipient"]}" }
    }

    @After
    fun tearDown() {
        database.close()
    }

    // 1. GEN-327 Skill Selector
    @Test
    fun testSkillSelectorMatchingAndPessimisticFallback() {
        val readResult = selector.selectSkill("read_calendar")
        assertTrue(readResult.matched)
        assertEquals(RiskLevel.LEVEL_1, readResult.riskLevel)

        val smsResult = selector.selectSkill("send_sms")
        assertTrue(smsResult.matched)
        assertEquals(RiskLevel.LEVEL_3, smsResult.riskLevel)

        val unknownResult = selector.selectSkill("unregistered_tool")
        assertFalse(unknownResult.matched)
        assertEquals(RiskLevel.LEVEL_3, unknownResult.riskLevel) // Pessimistic fallback
    }

    // 2. GEN-350 Action Simulation for ALL risk levels (Level 1, 2, 3)
    @Test
    fun testActionSimulationMandatoryForAllRiskLevels() {
        // Level 1 Simulation
        val sim1 = simulator.simulate(
            SkillExecutionRequest(
                actionId = "a1",
                skillName = "read_calendar",
                params = emptyMap()
            )
        )
        assertEquals(RiskLevel.LEVEL_1, sim1.resolvedRiskLevel)
        assertFalse(sim1.willMutateExternalState)

        // Level 2 Simulation
        val sim2 = simulator.simulate(
            SkillExecutionRequest(
                actionId = "a2",
                skillName = "create_note",
                params = mapOf("title" to "Grocery list")
            )
        )
        assertEquals(RiskLevel.LEVEL_2, sim2.resolvedRiskLevel)
        assertTrue(sim2.willMutateExternalState)

        // Level 3 Simulation
        val sim3 = simulator.simulate(
            SkillExecutionRequest(
                actionId = "a3",
                skillName = "send_sms",
                params = mapOf("recipient" to "Alice", "message" to "Hello!")
            )
        )
        assertEquals(RiskLevel.LEVEL_3, sim3.resolvedRiskLevel)
        assertTrue(sim3.willMutateExternalState)
        assertEquals("Alice", sim3.targetRecipient)
    }

    // 3. GEN-NEW-CONFIRM Precise Non-Generic Confirmation Prompt
    @Test
    fun testPreciseConfirmationPromptGeneration() {
        val request = SkillExecutionRequest(
            actionId = "act-sms-1",
            skillName = "send_sms",
            params = mapOf("recipient" to "Alice", "message" to "Meeting rescheduled to 4 PM")
        )
        val simulation = simulator.simulate(request)
        val prompt = promptGenerator.generatePrompt(request, simulation)

        assertTrue(prompt.requiresExplicitToken)
        assertEquals(RiskLevel.LEVEL_3, prompt.riskLevel)
        // Must contain exact recipient and message, not generic "Are you sure?"
        assertTrue(prompt.promptText.contains("Alice"))
        assertTrue(prompt.promptText.contains("Meeting rescheduled to 4 PM"))
        assertFalse(prompt.promptText == "آیا مطمئنید؟")
    }

    // 4. GEN-328 & GEN-NEW-GUARD: Level 3 Requires Confirmation, approved once confirmed
    @Test
    fun testLevel3RequiresConfirmationAndExecutesWhenConfirmed() {
        val requestUnconfirmed = SkillExecutionRequest(
            actionId = "sms-req-1",
            skillName = "send_sms",
            params = mapOf("recipient" to "Bob", "message" to "Call me"),
            initiator = ActionInitiator.USER_TURN,
            userConfirmed = false
        )

        val unconfirmedResult = engine.executeSkill(requestUnconfirmed)
        assertEquals(ExecutionStatus.REQUIRES_CONFIRMATION, unconfirmedResult.status)
        assertTrue(unconfirmedResult.output!!.contains("Bob"))

        // Now confirm and execute
        val requestConfirmed = requestUnconfirmed.copy(userConfirmed = true)
        val confirmedResult = engine.executeSkill(requestConfirmed)
        assertEquals(ExecutionStatus.SUCCEEDED, confirmedResult.status)
        assertEquals("SMS delivered to Bob", confirmedResult.output)
    }

    // 5. GEN-328: Level 3 strictly rejected when initiator is not USER_TURN
    @Test
    fun testLevel3StrictlyRejectedForNonUserInitiator() {
        val requestBg = SkillExecutionRequest(
            actionId = "sms-bg-1",
            skillName = "send_sms",
            params = mapOf("recipient" to "Bob", "message" to "Automated trigger"),
            initiator = ActionInitiator.BACKGROUND,
            userConfirmed = true
        )

        val result = engine.executeSkill(requestBg)
        assertEquals(ExecutionStatus.REJECTED_BY_GUARD, result.status)
        assertTrue(result.failureReason!!.contains("strictly forbidden when initiator is not 'user_turn'"))
    }

    // 6. GEN-NEW-RATE: Action Rate Limiter rejects actions exceeding threshold
    @Test
    fun testActionRateLimiterCeiling() {
        val req = SkillExecutionRequest(
            actionId = "sms-rate",
            skillName = "send_sms",
            params = mapOf("recipient" to "User", "message" to "Ping"),
            initiator = ActionInitiator.USER_TURN,
            userConfirmed = true
        )

        // Threshold set to 3 in setup()
        val r1 = engine.executeSkill(req.copy(actionId = "s1"))
        assertEquals(ExecutionStatus.SUCCEEDED, r1.status)
        val r2 = engine.executeSkill(req.copy(actionId = "s2"))
        assertEquals(ExecutionStatus.SUCCEEDED, r2.status)
        val r3 = engine.executeSkill(req.copy(actionId = "s3"))
        assertEquals(ExecutionStatus.SUCCEEDED, r3.status)

        // 4th action must exceed limit
        val r4 = engine.executeSkill(req.copy(actionId = "s4"))
        assertEquals(ExecutionStatus.FAILED, r4.status)
        assertTrue(r4.failureReason!!.contains("Rate limit exceeded"))
    }

    // 7. GEN-353/354: Idempotency Key Reservation Pattern
    @Test
    fun testIdempotencyKeyReservationPreventsDuplicateExecution() {
        val req = SkillExecutionRequest(
            actionId = "sms-idemp-1",
            skillName = "send_sms",
            params = mapOf("recipient" to "Charlie", "message" to "Transfer complete"),
            initiator = ActionInitiator.USER_TURN,
            userConfirmed = true,
            idempotencyKey = "epoch1:transfer-9988"
        )

        // First attempt succeeds
        val res1 = engine.executeSkill(req)
        assertEquals(ExecutionStatus.SUCCEEDED, res1.status)
        assertEquals("SMS delivered to Charlie", res1.output)

        // Second attempt with identical idempotencyKey returns IDEMPOTENT_SKIPPED
        val res2 = engine.executeSkill(req.copy(actionId = "sms-idemp-retry"))
        assertEquals(ExecutionStatus.IDEMPOTENT_SKIPPED, res2.status)
        assertEquals("SMS delivered to Charlie", res2.output)

        val idempotencyEvents = auditLog.getAllRecords().filter { it.idempotencyKey == req.idempotencyKey }
        assertEquals("Reservation and final outcome are separate append-only events", 2, idempotencyEvents.size)
        assertTrue(idempotencyEvents.any { it.executionStatus == "IN_FLIGHT" })
        assertTrue(idempotencyEvents.any { it.executionStatus == "SUCCEEDED" })
    }

    // 8. GEN-NEW-AUDIT & Contract #4 Projection Test
    @Test
    fun testActionAuditLogAndContract4ExperienceRecordProjection() {
        val req = SkillExecutionRequest(
            actionId = "note-act-1",
            skillName = "create_note",
            params = mapOf("title" to "Architecture Plan"),
            initiator = ActionInitiator.USER_TURN,
            userConfirmed = true,
            guidedByProcedureId = "proc-arch-01"
        )

        val result = engine.executeSkill(req)
        assertEquals(ExecutionStatus.SUCCEEDED, result.status)

        val records = auditLog.getAllRecords()
        assertTrue(records.isNotEmpty())
        val auditRecord = records.find { it.actionId == "note-act-1" }
        assertNotNull(auditRecord)
        assertEquals("create_note", auditRecord!!.skillName)
        assertEquals("SUCCEEDED", auditRecord.executionStatus)
        assertEquals("proc-arch-01", auditRecord.guidedByProcedureId)

        // Verify projection to Contract #4 (ExperienceRecord v1.1)
        val exp = auditLog.toExperienceRecord(auditRecord)
        assertEquals("note-act-1", exp.interaction_id)
        assertEquals("GEN-NEW-AUDIT", exp.signal_source)
        assertEquals(1.0f, exp.signal_value, 0.001f)
        assertEquals("standard_bridge", exp.execution.strategy)
    }
}
