package persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Canonical long_term_memory schema v1.2, persisted by the shared Room database.
 * GEN-MEM-10 provider/freshness metadata is deferred to Phase XVI per the roadmap scope decision;
 * add it later through a schema-additive migration.
 */
@Entity(tableName = "long_term_memory")
data class LongTermMemoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "memory_type")
    val memoryType: String,
    /** AES-256-GCM ciphertext encoded as Base64; plaintext is never stored in SQLite. */
    @ColumnInfo(name = "content")
    val content: String,
    /** AES-256-GCM encrypted packed Float vector; null when no embedding is available. */
    @ColumnInfo(name = "embedding")
    val embedding: ByteArray?,
    @ColumnInfo(name = "importance", defaultValue = "0.5")
    val importance: Float,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "last_accessed_at")
    val lastAccessedAt: Long,
    @ColumnInfo(name = "access_count", defaultValue = "0")
    val accessCount: Int,
    @ColumnInfo(name = "last_retrieved_at", defaultValue = "0")
    val lastRetrievedAt: Long,
    @ColumnInfo(name = "last_used_at", defaultValue = "0")
    val lastUsedAt: Long,
    @ColumnInfo(name = "successful_use_count", defaultValue = "0")
    val successfulUseCount: Int,
    @ColumnInfo(name = "superseded_by")
    val supersededBy: String?,
    @ColumnInfo(name = "origin")
    val origin: String?,
    @ColumnInfo(name = "guided_by_procedure_id")
    val guidedByProcedureId: String?,
    @ColumnInfo(name = "retry_after")
    val retryAfter: Long?,
    @ColumnInfo(name = "schema_version", defaultValue = "1")
    val schemaVersion: Int
)

/** Immutable user-action audit event. Sensitive free-text fields are AES-GCM ciphertext. */
@Entity(
    tableName = "action_audit_log",
    indices = [Index(value = ["idempotency_key", "status"], name = "index_action_audit_log_idempotency_key_status")]
)
data class ActionAuditEntity(
    @PrimaryKey
    @ColumnInfo(name = "audit_id")
    val auditId: String,
    @ColumnInfo(name = "action_id")
    val actionId: String,
    @ColumnInfo(name = "skill_name")
    val skillName: String,
    @ColumnInfo(name = "risk_level")
    val riskLevel: Int,
    @ColumnInfo(name = "initiator")
    val initiator: String,
    @ColumnInfo(name = "params_ciphertext")
    val paramsCiphertext: String,
    @ColumnInfo(name = "confirmation_prompt_ciphertext")
    val confirmationPromptCiphertext: String?,
    @ColumnInfo(name = "user_confirmed")
    val userConfirmed: Boolean,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "output_ciphertext")
    val outputCiphertext: String?,
    @ColumnInfo(name = "failure_reason_ciphertext")
    val failureReasonCiphertext: String?,
    @ColumnInfo(name = "cost_ms")
    val costMs: Long,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String?,
    @ColumnInfo(name = "guided_by_procedure_id")
    val guidedByProcedureId: String?,
    @ColumnInfo(name = "origin")
    val origin: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "schema_version", defaultValue = "1")
    val schemaVersion: Int
)

/**
 * PROVISIONAL auxiliary mutable reservation index: isolates current key/status state from the
 * append-only action_audit_log events so status transitions never rewrite historical events.
 */
@Entity(tableName = "action_idempotency")
data class ActionIdempotencyEntity(
    @PrimaryKey
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "status")
    val status: String
)

/** GEN-CM-6 immutable provenance event; rationale is encrypted at rest. */
@Entity(tableName = "change_provenance_log")
data class ChangeProvenanceEntity(
    @PrimaryKey
    @ColumnInfo(name = "provenance_id")
    val provenanceId: String,
    @ColumnInfo(name = "change_id")
    val changeId: String,
    @ColumnInfo(name = "trigger_signal_id")
    val triggerSignalId: String,
    @ColumnInfo(name = "source_experience_id")
    val sourceExperienceId: String?,
    @ColumnInfo(name = "rationale_ciphertext")
    val rationaleCiphertext: String,
    @ColumnInfo(name = "proposed_target")
    val proposedTarget: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "schema_version", defaultValue = "1")
    val schemaVersion: Int
)

/** GEN-CM-7 immutable change audit event; reason is encrypted at rest. */
@Entity(tableName = "change_audit_log")
data class ChangeAuditEntity(
    @PrimaryKey
    @ColumnInfo(name = "change_id")
    val changeId: String,
    @ColumnInfo(name = "target_type")
    val targetType: String,
    @ColumnInfo(name = "target_key")
    val targetKey: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "reason_ciphertext")
    val reasonCiphertext: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "schema_version", defaultValue = "1")
    val schemaVersion: Int
)

/** Provider-scoped AES-GCM ciphertext only; API key plaintext is never represented in this row. */
@Entity(tableName = "provider_secrets")
data class ProviderSecretEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "ciphertext")
    val ciphertext: ByteArray
) {
    override fun toString(): String =
        "ProviderSecretEntity(providerId=$providerId, ciphertext=<redacted>)"
}
