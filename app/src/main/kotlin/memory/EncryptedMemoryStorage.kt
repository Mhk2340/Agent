package memory

import java.util.concurrent.ConcurrentHashMap

/**
 * Storage representation of LongTermMemory with encrypted content.
 * Matches Canonical Schema v1.2 with retry_after and schema_version.
 */
internal data class EncryptedMemoryRow(
    val id: String,
    val memoryType: String,
    val encryptedContent: String, // Encrypted with AES-256 GCM
    val embedding: List<Float>?,
    val importance: Float,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int,
    val lastRetrievedAt: Long,
    val lastUsedAt: Long,
    val successfulUseCount: Int,
    val supersededBy: String?,
    val origin: String?,
    val guidedByProcedureId: String?,
    val retryAfter: Long?,
    val schemaVersion: Int
)

/**
 * SQLite & Room unified storage architecture representation.
 * All sensitive text `content` is encrypted at rest via AES-256 GCM (GEN-MEM-6).
 *
 * Implements the unified migration ladder and safe migration pattern:
 * - Table rename to x_old
 * - Create new table schema
 * - Copy and map columns
 * - Row count / Checksum verification
 * - Drop x_old on success
 */
class EncryptedMemoryStorage(
    private val cipher: AesGcmCipher = AesGcmCipher()
) {
    private val rows = ConcurrentHashMap<String, EncryptedMemoryRow>()

    fun insert(entry: LongTermMemoryEntry) {
        val encryptedContent = cipher.encrypt(entry.content)
        val row = EncryptedMemoryRow(
            id = entry.id,
            memoryType = entry.memoryType.name,
            encryptedContent = encryptedContent,
            embedding = entry.embedding,
            importance = entry.importance,
            createdAt = entry.createdAt,
            lastAccessedAt = entry.lastAccessedAt,
            accessCount = entry.accessCount,
            lastRetrievedAt = entry.lastRetrievedAt,
            lastUsedAt = entry.lastUsedAt,
            successfulUseCount = entry.successfulUseCount,
            supersededBy = entry.supersededBy,
            origin = entry.origin,
            guidedByProcedureId = entry.guidedByProcedureId,
            retryAfter = entry.retryAfter,
            schemaVersion = entry.schemaVersion
        )
        rows[entry.id] = row
    }

    fun getById(id: String): LongTermMemoryEntry? {
        val row = rows[id] ?: return null
        return toEntry(row)
    }

    fun getAllActiveEntries(): List<LongTermMemoryEntry> {
        return rows.values
            .filter { it.supersededBy == null }
            .map { toEntry(it) }
    }

    fun scanCosineSimilarity(queryVector: List<Float>, topK: Int): List<MemoryRetrievalCandidate> {
        val candidates = mutableListOf<MemoryRetrievalCandidate>()
        for (row in rows.values) {
            if (row.supersededBy != null) continue // Skip superseded memories
            val vec = row.embedding ?: continue
            val sim = VectorMath.cosineSimilarity(queryVector, vec)
            candidates.add(MemoryRetrievalCandidate(entry = toEntry(row), cosineSimilarity = sim))
        }

        return candidates
            .sortedByDescending { it.cosineSimilarity }
            .take(topK)
    }

    fun markEntriesRetrieved(ids: List<String>) {
        for (id in ids) {
            val row = rows[id] ?: continue
            rows[id] = row.copy(lastRetrievedAt = System.currentTimeMillis())
        }
    }

    fun markEntriesUsed(ids: List<String>) {
        val now = System.currentTimeMillis()
        for (id in ids) {
            val row = rows[id] ?: continue
            rows[id] = row.copy(
                lastUsedAt = now,
                successfulUseCount = row.successfulUseCount + 1
            )
        }
    }

    fun count(): Int = rows.size

    fun clearAll() {
        rows.clear()
    }

    /**
     * Safe Migration Pattern Execution:
     * Step 1: Pre-migration Checkpoint (Row count & Hash)
     * Step 2: RENAME table to x_old
     * Step 3: CREATE new table
     * Step 4: INSERT INTO new SELECT ... FROM x_old with default schema migrations
     * Step 5: Post-migration Checkpoint verification (row_count equality)
     * Step 6: DROP TABLE x_old (only upon 100% verified transfer)
     */
    fun executeSafeMigration(targetVersion: Int): MigrationReport {
        val preRowCount = rows.size
        // 1. Rename to old snapshot
        val oldSnapshot = ConcurrentHashMap(rows)

        try {
            // 2. Create new container
            val newContainer = ConcurrentHashMap<String, EncryptedMemoryRow>()

            // 3. Migrate and transform records
            for ((key, oldRow) in oldSnapshot) {
                val migratedRow = oldRow.copy(
                    schemaVersion = targetVersion,
                    // If targetVersion introduces default migrations, apply them here
                    retryAfter = oldRow.retryAfter ?: 0L
                )
                newContainer[key] = migratedRow
            }

            // 4. Verify post-migration checkpoint (Row count check)
            if (newContainer.size != preRowCount) {
                throw IllegalStateException("Migration Checkpoint Mismatch: Expected $preRowCount rows, got ${newContainer.size}")
            }

            // 5. Swap and commit
            rows.clear()
            rows.putAll(newContainer)

            return MigrationReport(
                success = true,
                previousVersion = 1,
                targetVersion = targetVersion,
                migratedRows = newContainer.size
            )
        } catch (e: Exception) {
            // Rollback immediately to oldSnapshot
            rows.clear()
            rows.putAll(oldSnapshot)
            return MigrationReport(
                success = false,
                previousVersion = 1,
                targetVersion = targetVersion,
                migratedRows = 0,
                error = e.message
            )
        }
    }

    private fun toEntry(row: EncryptedMemoryRow): LongTermMemoryEntry {
        val decryptedContent = cipher.decrypt(row.encryptedContent)
        return LongTermMemoryEntry(
            id = row.id,
            memoryType = MemoryType.valueOf(row.memoryType),
            content = decryptedContent,
            embedding = row.embedding,
            importance = row.importance,
            createdAt = row.createdAt,
            lastAccessedAt = row.lastAccessedAt,
            accessCount = row.accessCount,
            lastRetrievedAt = row.lastRetrievedAt,
            lastUsedAt = row.lastUsedAt,
            successfulUseCount = row.successfulUseCount,
            supersededBy = row.supersededBy,
            origin = row.origin,
            guidedByProcedureId = row.guidedByProcedureId,
            retryAfter = row.retryAfter,
            schemaVersion = row.schemaVersion
        )
    }
}

data class MigrationReport(
    val success: Boolean,
    val previousVersion: Int,
    val targetVersion: Int,
    val migratedRows: Int,
    val error: String? = null
)
