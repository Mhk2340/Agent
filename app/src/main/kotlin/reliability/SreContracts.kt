package reliability

import kotlin.math.sqrt

/**
 * GEN-SRE-1 — Service Level Objectives (SLO) & Service Level Indicators (SLI)
 *
 * Evaluation Windows:
 * Tier 1 (High Volume): Rolling 7-day window, minimum 50 samples.
 * Tier 2 (Low Volume): 30-day window, minimum 10 samples, Wilson Lower Bound at 95% confidence.
 * Tier 3 (Pure Count): Rollback < 2/week, Fallback < 3/day, Rate Limiter rejects < 1/day.
 *
 * PROVISIONAL - pending GEN-PERF-1:
 * Turn Latency p95 threshold of 30,000 ms is provisional pending physical ARM64 profiling.
 */

enum class SliTier {
    TIER_1_HIGH_VOLUME,
    TIER_2_LOW_VOLUME,
    TIER_3_PURE_COUNT
}

sealed interface SliEvent {
    val timestampMs: Long

    data class TurnLatency(
        override val timestampMs: Long,
        val latencyMs: Long
    ) : SliEvent

    data class TurnCompletion(
        override val timestampMs: Long,
        val isCrashFree: Boolean
    ) : SliEvent

    data class BackgroundJob(
        override val timestampMs: Long,
        val isSuccessful: Boolean
    ) : SliEvent

    data class ProviderCall(
        override val timestampMs: Long,
        val isFailed: Boolean
    ) : SliEvent

    data class RollbackCount(
        override val timestampMs: Long
    ) : SliEvent

    data class FallbackCount(
        override val timestampMs: Long
    ) : SliEvent

    data class RateLimiterRejectCount(
        override val timestampMs: Long
    ) : SliEvent
}

data class SloEvaluationResult(
    val sliName: String,
    val tier: SliTier,
    val targetDescription: String,
    val sampleCount: Int,
    val observedValue: Double,
    val thresholdValue: Double,
    val isMet: Boolean,
    val errorBudgetBurnRate: Double, // 1.0 means consuming at exact budget rate; > 5.0 indicates critical fast burn
    val evaluationNotes: String
)

object SloDefinitions {
    // Tier 1 thresholds
    // PROVISIONAL - pending GEN-PERF-1: 30s turn latency threshold is provisional pending physical ARM64 hardware profiling
    const val TIER_1_TURN_LATENCY_P95_MS: Long = 30_000L
    const val TIER_1_CRASH_FREE_RATE: Double = 0.95 // 95%
    const val TIER_1_MIN_SAMPLES: Int = 50
    const val TIER_1_WINDOW_MS: Long = 7L * 24 * 3600 * 1000 // 7 days

    // Tier 2 thresholds
    const val TIER_2_JOB_COMPLETION_RATE: Double = 0.85 // 85%
    const val TIER_2_PROVIDER_FAILURE_RATE: Double = 0.05 // 5%
    const val TIER_2_MIN_SAMPLES: Int = 10
    const val TIER_2_WINDOW_MS: Long = 30L * 24 * 3600 * 1000 // 30 days

    // Tier 3 thresholds
    const val TIER_3_ROLLBACK_MAX_PER_WEEK: Int = 2
    const val TIER_3_FALLBACK_MAX_PER_DAY: Int = 3
    const val TIER_3_RATE_LIMITER_REJECTS_MAX_PER_DAY: Int = 1
    const val FAST_BURN_RATE_THRESHOLD: Double = 5.0

    /**
     * Wilson Score Interval (Lower Bound) at 95% confidence (z = 1.96).
     * Used for low-volume Tier 2 sample evaluation.
     */
    fun calculateWilsonLowerBound(successes: Int, total: Int, z: Double = 1.95996): Double {
        if (total == 0) return 0.0
        val p = successes.toDouble() / total.toDouble()
        val z2 = z * z
        val denominator = 1.0 + z2 / total
        val centre = p + z2 / (2.0 * total)
        val spread = z * sqrt((p * (1.0 - p) + z2 / (4.0 * total)) / total)
        val lowerBound = (centre - spread) / denominator
        return lowerBound.coerceIn(0.0, 1.0)
    }
}
