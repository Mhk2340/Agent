package persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface LongTermMemoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: LongTermMemoryEntity): Long

    @Query("SELECT * FROM long_term_memory WHERE id = :id LIMIT 1")
    fun getById(id: String): LongTermMemoryEntity?

    @Query("SELECT * FROM long_term_memory WHERE superseded_by IS NULL ORDER BY created_at, id")
    fun getAllActiveEntries(): List<LongTermMemoryEntity>

    @Query("SELECT * FROM long_term_memory ORDER BY created_at, id")
    fun getAllEntries(): List<LongTermMemoryEntity>

    @Query("SELECT COUNT(*) FROM long_term_memory")
    fun count(): Int

    @Query("DELETE FROM long_term_memory")
    fun deleteAll()

    @Query("UPDATE long_term_memory SET last_retrieved_at = :timestamp WHERE id IN (:ids)")
    fun markRetrieved(ids: List<String>, timestamp: Long): Int

    @Query("UPDATE long_term_memory SET last_accessed_at = :timestamp, last_used_at = :timestamp, successful_use_count = successful_use_count + 1 WHERE id IN (:ids)")
    fun markUsed(ids: List<String>, timestamp: Long): Int
}

@Dao
interface ActionAuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: ActionAuditEntity): Long

    @Query("SELECT * FROM action_audit_log WHERE audit_id = :auditId LIMIT 1")
    fun getById(auditId: String): ActionAuditEntity?

    // Predicate uses only the canonical key and status; SQLite rowid selects the newest append-only event.
    @Query("SELECT * FROM action_audit_log WHERE idempotency_key = :key AND status IN ('IN_FLIGHT', 'SUCCEEDED', 'FAILED', 'REJECTED_BY_GUARD', 'REQUIRES_CONFIRMATION') ORDER BY rowid DESC LIMIT 1")
    fun findIdempotencyEvent(key: String): ActionAuditEntity?

    @Query("SELECT * FROM action_audit_log ORDER BY timestamp, audit_id")
    fun getAll(): List<ActionAuditEntity>

    @Query("SELECT * FROM action_audit_log WHERE skill_name = :skillName AND status = :status ORDER BY timestamp, audit_id")
    fun getBySkillAndStatus(skillName: String, status: String): List<ActionAuditEntity>

    @Query("DELETE FROM action_audit_log")
    fun deleteAll(): Int
}

@Dao
interface ActionIdempotencyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(entity: ActionIdempotencyEntity): Long

    @Query("SELECT * FROM action_idempotency WHERE idempotency_key = :key AND status IN ('IN_FLIGHT', 'SUCCEEDED', 'FAILED', 'REJECTED_BY_GUARD', 'REQUIRES_CONFIRMATION') LIMIT 1")
    fun findByKeyAndStatus(key: String): ActionIdempotencyEntity?

    @Update
    fun update(entity: ActionIdempotencyEntity): Int

    @Query("DELETE FROM action_idempotency")
    fun deleteAll(): Int
}

@Dao
interface ChangeProvenanceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: ChangeProvenanceEntity): Long

    @Query("SELECT * FROM change_provenance_log ORDER BY timestamp, provenance_id")
    fun getAll(): List<ChangeProvenanceEntity>
}

@Dao
interface ChangeAuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: ChangeAuditEntity): Long

    @Query("SELECT * FROM change_audit_log ORDER BY timestamp, change_id")
    fun getAll(): List<ChangeAuditEntity>
}

@Dao
interface ProviderSecretDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: ProviderSecretEntity): Long

    @Query("SELECT * FROM provider_secrets WHERE provider_id = :providerId LIMIT 1")
    fun getByProviderId(providerId: String): ProviderSecretEntity?

    @Query("SELECT provider_id FROM provider_secrets ORDER BY provider_id")
    fun getProviderIds(): List<String>

    @Query("DELETE FROM provider_secrets WHERE provider_id = :providerId")
    fun deleteByProviderId(providerId: String): Int

    @Query("DELETE FROM provider_secrets")
    fun deleteAll(): Int
}
