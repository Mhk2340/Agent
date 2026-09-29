package reliability

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GEN-422 — System Monitoring & SLI Metric Collector
 * Aggregates operational telemetry from Phase V (Execution), Phase VII (Memory),
 * Phase VIII (Skills), Phase IX (Multi-Agent/Tools), and Phase XIII (Network/Offline).
 */
interface SliEventSink {
    fun recordEvent(event: SliEvent)
    fun getEvents(sinceTimestampMs: Long): List<SliEvent>
    fun clear()
}

class InMemorySliEventSink : SliEventSink {
    private val events = ConcurrentLinkedQueue<SliEvent>()

    override fun recordEvent(event: SliEvent) {
        events.add(event)
    }

    override fun getEvents(sinceTimestampMs: Long): List<SliEvent> {
        return events.filter { it.timestampMs >= sinceTimestampMs }
    }

    override fun clear() {
        events.clear()
    }
}

/**
 * GEN-423 & GEN-SRE-2 — Health Aggregator & Single-User Dashboard Model
 * Computes SLO status across all 3 tiers and formats a privacy-safe single-user health view.
 */
enum class SystemHealthStatus {
    HEALTHY,
    DEGRADED,
    CRITICAL
}

data class SingleUserHealthDashboard(
    val overallStatus: SystemHealthStatus,
    val currentMode: OperatingMode,
    val activeAnomalies: List<String>,
    val tier1LatencyP95Ms: Long,
    val tier1CrashFreeRate: Double,
    val tier2JobSuccessRate: Double,
    val tier2ProviderFailureRate: Double,
    val tier3RollbackCountPastWeek: Int,
    val tier3FallbackCountPastDay: Int,
    val tier3RateLimiterRejectsPastDay: Int,
    val isErrorBudgetExhausted: Boolean,
    val evaluationTimestampMs: Long
)

