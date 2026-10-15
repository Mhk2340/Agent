package memory

import persistence.AgentDatabase
import persistence.FloatVectorCodec
import persistence.LongTermMemoryEntity
import java.util.Locale

/**
 * GEN-MEM-1 — production long-term storage backed by the shared Room database.
 * No process-memory map is used. All persisted content and embedding bytes are AES-256-GCM
 * ciphertext, keyed by Android Keystore through the default AesGcmCipher.
 */
class EncryptedMemoryStorage(
    override val database: AgentDatabase,
    private val cipher: AesGcmCipher = AesGcmCipher()
) : MemoryStorage {
    private val dao get() = database.longTermMemoryDao()

    override fun insert(entry: LongTermMemoryEntry) {
        dao.insert(entry.toEntity())
    }

    override fun getById(id: String): LongTermMemoryEntry? = dao.getById(id)?.toDomain()

    override fun getAllActiveEntries(): List<LongTermMemoryEntry> =
        dao.getAllActiveEntries().map { it.toDomain() }

    override fun scanCosineSimilarity(
        queryVector: List<Float>,
        topK: Int
    ): List<MemoryRetrievalCandidate> = getAllActiveEntries()
        .mapNotNull { entry ->
            val vector = entry.embedding ?: return@mapNotNull null
            MemoryRetrievalCandidate(entry, VectorMath.cosineSimilarity(queryVector, vector))
        }
        .sortedByDescending { it.cosineSimilarity }
        .take(topK)

    override fun markEntriesRetrieved(ids: List<String>) {
        if (ids.isNotEmpty()) dao.markRetrieved(ids, System.currentTimeMillis())
    }

    override fun markEntriesUsed(ids: List<String>) {
        if (ids.isNotEmpty()) dao.markUsed(ids, System.currentTimeMillis())
    }

    override fun count(): Int = dao.count()

    override fun clearAll() = dao.deleteAll()

    private fun LongTermMemoryEntry.toEntity(): LongTermMemoryEntity = LongTermMemoryEntity(
        id = id,
        memoryType = memoryType.name.lowercase(Locale.ROOT),
        content = cipher.encrypt(content),
        embedding = embedding?.let(FloatVectorCodec::encode)?.let(cipher::encryptBytes),
        importance = importance,
        createdAt = createdAt,
        lastAccessedAt = lastAccessedAt,
        accessCount = accessCount,
        lastRetrievedAt = lastRetrievedAt,
        lastUsedAt = lastUsedAt,
        successfulUseCount = successfulUseCount,
        supersededBy = supersededBy,
        origin = origin,
        guidedByProcedureId = guidedByProcedureId,
        retryAfter = retryAfter,
        schemaVersion = schemaVersion
    )

    private fun LongTermMemoryEntity.toDomain(): LongTermMemoryEntry = LongTermMemoryEntry(
        id = id,
        memoryType = MemoryType.valueOf(memoryType.uppercase(Locale.ROOT)),
        content = cipher.decrypt(content),
        embedding = embedding?.let(cipher::decryptBytes)?.let(FloatVectorCodec::decode),
        importance = importance,
        createdAt = createdAt,
        lastAccessedAt = lastAccessedAt,
        accessCount = accessCount,
        lastRetrievedAt = lastRetrievedAt,
        lastUsedAt = lastUsedAt,
        successfulUseCount = successfulUseCount,
        supersededBy = supersededBy,
        origin = origin,
        guidedByProcedureId = guidedByProcedureId,
        retryAfter = retryAfter,
        schemaVersion = schemaVersion
    )
}
