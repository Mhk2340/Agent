package memory

import runtime.AgentLogger
import runtime.Identity
import security.AppLockAccess
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Access Caller Roles for GEN-MEM-6 Privacy & Access Control Policy.
 */
enum class MemoryCallerRole {
    SYSTEM_CORE,
    REASONING_PIPELINE,
    USER_INTERACTION,
    UNAUTHORIZED_SKILL
}

/**
 * GEN-136 — Memory Manager
 * GEN-MEM-1 — Memory Tier Model (Session in-RAM + Long-Term in SQLite Encrypted)
 * GEN-MEM-2 — Embedding & Brute-force Retrieval
 * GEN-MEM-6 — Memory Privacy Policy & Access Control
 *
 * PROVISIONAL - pending GEN-PERF-1:
 * - Storage record ceiling = 20,000 records
 * - Embedding dimension = 384 floats
 * - Retrieval top-K candidate search = 20 candidates
 */
class MemoryManager(
    private val storage: MemoryStorage,
    private val appLockAccess: AppLockAccess,
    // PROVISIONAL - pending GEN-PERF-1: Storage record ceiling = 20,000 records
    val maxRecordCeiling: Int = 20_000,
    // PROVISIONAL - pending GEN-PERF-1: Candidate top-K default = 20
    val defaultTopK: Int = 20,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    // GEN-MEM-1: Session Memory (in-RAM only, wipes upon teardown)
    private val sessionMemory = CopyOnWriteArrayList<SessionMemoryEntry>()

    /** Shared Room database used by durable memory and append-only audit repositories. */
    internal val database get() = storage.database

    // ------------------------------------------------------------------------
    // GEN-MEM-6: Access Control Policies
    // ------------------------------------------------------------------------
    private fun verifyAccess(callerRole: MemoryCallerRole, isWrite: Boolean) {
        if (callerRole == MemoryCallerRole.UNAUTHORIZED_SKILL) {
            val op = if (isWrite) "write" else "read"
            AgentLogger.e("MemoryManager", "Access violation: Direct memory $op denied for UNAUTHORIZED_SKILL")
            throw SecurityException("Direct memory $op strictly prohibited for unauthorized skills. Access must route through Memory Manager API with authorized role.")
        }
        val unlocked = try {
            appLockAccess.isAppUnlocked()
        } catch (_: Exception) {
            false
        }
        if (!unlocked) throw SecurityException("App Lock is locked; memory access denied")
    }

    // ------------------------------------------------------------------------
    // Session Memory API (Working Memory)
    // ------------------------------------------------------------------------
    fun appendSessionMessage(role: String, content: String, callerRole: MemoryCallerRole) {
        verifyAccess(callerRole, isWrite = true)
        sessionMemory.add(
            SessionMemoryEntry(
                id = Identity.nextId("session"),
                role = role,
                content = content,
                timestamp = clock()
            )
        )
    }

    fun getSessionMessages(callerRole: MemoryCallerRole): List<SessionMemoryEntry> {
        verifyAccess(callerRole, isWrite = false)
        return sessionMemory.toList()
    }

    fun clearSession(callerRole: MemoryCallerRole) {
        verifyAccess(callerRole, isWrite = true)
        sessionMemory.clear()
    }

    /** Internal Panic Reset hook: only the reset coordinator may wipe RAM after locking the app. */
    internal fun clearSessionForPanicReset() {
        sessionMemory.clear()
    }

    // ------------------------------------------------------------------------
    // Long-Term Memory API (GEN-MEM-1 Canonical Write)
    // ------------------------------------------------------------------------
    fun saveLongTermMemory(
        memoryType: MemoryType,
        content: String,
        embedding: List<Float>?,
        importance: Float = 0.5f,
        origin: String? = null,
        guidedByProcedureId: String? = null,
        retryAfter: Long? = null,
        schemaVersion: Int = 1,
        callerRole: MemoryCallerRole
    ): LongTermMemoryEntry {
        verifyAccess(callerRole, isWrite = true)

        if (storage.count() >= maxRecordCeiling) {
            AgentLogger.w("MemoryManager", "Memory storage ceiling ($maxRecordCeiling) reached. Write requires eviction.")
            throw IllegalStateException("Memory record ceiling ($maxRecordCeiling) reached. Trigger GEN-MEM-5 decay/eviction.")
        }

        val now = clock()
        val entry = LongTermMemoryEntry(
            id = Identity.nextId("mem"),
            memoryType = memoryType,
            content = content,
            embedding = embedding,
            importance = importance,
            createdAt = now,
            lastAccessedAt = now,
            accessCount = 0,
            lastRetrievedAt = 0L,
            lastUsedAt = 0L,
            successfulUseCount = 0,
            supersededBy = null,
            origin = origin,
            guidedByProcedureId = guidedByProcedureId,
            retryAfter = retryAfter,
            schemaVersion = schemaVersion
        )

        storage.insert(entry)
        return entry
    }

    fun getById(id: String, callerRole: MemoryCallerRole): LongTermMemoryEntry? {
        verifyAccess(callerRole, isWrite = false)
        return storage.getById(id)
    }

    // ------------------------------------------------------------------------
    // GEN-MEM-2: Brute-Force Cosine Similarity Retrieval
    // ------------------------------------------------------------------------
    fun retrieveSimilarCandidates(
        queryVector: List<Float>,
        topK: Int = defaultTopK,
        callerRole: MemoryCallerRole
    ): List<MemoryRetrievalCandidate> {
        verifyAccess(callerRole, isWrite = false)
        return storage.scanCosineSimilarity(queryVector, topK)
    }

    fun getTotalCount(callerRole: MemoryCallerRole): Int {
        verifyAccess(callerRole, isWrite = false)
        return storage.count()
    }

    fun markRetrieved(ids: List<String>, callerRole: MemoryCallerRole) {
        verifyAccess(callerRole, isWrite = true)
        storage.markEntriesRetrieved(ids)
    }

    fun markUsed(ids: List<String>, callerRole: MemoryCallerRole) {
        verifyAccess(callerRole, isWrite = true)
        storage.markEntriesUsed(ids)
    }

}
