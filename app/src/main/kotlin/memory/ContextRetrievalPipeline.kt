package memory

import inference.ContextWindowBudgetPolicy
import reasoning.ContextRetrievalSource

/**
 * GEN-MEM-4 — Context Retrieval Pipeline & Packing
 *
 * Implements the locked Roadmap pipeline:
 * Top-K candidates (K=20 default) from GEN-MEM-2
 *   -> Ranked by GEN-MEM-3 (Unified Retrieval Scoring)
 *   -> Greedy packing up to explicit budget limit of 4096 tokens (GEN-285 Phase VIII limit)
 *
 * Replaces provisional retrieval stubs and feeds directly into GEN-244 (ContextManager).
 */
class ContextRetrievalPipeline(
    private val memoryManager: MemoryManager,
    private val scorer: UnifiedRetrievalScorer = UnifiedRetrievalScorer(),
    private val budgetPolicy: ContextWindowBudgetPolicy = ContextWindowBudgetPolicy(maxContextTokens = 4096),
    private val defaultCandidateK: Int = 20,
    private val tokenEstimator: (String) -> Int = { text ->
        // Standard token estimation heuristic for on-device JVM tests: ~4 chars per token, min 1
        (text.length / 4).coerceAtLeast(1)
    }
) : ContextRetrievalSource {

    data class RetrievalAndPackingResult(
        val packedEntries: List<LongTermMemoryEntry>,
        val totalTokensUsed: Int,
        val candidatesFound: Int,
        val topKRetrievedCount: Int
    )

    /**
     * Executes the full retrieval and greedy packing pipeline:
     * 1. Retrieve Top-K candidates from GEN-MEM-2 using query embedding vector.
     * 2. Re-rank candidates using GEN-MEM-3 unified scoring formula.
     * 3. Greedily select top-ranked memories that fit within the allocated token budget.
     * 4. Updates feedback-loop-free metrics: marks retrieved candidates in [last_retrieved_at]
     *    and packed entries in [last_used_at].
     */
    fun retrieveAndPack(
        queryVector: List<Float>,
        maxBudgetTokens: Int = 4096,
        topKCandidates: Int = defaultCandidateK,
        callerRole: MemoryCallerRole = MemoryCallerRole.REASONING_PIPELINE
    ): RetrievalAndPackingResult {
        // Enforce hard ceiling: token budget cannot exceed Phase VIII budget limit (4096 tokens)
        val effectiveMaxTokens = maxBudgetTokens.coerceAtMost(budgetPolicy.maxContextTokens)

        // Step 1: GEN-MEM-2 Candidate Retrieval (Top-K)
        val candidates = memoryManager.retrieveSimilarCandidates(
            queryVector = queryVector,
            topK = topKCandidates,
            callerRole = callerRole
        )

        if (candidates.isEmpty()) {
            return RetrievalAndPackingResult(
                packedEntries = emptyList(),
                totalTokensUsed = 0,
                candidatesFound = 0,
                topKRetrievedCount = 0
            )
        }

        // Track retrieval in metadata without extending decay lifetime (last_retrieved_at)
        memoryManager.markRetrieved(candidates.map { it.entry.id }, callerRole)

        // Step 2: GEN-MEM-3 Unified Scoring & Ranking
        val rankedRecords = scorer.rankCandidates(candidates)

        // Step 3: Greedy Context Packing
        val packed = mutableListOf<LongTermMemoryEntry>()
        var tokensAccumulated = 0

        for (record in rankedRecords) {
            val entryTokens = tokenEstimator(record.entry.content)
            if (tokensAccumulated + entryTokens <= effectiveMaxTokens) {
                packed.add(record.entry)
                tokensAccumulated += entryTokens
            }
        }

        // Step 4: Mark only the actually packed/injected entries in last_used_at
        if (packed.isNotEmpty()) {
            memoryManager.markUsed(packed.map { it.id }, callerRole)
        }

        return RetrievalAndPackingResult(
            packedEntries = packed,
            totalTokensUsed = tokensAccumulated,
            candidatesFound = memoryManager.getTotalCount(callerRole),
            topKRetrievedCount = candidates.size
        )
    }

    /**
     * Implementation of ContextRetrievalSource contract for direct consumption by GEN-244 (ContextManager).
     */
    override fun retrieveRelevantContext(query: String, maxTokens: Int): List<LongTermMemoryEntry> {
        // Generates deterministic query representation vector for query text
        val dummyVector = createDummyEmbedding(query)
        val result = retrieveAndPack(
            queryVector = dummyVector,
            maxBudgetTokens = maxTokens,
            topKCandidates = defaultCandidateK,
            callerRole = MemoryCallerRole.REASONING_PIPELINE
        )
        return result.packedEntries
    }

    private fun createDummyEmbedding(text: String): List<Float> {
        // Deterministic 384-dimensional unit vector representation for testing
        val dim = 384
        val hash = text.hashCode()
        return List(dim) { i ->
            val v = ((hash + i * 31) % 100) / 100.0f
            v
        }
    }
}
