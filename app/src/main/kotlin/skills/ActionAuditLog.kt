package skills

import contracts.ExecutionDetails
import contracts.ExperienceRecord
import memory.AesGcmCipher
import memory.MemoryCallerRole
import memory.MemoryManager
import memory.MemoryType
import persistence.ActionAuditEntity
import persistence.ActionIdempotencyEntity
import persistence.AgentDatabase
import runtime.AgentLogger
import runtime.Identity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * GEN-NEW-AUDIT — append-only action audit log in the shared Room database.
 *
 * Sensitive request parameters, confirmation text, output, and failure details are encrypted
 * with AES-256-GCM before insertion. Idempotency state is a separate mutable index table; every
 * reservation and outcome remains an immutable event in action_audit_log.
 */
data class ActionAuditRecord(
    val auditId: String,
    val actionId: String,
    val skillName: String,
    val riskLevel: Int,
    val initiator: String,
    val params: Map<String, String>,
    val confirmationPrompt: String?,
    val userConfirmed: Boolean,
    val executionStatus: String,
    val output: String?,
    val failureReason: String?,
    val costMs: Long,
    val idempotencyKey: String?,
    val guidedByProcedureId: String?,
    val origin: String,
    val timestamp: Long,
    val schemaVersion: Int = 1
)