class HealthAggregator(
    private val eventSink: SliEventSink,
    private val modeManager: AgentOperatingModeManager
) {

    fun evaluateSloStatus(currentTimeMs: Long): List<SloEvaluationResult> {
        val results = mutableListOf<SloEvaluationResult>()

        // 1. Tier 1: Turn Latency p95 (< 30s)
        val t1WindowEvents = eventSink.getEvents(currentTimeMs - SloDefinitions.TIER_1_WINDOW_MS)
        val latencies = t1WindowEvents.filterIsInstance<SliEvent.TurnLatency>().map { it.latencyMs }.sorted()
        if (latencies.size >= SloDefinitions.TIER_1_MIN_SAMPLES) {
            val p95Index = (latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)
            val p95 = latencies[p95Index]
            val met = p95 <= SloDefinitions.TIER_1_TURN_LATENCY_P95_MS
            val burnRate = if (p95 > SloDefinitions.TIER_1_TURN_LATENCY_P95_MS) {
                (p95.toDouble() / SloDefinitions.TIER_1_TURN_LATENCY_P95_MS) * 2.0
            } else 0.5
            results.add(
                SloEvaluationResult(
                    sliName = "TurnLatencyP95",
                    tier = SliTier.TIER_1_HIGH_VOLUME,
                    targetDescription = "Turn Latency p95 <= 30000ms",
                    sampleCount = latencies.size,
                    observedValue = p95.toDouble(),
                    thresholdValue = SloDefinitions.TIER_1_TURN_LATENCY_P95_MS.toDouble(),
                    isMet = met,
                    errorBudgetBurnRate = burnRate,
                    evaluationNotes = "p95 latency observed: ${p95}ms across ${latencies.size} turns"
                )
            )
        } else {
            results.add(
                SloEvaluationResult(
                    sliName = "TurnLatencyP95",
                    tier = SliTier.TIER_1_HIGH_VOLUME,
                    targetDescription = "Turn Latency p95 <= 30000ms",
                    sampleCount = latencies.size,
                    observedValue = latencies.lastOrNull()?.toDouble() ?: 0.0,
                    thresholdValue = SloDefinitions.TIER_1_TURN_LATENCY_P95_MS.toDouble(),
                    isMet = true, // Insufficient samples
                    errorBudgetBurnRate = 0.0,
                    evaluationNotes = "Insufficient samples (${latencies.size}/${SloDefinitions.TIER_1_MIN_SAMPLES})"
                )
            )
        }

        // 2. Tier 1: Crash-free rate (> 95%)
        val turnCompletions = t1WindowEvents.filterIsInstance<SliEvent.TurnCompletion>()
        if (turnCompletions.size >= SloDefinitions.TIER_1_MIN_SAMPLES) {
            val crashFreeCount = turnCompletions.count { it.isCrashFree }
            val rate = crashFreeCount.toDouble() / turnCompletions.size.toDouble()
            val met = rate >= SloDefinitions.TIER_1_CRASH_FREE_RATE
            val allowedUnreliability = 1.0 - SloDefinitions.TIER_1_CRASH_FREE_RATE
            val actualUnreliability = 1.0 - rate
            val burnRate = if (actualUnreliability > 0) actualUnreliability / allowedUnreliability else 0.0
            results.add(
                SloEvaluationResult(
                    sliName = "CrashFreeTurnRate",
                    tier = SliTier.TIER_1_HIGH_VOLUME,
                    targetDescription = "Crash-free turns >= 95%",
                    sampleCount = turnCompletions.size,
                    observedValue = rate,
                    thresholdValue = SloDefinitions.TIER_1_CRASH_FREE_RATE,
                    isMet = met,
                    errorBudgetBurnRate = burnRate,
                    evaluationNotes = "Crash-free rate: ${(rate * 100).toInt()}% ($crashFreeCount/${turnCompletions.size})"
                )
            )
        } else {
            results.add(
                SloEvaluationResult(
                    sliName = "CrashFreeTurnRate",
                    tier = SliTier.TIER_1_HIGH_VOLUME,
                    targetDescription = "Crash-free turns >= 95%",
                    sampleCount = turnCompletions.size,
                    observedValue = 1.0,
                    thresholdValue = SloDefinitions.TIER_1_CRASH_FREE_RATE,
                    isMet = true,
                    errorBudgetBurnRate = 0.0,
                    evaluationNotes = "Insufficient samples (${turnCompletions.size}/${SloDefinitions.TIER_1_MIN_SAMPLES})"
                )
            )
        }

        // 3. Tier 2: Job completion rate (> 85% via Wilson Lower Bound)
        val t2WindowEvents = eventSink.getEvents(currentTimeMs - SloDefinitions.TIER_2_WINDOW_MS)
        val bgJobs = t2WindowEvents.filterIsInstance<SliEvent.BackgroundJob>()
        if (bgJobs.size >= SloDefinitions.TIER_2_MIN_SAMPLES) {
            val successCount = bgJobs.count { it.isSuccessful }
            val wilsonLowerBound = SloDefinitions.calculateWilsonLowerBound(successCount, bgJobs.size)
            val met = wilsonLowerBound >= SloDefinitions.TIER_2_JOB_COMPLETION_RATE
            val burnRate = if (!met) 2.5 else 0.5
            results.add(
                SloEvaluationResult(
                    sliName = "BackgroundJobCompletionRate",
                    tier = SliTier.TIER_2_LOW_VOLUME,
                    targetDescription = "Job completion >= 85% (Wilson 95% LB)",
                    sampleCount = bgJobs.size,
                    observedValue = wilsonLowerBound,
                    thresholdValue = SloDefinitions.TIER_2_JOB_COMPLETION_RATE,
                    isMet = met,
                    errorBudgetBurnRate = burnRate,
                    evaluationNotes = "Wilson lower bound: ${(wilsonLowerBound * 100).toInt()}% ($successCount/${bgJobs.size})"
                )
            )
        }

        // 4. Tier 2: Provider failure rate (< 5%)
        val providerCalls = t2WindowEvents.filterIsInstance<SliEvent.ProviderCall>()
        if (providerCalls.size >= SloDefinitions.TIER_2_MIN_SAMPLES) {
            val failureCount = providerCalls.count { it.isFailed }
            val failureRate = failureCount.toDouble() / providerCalls.size.toDouble()
            val met = failureRate <= SloDefinitions.TIER_2_PROVIDER_FAILURE_RATE
            val burnRate = if (!met) failureRate / SloDefinitions.TIER_2_PROVIDER_FAILURE_RATE else 0.2
            results.add(
                SloEvaluationResult(
                    sliName = "ProviderFailureRate",
                    tier = SliTier.TIER_2_LOW_VOLUME,
                    targetDescription = "Provider failure <= 5%",
                    sampleCount = providerCalls.size,
                    observedValue = failureRate,
                    thresholdValue = SloDefinitions.TIER_2_PROVIDER_FAILURE_RATE,
                    isMet = met,
                    errorBudgetBurnRate = burnRate,
                    evaluationNotes = "Provider failure rate: ${(failureRate * 100).toInt()}% ($failureCount/${providerCalls.size})"
                )
            )
        }

        // 5. Tier 3: Rollback (< 2/week)
        val weekAgo = currentTimeMs - (7L * 24 * 3600 * 1000)
        val rollbacksPastWeek = eventSink.getEvents(weekAgo).filterIsInstance<SliEvent.RollbackCount>().size
        val rollbackMet = rollbacksPastWeek < SloDefinitions.TIER_3_ROLLBACK_MAX_PER_WEEK
        val rollbackBurn = if (rollbacksPastWeek > 0) rollbacksPastWeek.toDouble() / SloDefinitions.TIER_3_ROLLBACK_MAX_PER_WEEK else 0.0
        results.add(
            SloEvaluationResult(
                sliName = "RollbackCount",
                tier = SliTier.TIER_3_PURE_COUNT,
                targetDescription = "Rollbacks < 2 per week",
                sampleCount = rollbacksPastWeek,
                observedValue = rollbacksPastWeek.toDouble(),
                thresholdValue = SloDefinitions.TIER_3_ROLLBACK_MAX_PER_WEEK.toDouble(),
                isMet = rollbackMet,
                errorBudgetBurnRate = rollbackBurn,
                evaluationNotes = "$rollbacksPastWeek rollbacks observed past week"
            )
        )

        // 6. Tier 3: Fallback (< 3/day)
        val dayAgo = currentTimeMs - (24L * 3600 * 1000)
        val dayEvents = eventSink.getEvents(dayAgo)
        val fallbacksPastDay = dayEvents.filterIsInstance<SliEvent.FallbackCount>().size
        val fallbackMet = fallbacksPastDay < SloDefinitions.TIER_3_FALLBACK_MAX_PER_DAY
        val fallbackBurn = if (fallbacksPastDay > 0) fallbacksPastDay.toDouble() / SloDefinitions.TIER_3_FALLBACK_MAX_PER_DAY else 0.0
        results.add(
            SloEvaluationResult(
                sliName = "FallbackCount",
                tier = SliTier.TIER_3_PURE_COUNT,
                targetDescription = "Fallbacks < 3 per day",
                sampleCount = fallbacksPastDay,
                observedValue = fallbacksPastDay.toDouble(),
                thresholdValue = SloDefinitions.TIER_3_FALLBACK_MAX_PER_DAY.toDouble(),
                isMet = fallbackMet,
                errorBudgetBurnRate = fallbackBurn,
                evaluationNotes = "$fallbacksPastDay fallbacks observed past 24h"
            )
        )

        // 7. Tier 3: Rate Limiter rejects (< 1/day)
        val rejectsPastDay = dayEvents.filterIsInstance<SliEvent.RateLimiterRejectCount>().size
        val rejectMet = rejectsPastDay < SloDefinitions.TIER_3_RATE_LIMITER_REJECTS_MAX_PER_DAY
        val rejectBurn = if (rejectsPastDay > 0) rejectsPastDay.toDouble() / SloDefinitions.TIER_3_RATE_LIMITER_REJECTS_MAX_PER_DAY else 0.0
        results.add(
            SloEvaluationResult(
                sliName = "RateLimiterRejectCount",
                tier = SliTier.TIER_3_PURE_COUNT,
                targetDescription = "Rate Limiter rejects < 1 per day",
                sampleCount = rejectsPastDay,
                observedValue = rejectsPastDay.toDouble(),
                thresholdValue = SloDefinitions.TIER_3_RATE_LIMITER_REJECTS_MAX_PER_DAY.toDouble(),
                isMet = rejectMet,
                errorBudgetBurnRate = rejectBurn,
                evaluationNotes = "$rejectsPastDay rejects observed past 24h"
            )
        )

        return results
    }

    fun buildDashboard(currentTimeMs: Long): SingleUserHealthDashboard {
        val evaluations = evaluateSloStatus(currentTimeMs)
        val unmetEvaluations = evaluations.filter { !it.isMet }
        val fastBurnEvaluations = evaluations.filter { it.errorBudgetBurnRate >= SloDefinitions.FAST_BURN_RATE_THRESHOLD }

        val status = when {
            unmetEvaluations.isNotEmpty() || fastBurnEvaluations.isNotEmpty() -> SystemHealthStatus.CRITICAL
            evaluations.any { it.errorBudgetBurnRate > 1.0 } -> SystemHealthStatus.DEGRADED
            else -> SystemHealthStatus.HEALTHY
        }

        val anomalies = mutableListOf<String>()
        for (unmet in unmetEvaluations) {
            anomalies.add("SLO Breach: ${unmet.sliName} (${unmet.evaluationNotes})")
        }
        for (burn in fastBurnEvaluations) {
            anomalies.add("Fast Burn Alert: ${burn.sliName} burn rate is ${burn.errorBudgetBurnRate}x")
        }

        val t1Latency = evaluations.firstOrNull { it.sliName == "TurnLatencyP95" }?.observedValue?.toLong() ?: 0L
        val t1CrashFree = evaluations.firstOrNull { it.sliName == "CrashFreeTurnRate" }?.observedValue ?: 1.0
        val t2JobSuccess = evaluations.firstOrNull { it.sliName == "BackgroundJobCompletionRate" }?.observedValue ?: 1.0
        val t2ProviderFail = evaluations.firstOrNull { it.sliName == "ProviderFailureRate" }?.observedValue ?: 0.0
        val t3Rollback = evaluations.firstOrNull { it.sliName == "RollbackCount" }?.observedValue?.toInt() ?: 0
        val t3Fallback = evaluations.firstOrNull { it.sliName == "FallbackCount" }?.observedValue?.toInt() ?: 0
        val t3Rejects = evaluations.firstOrNull { it.sliName == "RateLimiterRejectCount" }?.observedValue?.toInt() ?: 0

        val errorBudgetExhausted = unmetEvaluations.isNotEmpty()

        return SingleUserHealthDashboard(
            overallStatus = status,
            currentMode = modeManager.getCurrentMode(),
            activeAnomalies = anomalies,
            tier1LatencyP95Ms = t1Latency,
            tier1CrashFreeRate = t1CrashFree,
            tier2JobSuccessRate = t2JobSuccess,
            tier2ProviderFailureRate = t2ProviderFail,
            tier3RollbackCountPastWeek = t3Rollback,
            tier3FallbackCountPastDay = t3Fallback,
            tier3RateLimiterRejectsPastDay = t3Rejects,
            isErrorBudgetExhausted = errorBudgetExhausted,
            evaluationTimestampMs = currentTimeMs
        )
    }
}
