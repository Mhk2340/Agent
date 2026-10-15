package skills

import safety.RiskLevel
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GEN-NEW-RATE — Action Rate Limiter
 * Enforces per-hour action limits for Level 3 actions (default 5/hour).
 * Prevents runaway loops across turns.
 */
class ActionRateLimiter(
    // PROVISIONAL - pending GEN-PERF-1: default 5 Level-3 actions per hour
    maxLevel3ActionsPerHour: Int = 5,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val configuredMaxLevel3ActionsPerHour: (() -> Int)? = null
) {
    private val seedMaxLevel3ActionsPerHour = maxLevel3ActionsPerHour
    val maxLevel3ActionsPerHour: Int
        get() = (configuredMaxLevel3ActionsPerHour?.invoke() ?: seedMaxLevel3ActionsPerHour).coerceAtLeast(1)

    private val executionTimestamps = ConcurrentLinkedQueue<Long>()

    data class RateLimitCheck(
        val allowed: Boolean,
        val currentCountInWindow: Int,
        val maxAllowed: Int,
        val resetInMs: Long
    )

    fun checkAndRecord(riskLevel: RiskLevel): RateLimitCheck {
        if (riskLevel != RiskLevel.LEVEL_3) {
            return RateLimitCheck(
                allowed = true,
                currentCountInWindow = 0,
                maxAllowed = maxLevel3ActionsPerHour,
                resetInMs = 0L
            )
        }

        pruneOldEntries()
        val currentCount = executionTimestamps.size
        val maxAllowed = maxLevel3ActionsPerHour
        if (currentCount >= maxAllowed) {
            val oldest = executionTimestamps.peek() ?: clock()
            val resetTime = (oldest + 3600_000L) - clock()
            return RateLimitCheck(
                allowed = false,
                currentCountInWindow = currentCount,
                maxAllowed = maxAllowed,
                resetInMs = resetTime.coerceAtLeast(0L)
            )
        }

        executionTimestamps.add(clock())
        return RateLimitCheck(
            allowed = true,
            currentCountInWindow = executionTimestamps.size,
            maxAllowed = maxAllowed,
            resetInMs = 3600_000L
        )
    }

    fun getUsageCount(): Int {
        pruneOldEntries()
        return executionTimestamps.size
    }

    fun clear() {
        executionTimestamps.clear()
    }

    private fun pruneOldEntries() {
        val windowBoundary = clock() - 3600_000L
        while (true) {
            val oldest = executionTimestamps.peek() ?: break
            if (oldest < windowBoundary) {
                executionTimestamps.poll()
            } else {
                break
            }
        }
    }
}