class ActionAuditLog(
    private val memoryManager: MemoryManager,
    private val database: AgentDatabase = memoryManager.database,
    private val cipher: AesGcmCipher = AesGcmCipher(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val auditDao get() = database.actionAuditDao()
    private val idempotencyDao get() = database.actionIdempotencyDao()

    /**
     * Atomically reserves a new/retry idempotency key. Queries are constrained to key + status;
     * the unique key row arbitrates concurrent callers inside a SQLite transaction.
     * Returns the current record if another request already owns/completed the key, or null when
     * this call successfully appended an IN_FLIGHT reservation event.
     */
    fun reserveIdempotencyKey(key: String, actionId: String, skillName: String): ActionAuditRecord? {
        var existing: ActionAuditRecord? = null
        var reserved = false

        database.runInTransaction {
            val state = idempotencyDao.findByKeyAndStatus(key)
            if (state == null) {
                val reservation = createReservation(key, actionId, skillName)
                val inserted = idempotencyDao.insertIfAbsent(
                    ActionIdempotencyEntity(
                        idempotencyKey = key,
                        status = reservation.executionStatus
                    )
                )
                if (inserted != -1L) {
                    auditDao.insert(reservation.toEntity())
                    reserved = true
                } else {
                    // A concurrent winner committed the unique key. Re-read using key + status.
                    val winner = idempotencyDao.findByKeyAndStatus(key)
                        ?: error("Idempotency key exists in an unsupported status")
                    existing = winner.toCurrentAuditRecord()
                }
            } else if (state.status == STATUS_SUCCEEDED || state.status == STATUS_IN_FLIGHT) {
                existing = state.toCurrentAuditRecord()
            } else {
                // Failed/rejected/confirmation-required attempts can be retried, but first append
                // a fresh reservation and atomically restore the IN_FLIGHT status.
                val reservation = createReservation(key, actionId, skillName)
                auditDao.insert(reservation.toEntity())
                val changed = idempotencyDao.update(
                    state.copy(status = STATUS_IN_FLIGHT)
                )
                check(changed == 1) { "Could not atomically reserve idempotency key" }
                reserved = true
            }
        }

        return if (reserved) null else checkNotNull(existing) {
            "Idempotency reservation completed without a current record"
        }
    }

    /** Append an immutable audit event and synchronize the mutable idempotency index. */
    fun recordAction(record: ActionAuditRecord): Result<Unit> = try {
        database.runInTransaction {
            auditDao.insert(record.toEntity())
            record.idempotencyKey?.let { key -> updateIdempotencyState(key, record) }

            // Preserve the existing GEN-NEW-AUDIT memory projection. The content is subsequently
            // encrypted by the production MemoryStorage before it reaches the same Room database.
            val memoryType = if (record.executionStatus == STATUS_SUCCEEDED) {
                MemoryType.EPISODIC
            } else {
                MemoryType.FAILURE
            }
            val contentSummary = "[AUDIT] ${record.skillName} by ${record.initiator}: status=${record.executionStatus}, cost=${record.costMs}ms"
            memoryManager.saveLongTermMemory(
                memoryType = memoryType,
                content = contentSummary,
                embedding = null,
                importance = if (record.riskLevel == 3) 0.9f else 0.5f,
                origin = record.origin,
                guidedByProcedureId = record.guidedByProcedureId,
                // PROVISIONAL: 30_000 ms was the existing audit-memory retry delay; the roadmap gives no action-retry interval.
                retryAfter = if (record.executionStatus != STATUS_SUCCEEDED) 30_000L else null,
                schemaVersion = 1,
                callerRole = MemoryCallerRole.SYSTEM_CORE
            )
        }
        Result.success(Unit)
    } catch (e: Exception) {
        AgentLogger.e("ActionAuditLog", "Audit write failed: ${e.message}")
        Result.failure(e)
    }

    /**
     * Appends the post-reset Genesis record directly to AUDIT. Unlike ordinary action records it
     * deliberately does not project into long_term_memory, which remains empty after the wipe.
     * The caller retires the previous AES key first; this write then creates/uses the fresh key.
     */
    fun recordPanicResetGenesis(resetAtEpochMs: Long, resetEpoch: Long) {
        val message = "Reset at $resetAtEpochMs, epoch=$resetEpoch, prior history unreadable"
        val record = ActionAuditRecord(
            auditId = Identity.nextId("audit-genesis"),
            actionId = Identity.nextId("panic-reset"),
            skillName = PANIC_RESET_GENESIS_SKILL,
            riskLevel = 3,
            initiator = "system_core",
            params = emptyMap(),
            confirmationPrompt = null,
            userConfirmed = false,
            executionStatus = PANIC_RESET_GENESIS_STATUS,
            output = message,
            failureReason = null,
            costMs = 0L,
            idempotencyKey = null,
            guidedByProcedureId = null,
            origin = "panic_reset",
            timestamp = resetAtEpochMs
        )
        try {
            database.runInTransaction { auditDao.insert(record.toEntity()) }
        } catch (_: Exception) {
            // Do not attach a database/crypto cause; it may contain sensitive provider details.
            throw IllegalStateException("Unable to write Panic Reset Genesis audit record")
        }
    }

    /** Reads only the Genesis entries, allowing them to be checked after older ciphertext is retired. */
    fun getPanicResetGenesisRecords(): List<ActionAuditRecord> =
        auditDao.getBySkillAndStatus(PANIC_RESET_GENESIS_SKILL, PANIC_RESET_GENESIS_STATUS)
            .map { it.toRecord() }

    /**
     * Writes a fixed, credential-free recovery audit event directly to AUDIT. It deliberately does
     * not project into MemoryManager because App Lock remains active until the following final latch
     * commit succeeds. The event records authorization/preparation, not user-entered confirmation text.
     */
    fun recordSecurityLockdownReleaseAuthorized(
        timestampEpochMs: Long,
        resetEpoch: Long,
        securityStateReset: Boolean
    ) {
        val eventKind = if (securityStateReset) "security_state_reset_authorized" else "lockdown_release_authorized"
        val outputText = if (securityStateReset) {
            "Security state reset authorized at $timestampEpochMs, epoch=$resetEpoch"
        } else {
            "Lockdown release authorized at $timestampEpochMs, epoch=$resetEpoch"
        }
        val record = ActionAuditRecord(
            auditId = Identity.nextId("security-recovery"),
            actionId = Identity.nextId("security-recovery-action"),
            skillName = eventKind,
            riskLevel = 3,
            initiator = "user_interaction",
            params = emptyMap(),
            confirmationPrompt = null,
            userConfirmed = true,
            executionStatus = "AUTHORIZED_PENDING_FINALIZE",
            output = outputText,
            failureReason = null,
            costMs = 0L,
            idempotencyKey = null,
            guidedByProcedureId = null,
            origin = "security_recovery",
            timestamp = timestampEpochMs
        )
        try {
            database.runInTransaction { auditDao.insert(record.toEntity()) }
        } catch (_: Exception) {
            throw IllegalStateException("Unable to write security recovery audit record")
        }
    }

    /** Last-resort fallback only if the old shared database AES key could not be retired. */
    fun clearHistoryForPanicReset() {
        try {
            database.runInTransaction {
                auditDao.deleteAll()
                idempotencyDao.deleteAll()
            }
        } catch (_: Exception) {
            throw IllegalStateException("Unable to remove prior audit history")
        }
    }

    /** Looks up an existing audit event strictly by idempotency key and recognized status. */
    fun findByIdempotencyKey(key: String): ActionAuditRecord? =
        idempotencyDao.findByKeyAndStatus(key)?.toCurrentAuditRecord()

    /** Projects an audit record into Contract #4 (ExperienceRecord v1.1). */
    fun toExperienceRecord(auditRecord: ActionAuditRecord): ExperienceRecord {
        val signalValue = if (auditRecord.executionStatus == STATUS_SUCCEEDED) 1.0f else 0.0f
        return ExperienceRecord(
            interaction_id = auditRecord.actionId,
            timestamp = auditRecord.timestamp,
            input_summary = "${auditRecord.skillName} params=${auditRecord.params}",
            output_summary = auditRecord.output ?: auditRecord.failureReason ?: "no output",
            signal_type = "tool_execution_outcome",
            signal_value = signalValue,
            signal_source = "GEN-NEW-AUDIT",
            execution = ExecutionDetails(
                strategy = "standard_bridge",
                cost_ms = auditRecord.costMs,
                failure_reason = auditRecord.failureReason
            )
        )
    }

    fun getAllRecords(): List<ActionAuditRecord> = auditDao.getAll().map { it.toRecord() }

    private fun updateIdempotencyState(key: String, record: ActionAuditRecord) {
        val current = idempotencyDao.findByKeyAndStatus(key)
        if (current == null) {
            val inserted = idempotencyDao.insertIfAbsent(
                ActionIdempotencyEntity(
                    idempotencyKey = key,
                    status = record.executionStatus
                )
            )
            check(inserted != -1L) { "Idempotency state changed to an unsupported status" }
        } else {
            val changed = idempotencyDao.update(
                current.copy(status = record.executionStatus)
            )
            check(changed == 1) { "Could not update idempotency state" }
        }
    }

    private fun ActionIdempotencyEntity.toCurrentAuditRecord(): ActionAuditRecord =
        checkNotNull(auditDao.findIdempotencyEvent(idempotencyKey)) {
            "Idempotency state has no corresponding action_audit_log event"
        }.toRecord()

    private fun createReservation(key: String, actionId: String, skillName: String) = ActionAuditRecord(
        auditId = Identity.nextId("audit"),
        actionId = actionId,
        skillName = skillName,
        riskLevel = 2,
        initiator = "user_turn",
        params = emptyMap(),
        confirmationPrompt = null,
        userConfirmed = true,
        executionStatus = STATUS_IN_FLIGHT,
        output = null,
        failureReason = null,
        costMs = 0L,
        idempotencyKey = key,
        guidedByProcedureId = null,
        origin = ORIGIN_REAL,
        timestamp = clock()
    )

    private fun ActionAuditRecord.toEntity(): ActionAuditEntity = ActionAuditEntity(
        auditId = auditId,
        actionId = actionId,
        skillName = skillName,
        riskLevel = riskLevel,
        initiator = initiator,
        paramsCiphertext = cipher.encrypt(Json.encodeToString(params)),
        confirmationPromptCiphertext = confirmationPrompt?.let(cipher::encrypt),
        userConfirmed = userConfirmed,
        status = executionStatus,
        outputCiphertext = output?.let(cipher::encrypt),
        failureReasonCiphertext = failureReason?.let(cipher::encrypt),
        costMs = costMs,
        idempotencyKey = idempotencyKey,
        guidedByProcedureId = guidedByProcedureId,
        origin = origin,
        timestamp = timestamp,
        schemaVersion = schemaVersion
    )

    private fun ActionAuditEntity.toRecord(): ActionAuditRecord = ActionAuditRecord(
        auditId = auditId,
        actionId = actionId,
        skillName = skillName,
        riskLevel = riskLevel,
        initiator = initiator,
        params = Json.decodeFromString<Map<String, String>>(cipher.decrypt(paramsCiphertext)),
        confirmationPrompt = confirmationPromptCiphertext?.let(cipher::decrypt),
        userConfirmed = userConfirmed,
        executionStatus = status,
        output = outputCiphertext?.let(cipher::decrypt),
        failureReason = failureReasonCiphertext?.let(cipher::decrypt),
        costMs = costMs,
        idempotencyKey = idempotencyKey,
        guidedByProcedureId = guidedByProcedureId,
        origin = origin,
        timestamp = timestamp,
        schemaVersion = schemaVersion
    )

    private companion object {
        const val STATUS_IN_FLIGHT = "IN_FLIGHT"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val ORIGIN_REAL = "real"
        const val PANIC_RESET_GENESIS_SKILL = "panic_reset_genesis"
        const val PANIC_RESET_GENESIS_STATUS = "GENESIS"
    }
}
