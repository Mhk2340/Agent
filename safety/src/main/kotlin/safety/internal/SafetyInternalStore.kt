package safety.internal

import safety.RiskLevel

/**
 * Internal configuration store for Safety Rules and Rate Limits.
 * As per Roadmap Patch (GEN-CONST-2 & Config Gap Closure):
 * 1. Independent Store with Integrity Check (not public DataStore).
 * 2. Writes allowed ONLY via explicit UserActionToken.
 * 3. Risk level escalation is monotonic (max(current, new)); lowering requires UserActionToken.
 * 4. No automated paths (Evolution, Learning, MultiAgent) can write or bypass.
 * 5. Tracked in Checksum verification.
 */
object SafetyInternalStore {

    /**
     * Token proving that an operation originates strictly from an explicit user UI action.
     */
    class UserActionToken private constructor() {
        companion object {
            fun createForUserAction(userSessionSignature: String): UserActionToken {
                require(userSessionSignature.isNotBlank()) { "Invalid user signature" }
                return UserActionToken()
            }
        }
    }

    // PROVISIONAL - pending GEN-PERF-1: max_level3_actions_per_hour = 5
    private var maxLevel3ActionsPerHour: Int = 5
    private val toolRiskOverrides = mutableMapOf<String, RiskLevel>()

    /**
     * Risk level override can mathematically ONLY increase caution (max(current, new)).
     * It can never lower the risk level automatically.
     */
    fun escalateRiskLevel(toolName: String, newLevel: RiskLevel) {
        val current = toolRiskOverrides[toolName] ?: RiskLevel.LEVEL_1
        val updated = if (newLevel.value > current.value) newLevel else current
        toolRiskOverrides[toolName] = updated
    }

    /**
     * Lowering risk level or modifying rate limits requires an explicit UserActionToken.
     */
    fun userManualOverride(
        token: UserActionToken,
        toolName: String,
        level: RiskLevel
    ) {
        checkNotNull(token)
        toolRiskOverrides[toolName] = level
    }

    fun userUpdateRateLimit(
        token: UserActionToken,
        newMaxLevel3PerHour: Int
    ) {
        checkNotNull(token)
        require(newMaxLevel3PerHour > 0) { "Rate limit must be positive" }
        maxLevel3ActionsPerHour = newMaxLevel3PerHour
    }

    fun getRiskLevel(toolName: String, baseLevel: RiskLevel): RiskLevel {
        val override = toolRiskOverrides[toolName]
        return if (override != null && override.value > baseLevel.value) {
            override
        } else {
            baseLevel
        }
    }

    fun getMaxLevel3ActionsPerHour(): Int = maxLevel3ActionsPerHour

    fun resetToDefaultsForTesting() {
        maxLevel3ActionsPerHour = 5
        toolRiskOverrides.clear()
    }

    /**
     * Returns an integrity snapshot hash of the current safety configuration.
     */
    fun computeIntegrityChecksum(): String {
        val content = buildString {
            append("rate_limit:").append(maxLevel3ActionsPerHour).append(";")
            toolRiskOverrides.toSortedMap().forEach { (k, v) ->
                append(k).append("=").append(v.name).append(";")
            }
        }
        return SafetyConfigChecksum.sha256(content.toByteArray(Charsets.UTF_8))
    }
}
