package persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import memory.EncryptedMemoryStorage
import memory.MemoryCallerRole
import memory.MemoryManager
import memory.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import safety.SkillContract
import safety.SafetyGuard
import shared.change_management.ChangeAuditLogger
import shared.change_management.ChangeAuditRecord
import shared.change_management.ChangeProvenanceLogger
import skills.ActionAuditLog
import skills.ActionAuditRecord
import skills.ActionInitiator
import skills.ActionRateLimiter
import skills.ActionSimulator
import skills.ExecutionStatus
import skills.SkillExecutionEngine
import skills.SkillExecutionRequest
import testutil.RoomTestEnvironment
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** File-backed SQLite integration coverage; no map or in-memory database is used here. */
// PROVISIONAL: API 28 keeps Robolectric's platform artifact smaller; these tests only assert SQLite/Room persistence semantics supported at this API.
@Config(sdk = [28], manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class AgentDatabasePersistenceTest {
    @Test
    fun fileBackedRoomDatabaseRetainsEncryptedMemoryAndSharedAppendOnlyLogsAfterReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "phase-b-persistence-${UUID.randomUUID()}.db"
        val cipher = RoomTestEnvironment.newCipher()
        var memoryId = ""

        val firstDatabase = RoomTestEnvironment.fileDatabase(context, databaseName, cipher)
        try {
            val storage = EncryptedMemoryStorage(firstDatabase, cipher)
            val memoryManager = MemoryManager(storage, testutil.TestUnlockedAppLockAccess)
            val memory = memoryManager.saveLongTermMemory(
                memoryType = MemoryType.SEMANTIC,
                content = "Sensitive file-backed memory payload",
                embedding = listOf(0.25f, 0.75f),
                importance = 0.9f,
                origin = "user",
                callerRole = MemoryCallerRole.SYSTEM_CORE
            )
            memoryId = memory.id

            val actionAuditLog = ActionAuditLog(memoryManager, firstDatabase, cipher)
            val actionRecord = ActionAuditRecord(
                auditId = "audit-file-backed-1",
                actionId = "action-file-backed-1",
                skillName = "send_message",
                riskLevel = 2,
                initiator = "user_turn",
                params = mapOf("body" to "private message body"),
                confirmationPrompt = "Confirm private message?",
                userConfirmed = true,
                executionStatus = "SUCCEEDED",
                output = "message sent",
                failureReason = null,
                costMs = 18L,
                idempotencyKey = null,
                guidedByProcedureId = "procedure-1",
                origin = "real",
                timestamp = 1_700_000_000_000L
            )
            actionAuditLog.recordAction(actionRecord).getOrThrow()

            val provenanceLogger = ChangeProvenanceLogger(memoryManager, cipher)
            provenanceLogger.recordProvenance(
                changeId = "change-file-backed-1",
                triggerSignalId = "signal-1",
                sourceExperienceId = "experience-1",
                rationale = "private provenance rationale",
                proposedTarget = "WEIGHT"
            )
            ChangeAuditLogger(firstDatabase, cipher).record(
                ChangeAuditRecord(
                    changeId = "change-file-backed-1",
                    targetType = "WEIGHT",
                    targetKey = "ranking.relevance",
                    status = "COMMITTED",
                    reason = "private audit explanation",
                    timestamp = 1_700_000_000_001L
                )
            )

            assertFalse(
                firstDatabase.longTermMemoryDao().getById(memoryId)!!.content
                    .contains("Sensitive file-backed memory payload")
            )
            assertFalse(
                firstDatabase.actionAuditDao().getById(actionRecord.auditId)!!.paramsCiphertext
                    .contains("private message body")
            )
            assertFalse(
                firstDatabase.changeProvenanceDao().getAll().single().rationaleCiphertext
                    .contains("private provenance rationale")
            )
            assertFalse(
                firstDatabase.changeAuditDao().getAll().single().reasonCiphertext
                    .contains("private audit explanation")
            )
        } finally {
            firstDatabase.close()
        }

        val secondDatabase = RoomTestEnvironment.fileDatabase(context, databaseName, cipher)
        try {
            val reopenedStorage = EncryptedMemoryStorage(secondDatabase, cipher)
            val persistedMemory = reopenedStorage.getById(memoryId)
            assertNotNull("Memory record must survive closing and reopening the SQLite file", persistedMemory)
            assertEquals("Sensitive file-backed memory payload", persistedMemory!!.content)
            assertEquals(listOf(0.25f, 0.75f), persistedMemory.embedding)

            val reopenedManager = MemoryManager(reopenedStorage, testutil.TestUnlockedAppLockAccess)
            val reopenedActionLog = ActionAuditLog(reopenedManager, secondDatabase, cipher)
            val persistedAction = reopenedActionLog.getAllRecords().single()
            assertEquals("private message body", persistedAction.params["body"])
            assertEquals("message sent", persistedAction.output)

            val persistedProvenance = ChangeProvenanceLogger(reopenedManager, cipher).getAllRecords().single()
            assertEquals("private provenance rationale", persistedProvenance.rationale)
            val persistedChangeAudit = ChangeAuditLogger(secondDatabase, cipher).getAllRecords().single()
            assertEquals("private audit explanation", persistedChangeAudit.reason)
        } finally {
            secondDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun inFlightIdempotencyReservationSurvivesReopenAndPreventsReexecution() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "phase-b-idempotency-${UUID.randomUUID()}.db"
        val idempotencyKey = "epoch-test:transfer-123"
        val cipher = RoomTestEnvironment.newCipher()

        val firstDatabase = RoomTestEnvironment.fileDatabase(context, databaseName, cipher)
        try {
            val firstMemoryManager = MemoryManager(EncryptedMemoryStorage(firstDatabase, cipher), testutil.TestUnlockedAppLockAccess)
            val firstAuditLog = ActionAuditLog(firstMemoryManager, firstDatabase, cipher)

            // Simulate the process stopping after reserving the operation but before its side effect.
            assertNull(
                "The initial reservation should be newly acquired",
                firstAuditLog.reserveIdempotencyKey(
                    key = idempotencyKey,
                    actionId = "transfer-before-restart",
                    skillName = "transfer_funds"
                )
            )
            assertEquals("IN_FLIGHT", firstAuditLog.findByIdempotencyKey(idempotencyKey)?.executionStatus)
        } finally {
            firstDatabase.close()
        }

        // A fresh database/repository/engine instance simulates a process restart on the same file.
        val reopenedDatabase = RoomTestEnvironment.fileDatabase(context, databaseName, cipher)
        try {
            val reopenedMemoryManager = MemoryManager(EncryptedMemoryStorage(reopenedDatabase, cipher), testutil.TestUnlockedAppLockAccess)
            val reopenedAuditLog = ActionAuditLog(reopenedMemoryManager, reopenedDatabase, cipher)
            val persistedReservation = reopenedAuditLog.findByIdempotencyKey(idempotencyKey)
            assertNotNull("The reservation must survive a process restart", persistedReservation)
            assertEquals("IN_FLIGHT", persistedReservation!!.executionStatus)

            val safetyGuard = SafetyGuard()
            safetyGuard.registerSkill(
                SkillContract(
                    name = "transfer_funds",
                    input_schema = "{}",
                    output_schema = "{}",
                    risk_level = 3
                )
            )
            val sideEffectCount = AtomicInteger(0)
            val engine = SkillExecutionEngine(
                safetyGuard = safetyGuard,
                auditLog = reopenedAuditLog,
                appLockAccess = testutil.TestUnlockedAppLockAccess,
                simulator = ActionSimulator(safetyGuard),
                rateLimiter = ActionRateLimiter(maxLevel3ActionsPerHour = 3)
            )
            engine.registerHandler("transfer_funds") {
                sideEffectCount.incrementAndGet()
                "transfer submitted"
            }

            val retryResult = engine.executeSkill(
                SkillExecutionRequest(
                    actionId = "transfer-after-restart",
                    skillName = "transfer_funds",
                    params = mapOf("amount" to "100", "destination" to "account-7"),
                    initiator = ActionInitiator.USER_TURN,
                    idempotencyKey = idempotencyKey,
                    userConfirmed = true
                )
            )

            assertEquals(ExecutionStatus.IDEMPOTENT_SKIPPED, retryResult.status)
            assertTrue(retryResult.failureReason.orEmpty().contains("IN_FLIGHT"))
            assertEquals("An in-flight retry must not invoke the external side effect", 0, sideEffectCount.get())
            assertEquals("The retry must not append another audit event", 1, reopenedAuditLog.getAllRecords().size)
        } finally {
            reopenedDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }
}
