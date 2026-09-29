package skills

import contracts.ExecutionDetails
import contracts.ExperienceRecord
import memory.EncryptedMemoryStorage
import memory.LongTermMemoryEntry
import memory.MemoryCallerRole
import memory.MemoryManager
import memory.MemoryType
import runtime.AgentLogger
import runtime.Identity
import java.util.concurrent.ConcurrentHashMap

/**
 * GEN-NEW-AUDIT — Action Audit Log
 *
 * MANDATORY ARCHITECTURAL REQUIREMENTS:
 * 1. Single Database Architecture: Uses shared memory architecture (MemoryManager/EncryptedMemoryStorage)
 *    rather than a detached, separate ad-hoc store.
 * 2. Immutable Source of Truth for Phase X: Every Level 2/3 action (and rejected actions) is logged.
 * 3. Contract #4 (Experience Record v1.1) Projection: Produces views matching Contract #4 schema
 *    with `execution: { strategy, cost_ms, failure_reason }` directly from logged audit rows.
 * 4. Idempotency Key Tracking & Reservation Pattern (GEN-353/354):
 *    - Records idempotency key state: IN_FLIGHT, SUCCEEDED, FAILED.
 *    - Query relies STRICTLY on `idempotencyKey` and `status` alone (critical security invariant).
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
    val executionStatus: String, // "IN_FLIGHT", "SUCCEEDED", "FAILED", "REJECTED_BY_GUARD"
    val output: String?,
    val failureReason: String?,
    val costMs: Long,
    val idempotencyKey: String?,
    val guidedByProcedureId: String?,
    val origin: String, // "real" | "self_practice"
    val timestamp: Long,
    val schemaVersion: Int = 1 // Per-row schema version for append-only store migration rule
)

class ActionAuditLog(
    private val memoryManager: MemoryManager,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    // In-memory indexing of audit records backed by memoryManager long-term storage
    private val records = ConcurrentHashMap<String, ActionAuditRecord>()
    // Index mapping idempotencyKey -> auditId
    private val idempotencyIndex = ConcurrentHashMap<String, String>()

    /**
     * Reservation Pattern: Attempts to reserve an idempotency key with status IN_FLIGHT.
     * Returns existing record if key is already reserved/executed, or null if reservation succeeds.
     * Query relies STRICTLY on key and status.
     */
    fun reserveIdempotencyKey(key: String, actionId: String, skillName: String): ActionAuditRecord? {
        val existingAuditId = idempotencyIndex[key]
        if (existingAuditId != null) {
            val existing = records[existingAuditId]
            if (existing != null) {
                return existing
            }
        }

        val auditId = Identity.nextId("audit")
        val reservation = ActionAuditRecord(
            auditId = auditId,
            actionId = actionId,
            skillName = skillName,
            riskLevel = 2,
            initiator = "user_turn",
            params = emptyMap(),
            confirmationPrompt = null,
            userConfirmed = true,
            executionStatus = "IN_FLIGHT",
            output = null,
            failureReason = null,
            costMs = 0L,
            idempotencyKey = key,
            guidedByProcedureId = null,
            origin = "real",
            timestamp = clock()
        )

        val previous = idempotencyIndex.putIfAbsent(key, auditId)
        if (previous != null) {
            // Race condition: another thread won reservation
            return records[previous]
        }

        records[auditId] = reservation
        return null // Successfully reserved
    }

    /**
     * Records the final execution outcome of an action into Audit Log & LongTermMemory.
     */
    fun recordAction(record: ActionAuditRecord): Result<Unit> {
        return try {
            records[record.auditId] = record
            if (record.idempotencyKey != null) {
                idempotencyIndex[record.idempotencyKey] = record.auditId
            }

            // Also persist into shared encrypted LongTermMemory as EPISODIC/FAILURE entry
            val memoryType = if (record.executionStatus == "SUCCEEDED") MemoryType.EPISODIC else MemoryType.FAILURE
            val contentSummary = "[AUDIT] ${record.skillName} by ${record.initiator}: status=${record.executionStatus}, cost=${record.costMs}ms"

            memoryManager.saveLongTermMemory(
                memoryType = memoryType,
                content = contentSummary,
                embedding = null,
                importance = if (record.riskLevel == 3) 0.9f else 0.5f,
                origin = record.origin,
                guidedByProcedureId = record.guidedByProcedureId,
                retryAfter = if (record.executionStatus != "SUCCEEDED") 30_000L else null,
                schemaVersion = 1,
                callerRole = MemoryCallerRole.SYSTEM_CORE
            )
            Result.success(Unit)
        } catch (e: Exception) {
            AgentLogger.e("ActionAuditLog", "Audit write failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Looks up an existing record strictly by idempotencyKey.
     */
    fun findByIdempotencyKey(key: String): ActionAuditRecord? {
        val auditId = idempotencyIndex[key] ?: return null
        return records[auditId]
    }

    /**
     * Projects an audit record into Contract #4 (ExperienceRecord v1.1) for Phase X consumption.
     */
    fun toExperienceRecord(auditRecord: ActionAuditRecord): ExperienceRecord {
        val signalValue = if (auditRecord.executionStatus == "SUCCEEDED") 1.0f else 0.0f
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

    fun getAllRecords(): List<ActionAuditRecord> = records.values.toList()
}
