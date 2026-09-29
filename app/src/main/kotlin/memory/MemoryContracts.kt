package memory

import kotlinx.serialization.Serializable

/**
 * Memory types defined in GEN-MEM-1 Canonical Schema.
 */
enum class MemoryType {
    EPISODIC,
    SEMANTIC,
    PROCEDURAL,
    FAILURE
}

/**
 * Canonical Long-Term Memory record representation (GEN-MEM-1 Patch v1.2)
 * All sensitive text `content` is encrypted at rest via AES-256 (GEN-MEM-6).
 *
 * Fully incorporates canonical fields required by Patch:
 * - retry_after: specific to FAILURE memory type
 * - schema_version: required for all persistent stores in the migration ladder
 * - superseded_by: for semantic updates and quarantine
 * - origin: provenance source (Experience, Procedure, etc.)
 * - guided_by_procedure_id: for procedural audit
 * - last_retrieved_at, last_used_at, successful_use_count: feedback-loop-free decay tracking
 */
@Serializable
data class LongTermMemoryEntry(
    val id: String,
    val memoryType: MemoryType,
    val content: String, // Plaintext in entity; encrypted in storage BLOB/TEXT
    val embedding: List<Float>? = null, // Dimension ~384 (PROVISIONAL - pending GEN-PERF-1)
    val importance: Float = 0.5f,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int = 0,
    val lastRetrievedAt: Long = 0L,
    val lastUsedAt: Long = 0L,
    val successfulUseCount: Int = 0,
    val supersededBy: String? = null,
    val origin: String? = null,
    val guidedByProcedureId: String? = null,
    val retryAfter: Long? = null, // Nullable, specific to FAILURE type
    val schemaVersion: Int = 1     // Canonical schema version
)

/**
 * Session / Working Memory record (in-RAM only, wiped upon session teardown).
 */
@Serializable
data class SessionMemoryEntry(
    val id: String,
    val role: String, // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long
)

/**
 * Candidate score from Brute-force Cosine similarity search (GEN-MEM-2).
 */
data class MemoryRetrievalCandidate(
    val entry: LongTermMemoryEntry,
    val cosineSimilarity: Float
)
