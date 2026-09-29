package memory

import kotlin.math.exp

/**
 * GEN-MEM-3 — Unified Retrieval Scoring
 *
 * Implements the locked formula:
 *   score = w_relevance * cosine_similarity
 *         + w_recency   * exp(-Δt_retrieval / τ_retrieval)
 *         + w_importance * importance_field
 *
 * Time Constant Naming:
 * - The time constant here is strictly named [tauRetrieval] (τ_retrieval), representing
 *   ranking recency decay during search.
 * - This MUST NOT be confused with τ_forget (used in GEN-MEM-5 for expiration/deletion).
 *
 * Adaptive Tuning:
 * - Managed and updated dynamically by GEN-363 (Adaptive Ranking Weight Update).
 */
data class UnifiedScoringWeights(
    val wRelevance: Float = 0.5f,
    val wRecency: Float = 0.25f,
    val wImportance: Float = 0.25f,
    // τ_retrieval (in milliseconds). Default: 7 days = 7 * 24 * 3600 * 1000 ms
    // Note: τ_retrieval is distinct from τ_forget (GEN-MEM-5)
    val tauRetrieval: Double = 7.0 * 24.0 * 3600.0 * 1000.0
) {
    init {
        require(wRelevance in 0.05f..0.90f) { "wRelevance must remain within bounds [0.05, 0.90]" }
        require(wRecency in 0.05f..0.90f) { "wRecency must remain within bounds [0.05, 0.90]" }
        require(wImportance in 0.05f..0.90f) { "wImportance must remain within bounds [0.05, 0.90]" }
    }
}

data class ScoredMemoryRecord(
    val entry: LongTermMemoryEntry,
    val cosineSimilarity: Float,
    val finalScore: Float,
    val relevanceComponent: Float,
    val recencyComponent: Float,
    val importanceComponent: Float
)

class UnifiedRetrievalScorer(
    @Volatile private var weights: UnifiedScoringWeights = UnifiedScoringWeights(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun getCurrentWeights(): UnifiedScoringWeights = weights

    fun updateWeights(newWeights: UnifiedScoringWeights) {
        this.weights = newWeights
    }

    /**
     * Computes the Unified Retrieval Score for a candidate entry.
     *
     * Formula:
     * score = w_relevance * cosine_similarity + w_recency * exp(-Δt_retrieval / τ_retrieval) + w_importance * importance
     */
    fun calculateScore(candidate: MemoryRetrievalCandidate): ScoredMemoryRecord {
        val entry = candidate.entry
        val now = clock()
        val currentWeights = weights

        // 1. Relevance component
        val relevanceComp = currentWeights.wRelevance * candidate.cosineSimilarity

        // 2. Recency component: Δt_retrieval = max(0, now - lastAccessedAt)
        // Time constant is strictly tauRetrieval
        val deltaTRetrieval = (now - entry.lastAccessedAt).coerceAtLeast(0L).toDouble()
        val recencyFactor = exp(-deltaTRetrieval / currentWeights.tauRetrieval).toFloat()
        val recencyComp = currentWeights.wRecency * recencyFactor

        // 3. Importance component
        val importanceComp = currentWeights.wImportance * entry.importance

        // Total unified score
        var totalScore = relevanceComp + recencyComp + importanceComp

        // If record is superseded (Patch v1.2), apply near-zero multiplier to de-prioritize in packing
        if (entry.supersededBy != null) {
            totalScore *= 0.01f
        }

        return ScoredMemoryRecord(
            entry = entry,
            cosineSimilarity = candidate.cosineSimilarity,
            finalScore = totalScore,
            relevanceComponent = relevanceComp,
            recencyComponent = recencyComp,
            importanceComponent = importanceComp
        )
    }

    fun rankCandidates(candidates: List<MemoryRetrievalCandidate>): List<ScoredMemoryRecord> {
        return candidates
            .map { calculateScore(it) }
            .sortedByDescending { it.finalScore }
    }
}
