package persistence

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import memory.AesGcmCipher

/** Room migration ladder. Database version 3 adds provider-scoped encrypted credentials. */
object AgentMigrations {
    fun migration1To2(cipher: AesGcmCipher): Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val database = db
            val rowsBefore = rowCount(database, "long_term_memory")

            // The memory table is mutable data, so use the documented loss-safe
            // Rename -> Create -> Insert -> row_count check -> Drop sequence.
            database.execSQL("ALTER TABLE `long_term_memory` RENAME TO `long_term_memory_old`")
            database.execSQL(CREATE_LONG_TERM_MEMORY_V2)
            installMemoryTypeGuards(database)

            database.query(
                "SELECT `id`, `memory_type`, `content`, `embedding`, `importance`, `created_at`, `last_accessed_at`, `access_count` FROM `long_term_memory_old`"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val contentValues = ContentValues().apply {
                        put("id", cursor.getString(0))
                        put("memory_type", cursor.getString(1))
                        // v1 content is already AES-GCM encrypted text; preserve it byte-for-byte.
                        put("content", cursor.getString(2))
                        if (cursor.isNull(3)) {
                            putNull("embedding")
                        } else {
                            // v1 embeddings were raw BLOBs; v2 encrypts them before persistence.
                            put("embedding", cipher.encryptBytes(cursor.getBlob(3)))
                        }
                        put("importance", cursor.getDouble(4))
                        put("created_at", cursor.getLong(5))
                        put("last_accessed_at", cursor.getLong(6))
                        put("access_count", cursor.getInt(7))
                        put("last_retrieved_at", 0L)
                        put("last_used_at", 0L)
                        put("successful_use_count", 0)
                        putNull("superseded_by")
                        putNull("origin")
                        putNull("guided_by_procedure_id")
                        putNull("retry_after")
                        // Legacy rows retain their original row schema version.
                        put("schema_version", 1)
                    }
                    database.insert("long_term_memory", SQLiteDatabase.CONFLICT_ABORT, contentValues)
                }
            }

            createAppendOnlyTables(database)

            val rowsAfter = rowCount(database, "long_term_memory")
            check(rowsBefore == rowsAfter) {
                "Room migration row_count mismatch: before=$rowsBefore after=$rowsAfter; old table retained"
            }

            // Drop the snapshot only after the full copy and checkpoint have succeeded.
            database.execSQL("DROP TABLE `long_term_memory_old`")
        }
    }

    /** Additive provider credential storage; existing v2 rows and tables remain untouched. */
    fun migration2To3(): Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_PROVIDER_SECRETS)
        }
    }

    fun schemaCallback(): RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            installMemoryTypeGuards(db)
        }
    }

    /** Enforces the GEN-MEM-1 enum range because Room does not expose SQL CHECK in @Entity. */
    fun installMemoryTypeGuards(database: SupportSQLiteDatabase) {
        database.execSQL(CREATE_MEMORY_TYPE_INSERT_GUARD)
        database.execSQL(CREATE_MEMORY_TYPE_UPDATE_GUARD)
    }

    private fun rowCount(database: SupportSQLiteDatabase, table: String): Long =
        database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst()) { "Could not read row_count for $table" }
            cursor.getLong(0)
        }

    private fun createAppendOnlyTables(database: SupportSQLiteDatabase) {
        database.execSQL(CREATE_ACTION_AUDIT)
        database.execSQL(CREATE_ACTION_IDEMPOTENCY)
        database.execSQL(CREATE_CHANGE_PROVENANCE)
        database.execSQL(CREATE_CHANGE_AUDIT)
        database.execSQL(CREATE_ACTION_AUDIT_INDEX)
    }

    private const val CREATE_MEMORY_TYPE_INSERT_GUARD = """
        CREATE TRIGGER IF NOT EXISTS `guard_long_term_memory_type_insert`
        BEFORE INSERT ON `long_term_memory`
        WHEN NEW.`memory_type` NOT IN ('episodic', 'semantic', 'procedural', 'failure')
        BEGIN
            SELECT RAISE(ABORT, 'invalid long_term_memory.memory_type');
        END
    """

    private const val CREATE_MEMORY_TYPE_UPDATE_GUARD = """
        CREATE TRIGGER IF NOT EXISTS `guard_long_term_memory_type_update`
        BEFORE UPDATE OF `memory_type` ON `long_term_memory`
        WHEN NEW.`memory_type` NOT IN ('episodic', 'semantic', 'procedural', 'failure')
        BEGIN
            SELECT RAISE(ABORT, 'invalid long_term_memory.memory_type');
        END
    """

    private const val CREATE_LONG_TERM_MEMORY_V2 = """
        CREATE TABLE IF NOT EXISTS `long_term_memory` (
            `id` TEXT NOT NULL,
            `memory_type` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `embedding` BLOB,
            `importance` REAL NOT NULL DEFAULT 0.5,
            `created_at` INTEGER NOT NULL,
            `last_accessed_at` INTEGER NOT NULL,
            `access_count` INTEGER NOT NULL DEFAULT 0,
            `last_retrieved_at` INTEGER NOT NULL DEFAULT 0,
            `last_used_at` INTEGER NOT NULL DEFAULT 0,
            `successful_use_count` INTEGER NOT NULL DEFAULT 0,
            `superseded_by` TEXT,
            `origin` TEXT,
            `guided_by_procedure_id` TEXT,
            `retry_after` INTEGER,
            `schema_version` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`id`)
        )
    """

    private const val CREATE_ACTION_AUDIT = """
        CREATE TABLE IF NOT EXISTS `action_audit_log` (
            `audit_id` TEXT NOT NULL,
            `action_id` TEXT NOT NULL,
            `skill_name` TEXT NOT NULL,
            `risk_level` INTEGER NOT NULL,
            `initiator` TEXT NOT NULL,
            `params_ciphertext` TEXT NOT NULL,
            `confirmation_prompt_ciphertext` TEXT,
            `user_confirmed` INTEGER NOT NULL,
            `status` TEXT NOT NULL,
            `output_ciphertext` TEXT,
            `failure_reason_ciphertext` TEXT,
            `cost_ms` INTEGER NOT NULL,
            `idempotency_key` TEXT,
            `guided_by_procedure_id` TEXT,
            `origin` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `schema_version` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`audit_id`)
        )
    """

    private const val CREATE_ACTION_IDEMPOTENCY = """
        CREATE TABLE IF NOT EXISTS `action_idempotency` (
            `idempotency_key` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            PRIMARY KEY(`idempotency_key`)
        )
    """

    private const val CREATE_CHANGE_PROVENANCE = """
        CREATE TABLE IF NOT EXISTS `change_provenance_log` (
            `provenance_id` TEXT NOT NULL,
            `change_id` TEXT NOT NULL,
            `trigger_signal_id` TEXT NOT NULL,
            `source_experience_id` TEXT,
            `rationale_ciphertext` TEXT NOT NULL,
            `proposed_target` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `schema_version` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`provenance_id`)
        )
    """

    private const val CREATE_CHANGE_AUDIT = """
        CREATE TABLE IF NOT EXISTS `change_audit_log` (
            `change_id` TEXT NOT NULL,
            `target_type` TEXT NOT NULL,
            `target_key` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `reason_ciphertext` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `schema_version` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`change_id`)
        )
    """

    private const val CREATE_PROVIDER_SECRETS = """
        CREATE TABLE IF NOT EXISTS `provider_secrets` (
            `provider_id` TEXT NOT NULL,
            `ciphertext` BLOB NOT NULL,
            PRIMARY KEY(`provider_id`)
        )
    """

    private const val CREATE_ACTION_AUDIT_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_action_audit_log_idempotency_key_status` ON `action_audit_log` (`idempotency_key`, `status`)"
}
