package memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase V Tier 1 Test Suite:
 * - GEN-136: Memory Manager
 * - GEN-MEM-1: Memory Tier Model (Session in-RAM vs Long-Term SQLite Encrypted)
 * - GEN-MEM-2: Embedding & Brute-Force Cosine Similarity Search
 * - GEN-MEM-6: Memory Privacy Policy & Access Control + AES-256 GCM
 * - Canonical Schema v1.2: retry_after, schema_version
 * - Safe Migration Pattern Verification (Rename -> Create -> Insert -> Drop + Checkpoint)
 */
class PhaseVMemoryTierTest {

    private lateinit var memoryManager: MemoryManager
    private lateinit var cipher: AesGcmCipher
    private lateinit var storage: EncryptedMemoryStorage
    private var currentTime: Long = 1000000L

    @Before
    fun setup() {
        currentTime = 1000000L
        cipher = AesGcmCipher()
        storage = EncryptedMemoryStorage(cipher = cipher)
        memoryManager = MemoryManager(storage = storage, clock = { currentTime })
    }

    // 1. GEN-MEM-6 AES-256 Encryption At-Rest Test
    @Test
    fun testAes256EncryptionAndDecryption() {
        val sensitiveData = "User secret medical notes or personal address"
        val cipherText = cipher.encrypt(sensitiveData)

        // Ensure cipher text does not contain plain text
        assertFalse(cipherText.contains("medical"))
        assertFalse(cipherText.contains("personal"))

        val decrypted = cipher.decrypt(cipherText)
        assertEquals(sensitiveData, decrypted)
    }

    // 2. GEN-MEM-6 Access Control & Isolation
    @Test
    fun testAccessControlViolationForUnauthorizedSkill() {
        // Attempting to read or write directly by unauthorized skill must throw SecurityException
        try {
            memoryManager.saveLongTermMemory(
                memoryType = MemoryType.EPISODIC,
                content = "Unauthorized write attempt",
                embedding = listOf(0.1f, 0.2f),
                callerRole = MemoryCallerRole.UNAUTHORIZED_SKILL
            )
            assertTrue("Direct unauthorized write must fail", false)
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Direct memory write strictly prohibited"))
        }

        try {
            memoryManager.getSessionMessages(callerRole = MemoryCallerRole.UNAUTHORIZED_SKILL)
            assertTrue("Direct unauthorized read must fail", false)
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Direct memory read strictly prohibited"))
        }
    }

    // 3. GEN-MEM-1 Session Memory (in-RAM only, wipes upon clear)
    @Test
    fun testSessionMemoryLifecycle() {
        memoryManager.appendSessionMessage("user", "Hello assistant", MemoryCallerRole.USER_INTERACTION)
        memoryManager.appendSessionMessage("assistant", "Hello! How can I help?", MemoryCallerRole.REASONING_PIPELINE)

        val messages = memoryManager.getSessionMessages(MemoryCallerRole.REASONING_PIPELINE)
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)

