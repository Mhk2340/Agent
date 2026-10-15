package memory

/** Storage contract used by MemoryManager; production is the Room-backed EncryptedMemoryStorage. */
interface MemoryStorage {
    val database: persistence.AgentDatabase
    fun insert(entry: LongTermMemoryEntry)
    fun getById(id: String): LongTermMemoryEntry?
    fun getAllActiveEntries(): List<LongTermMemoryEntry>
    fun scanCosineSimilarity(queryVector: List<Float>, topK: Int): List<MemoryRetrievalCandidate>
    fun markEntriesRetrieved(ids: List<String>)
    fun markEntriesUsed(ids: List<String>)
    fun count(): Int
    fun clearAll()
}
