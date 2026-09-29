package memory

import kotlin.math.sqrt

/**
 * GEN-MEM-2: Brute-force Cosine Similarity Math
 * Pure deterministic floating-point vector calculations without any external vector database.
 */
object VectorMath {

    fun cosineSimilarity(v1: List<Float>, v2: List<Float>): Float {
        if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) {
            return 0.0f
        }

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in v1.indices) {
            val a = v1[i]
            val b = v2[i]
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }

        val denominator = sqrt(normA) * sqrt(normB)
        if (denominator == 0.0f) {
            return 0.0f
        }

        val similarity = dotProduct / denominator
        // Clamp to [-1.0, 1.0] to handle float precision drift
        return similarity.coerceIn(-1.0f, 1.0f)
    }
}