        // Wiping session memory
        memoryManager.clearSession(MemoryCallerRole.SYSTEM_CORE)
        assertEquals(0, memoryManager.getSessionMessages(MemoryCallerRole.REASONING_PIPELINE).size)
    }

    // 4. GEN-MEM-1 Canonical Long-Term Memory Write & Read with Schema v1.2 fields
    @Test
    fun testLongTermMemoryCanonicalSchemaV12() {
        val entry = memoryManager.saveLongTermMemory(
            memoryType = MemoryType.FAILURE,
            content = "External API rate limited",
            embedding = listOf(0.2f, 0.8f, 0.0f),
            importance = 0.9f,
            origin = "inference_quota_tracker",
            guidedByProcedureId = "proc-fallback-01",
            retryAfter = 60000L,
            schemaVersion = 1,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )

        assertNotNull(entry.id)
        val fetched = memoryManager.getById(entry.id, MemoryCallerRole.SYSTEM_CORE)
        assertNotNull(fetched)
        assertEquals("External API rate limited", fetched!!.content)
        assertEquals(MemoryType.FAILURE, fetched.memoryType)
        assertEquals(60000L, fetched.retryAfter)
        assertEquals(1, fetched.schemaVersion)
        assertEquals("proc-fallback-01", fetched.guidedByProcedureId)
    }

    // 5. GEN-MEM-2 Brute-Force Cosine Similarity Math & Retrieval
    @Test
    fun testBruteForceCosineSimilaritySearch() {
        val vQuery = listOf(1.0f, 0.0f, 0.0f)
        val vDoc1 = listOf(1.0f, 0.0f, 0.0f)
        val vDoc2 = listOf(0.7071f, 0.7071f, 0.0f)
        val vDoc3 = listOf(0.0f, 1.0f, 0.0f)

        assertEquals(1.0f, VectorMath.cosineSimilarity(vQuery, vDoc1), 0.001f)
        assertEquals(0.7071f, VectorMath.cosineSimilarity(vQuery, vDoc2), 0.001f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(vQuery, vDoc3), 0.001f)

        // Insert memories
        memoryManager.saveLongTermMemory(
            memoryType = MemoryType.EPISODIC,
            content = "User asked about flight tickets",
            embedding = vDoc1,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )
        memoryManager.saveLongTermMemory(
            memoryType = MemoryType.EPISODIC,
            content = "User booked a hotel room",
            embedding = vDoc2,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )
        memoryManager.saveLongTermMemory(
            memoryType = MemoryType.SEMANTIC,
            content = "The earth orbits the sun",
            embedding = vDoc3,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )

        // Retrieve top 2
        val candidates = memoryManager.retrieveSimilarCandidates(vQuery, topK = 2, callerRole = MemoryCallerRole.REASONING_PIPELINE)
        assertEquals(2, candidates.size)
        assertEquals("User asked about flight tickets", candidates[0].entry.content)
        assertEquals(1.0f, candidates[0].cosineSimilarity, 0.001f)

        assertEquals("User booked a hotel room", candidates[1].entry.content)
        assertEquals(0.7071f, candidates[1].cosineSimilarity, 0.001f)
    }

    // 6. Memory Storage Ceiling Rejection (PROVISIONAL - 20,000 ceiling)
    @Test
    fun testMemoryCeilingRejection() {
        val boundedManager = MemoryManager(storage = storage, maxRecordCeiling = 2)
        boundedManager.saveLongTermMemory(MemoryType.EPISODIC, "m1", null, callerRole = MemoryCallerRole.SYSTEM_CORE)
        boundedManager.saveLongTermMemory(MemoryType.EPISODIC, "m2", null, callerRole = MemoryCallerRole.SYSTEM_CORE)

        try {
            boundedManager.saveLongTermMemory(MemoryType.EPISODIC, "m3", null, callerRole = MemoryCallerRole.SYSTEM_CORE)
            assertTrue("Exceeding memory record ceiling must throw IllegalStateException", false)
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Memory record ceiling (2) reached"))
        }
    }

    // 7. Safe Migration Pattern: Rename -> Create -> Insert -> Drop + RowCount Checkpoint
    @Test
    fun testSafeDatabaseMigrationPattern() {
        // Populate initial v1 entries
        memoryManager.saveLongTermMemory(
            memoryType = MemoryType.SEMANTIC,
            content = "Fact A",
            embedding = listOf(0.1f),
            schemaVersion = 1,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )
        memoryManager.saveLongTermMemory(
            memoryType = MemoryType.FAILURE,
            content = "Failure B",
            embedding = listOf(0.2f),
            retryAfter = 5000L,
            schemaVersion = 1,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )

        assertEquals(2, memoryManager.getTotalCount(MemoryCallerRole.SYSTEM_CORE))

        // Execute safe migration from v1 to v2
        val report = memoryManager.executeDatabaseMigration(targetVersion = 2, callerRole = MemoryCallerRole.SYSTEM_CORE)

        assertTrue(report.success)
        assertEquals(2, report.migratedRows)
        assertEquals(2, report.targetVersion)

        // Verify entries retain their contents and reflect updated schemaVersion
        val allEntries = storage.getAllActiveEntries()
        assertEquals(2, allEntries.size)
        assertTrue(allEntries.all { it.schemaVersion == 2 })
    }

    // 8. GEN-MEM-3 Unified Retrieval Scoring Formula and Tau_retrieval Verification
    @Test
    fun testUnifiedRetrievalScoringFormula() {
        val weights = UnifiedScoringWeights(
            wRelevance = 0.5f,
            wRecency = 0.25f,
            wImportance = 0.25f,
            tauRetrieval = 1000.0 // 1000 ms time constant
        )
        val scorer = UnifiedRetrievalScorer(weights = weights, clock = { 2000L })

        val recentEntry = LongTermMemoryEntry(
            id = "mem-recent",
            memoryType = MemoryType.SEMANTIC,
            content = "Recent high importance entry",
            importance = 1.0f,
            createdAt = 1000L,
            lastAccessedAt = 2000L // Δt_retrieval = 0 -> recency factor = exp(0) = 1.0
        )
        val oldEntry = LongTermMemoryEntry(
            id = "mem-old",
            memoryType = MemoryType.SEMANTIC,
            content = "Older low importance entry",
            importance = 0.0f,
            createdAt = 500L,
            lastAccessedAt = 1000L // Δt_retrieval = 1000 -> recency factor = exp(-1000/1000) = exp(-1) ~= 0.3678
        )

        val candidateRecent = MemoryRetrievalCandidate(entry = recentEntry, cosineSimilarity = 0.8f)
        val candidateOld = MemoryRetrievalCandidate(entry = oldEntry, cosineSimilarity = 0.8f)

        val scoreRecent = scorer.calculateScore(candidateRecent)
        val scoreOld = scorer.calculateScore(candidateOld)

        // scoreRecent = 0.5 * 0.8 + 0.25 * 1.0 + 0.25 * 1.0 = 0.40 + 0.25 + 0.25 = 0.90
        assertEquals(0.90f, scoreRecent.finalScore, 0.001f)
        assertEquals(0.40f, scoreRecent.relevanceComponent, 0.001f)
        assertEquals(0.25f, scoreRecent.recencyComponent, 0.001f)
        assertEquals(0.25f, scoreRecent.importanceComponent, 0.001f)

        // scoreOld = 0.5 * 0.8 + 0.25 * exp(-1) + 0.25 * 0.0 ~= 0.40 + 0.25 * 0.367879 = 0.49197
        assertTrue(scoreRecent.finalScore > scoreOld.finalScore)
        assertEquals(0.40f, scoreOld.relevanceComponent, 0.001f)
        assertEquals(0.0f, scoreOld.importanceComponent, 0.001f)
    }

    // 9. GEN-MEM-3 Superseded Records Penalty Test
    @Test
    fun testSupersededRecordPenalty() {
        val scorer = UnifiedRetrievalScorer(clock = { 1000L })
        val normalEntry = LongTermMemoryEntry(
            id = "mem-normal",
            memoryType = MemoryType.SEMANTIC,
            content = "Current truth",
            importance = 0.8f,
            createdAt = 1000L,
            lastAccessedAt = 1000L
        )
        val supersededEntry = LongTermMemoryEntry(
            id = "mem-superseded",
            memoryType = MemoryType.SEMANTIC,
            content = "Old outdated truth",
            importance = 0.8f,
            createdAt = 1000L,
            lastAccessedAt = 1000L,
            supersededBy = "mem-normal"
        )

        val normalCandidate = MemoryRetrievalCandidate(normalEntry, 0.9f)
        val supersededCandidate = MemoryRetrievalCandidate(supersededEntry, 0.9f)

        val normalScore = scorer.calculateScore(normalCandidate)
        val supersededScore = scorer.calculateScore(supersededCandidate)

        assertTrue("Superseded record must receive near-zero multiplier penalty", supersededScore.finalScore < normalScore.finalScore * 0.05f)
    }

    // 10. GEN-MEM-4 Context Retrieval Pipeline & Greedy Packing Test
    @Test
    fun testContextRetrievalPipelineAndGreedyPacking() {
        val queryVector = listOf(1.0f, 0.0f, 0.0f)

        // Insert memories with different token lengths and relevance
        memoryManager.saveLongTermMemory(
            memoryType = MemoryType.EPISODIC,
            content = "Short highly relevant memory about Android", // ~10 tokens
            embedding = listOf(1.0f, 0.0f, 0.0f),
            importance = 0.9f,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )
        memoryManager.saveLongTermMemory(
            memoryType = MemoryType.EPISODIC,
            content = "Another relevant memory explaining Jetpack Compose declarative UI architecture", // ~18 tokens
            embedding = listOf(0.9f, 0.1f, 0.0f),
            importance = 0.8f,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )
        memoryManager.saveLongTermMemory(
            memoryType = MemoryType.SEMANTIC,
            content = "Irrelevant fact about astrophysics and distant planetary systems in the outer galaxy",
            embedding = listOf(0.0f, 1.0f, 0.0f),
            importance = 0.1f,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )

        val pipeline = ContextRetrievalPipeline(
            memoryManager = memoryManager,
            tokenEstimator = { text -> text.length / 4 }
        )

        // Pack with a budget of 25 tokens (should fit the top 1 or 2, but exclude what exceeds budget)
        val result = pipeline.retrieveAndPack(
            queryVector = queryVector,
            maxBudgetTokens = 25,
            topKCandidates = 20
        )

        assertTrue(result.packedEntries.isNotEmpty())
        assertTrue("Total tokens used must remain <= maxBudgetTokens (25)", result.totalTokensUsed <= 25)
        assertEquals("Short highly relevant memory about Android", result.packedEntries[0].content)

        // Verify last_used_at was updated on the packed item
        val packedId = result.packedEntries[0].id
        val fetched = memoryManager.getById(packedId, MemoryCallerRole.SYSTEM_CORE)
        assertNotNull(fetched)
        assertTrue(fetched!!.lastUsedAt > 0L)
        assertTrue(fetched.successfulUseCount >= 1)
    }
}
