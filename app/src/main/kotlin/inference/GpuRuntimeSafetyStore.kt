package inference

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class GpuTransientWarning(
    val timestampEpochMs: Long,
    val reason: BenchmarkDiagnosticReason
)

data class GpuTransientWarningDecision(
    val activeWarningCount: Int,
    val warningLimit: Int,
    val lockCreated: Boolean
)

enum class GpuSafetyTransitionKind {
    LOCKED,
    UNLOCKED
}

data class GpuSafetyTransition(
    val kind: GpuSafetyTransitionKind,
    val fingerprintHash: String,
    val previousFingerprintHash: String? = null,
    val reason: BenchmarkDiagnosticReason,
    val triggerReason: BenchmarkDiagnosticReason? = null,
    val timestampEpochMs: Long,
    val lockedAtEpochMs: Long? = null,
    val unlockCondition: GpuUnlockCondition? = null,
    val unlockEligibleAtEpochMs: Long? = null
)

data class GpuRuntimeSafetySnapshot(
    val fingerprintHash: String,
    val status: GpuLockStatus,
    val reason: BenchmarkDiagnosticReason? = null,
    val lockedAtEpochMs: Long? = null,
    val unlockCondition: GpuUnlockCondition? = null,
    val unlockEligibleAtEpochMs: Long? = null,
    val activeTransientWarningCount: Int = 0
)

/** Persistent safety policy for hard GPU errors, rolling warnings, release and canary counts. */
interface GpuRuntimeSafetyStore {
    fun isGpuDisabled(
        fingerprintHash: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean

    /** Returns false if persistent storage failed; the current process still latches GPU off. */
    fun disableGpu(
        fingerprintHash: String,
        reason: BenchmarkDiagnosticReason,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean

    fun recordTransientWarning(
        fingerprintHash: String,
        reason: BenchmarkDiagnosticReason,
        nowEpochMs: Long
    ): GpuTransientWarningDecision

    /** A hard lock is released only by fingerprint change or a valid post-lock fresh profile. */
    fun tryUnlockForFreshProfile(
        fingerprintHash: String,
        result: DeviceBenchmarkResult,
        nowEpochMs: Long
    ): Boolean

    fun safetySnapshot(
        fingerprintHash: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): GpuRuntimeSafetySnapshot

    /** Returns a 1-based ordinal; the counter is durable so process restart does not restart canary. */
    fun nextCanaryOrdinal(
        fingerprintHash: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Int

    /** Safety transitions are consumed by the inference layer and written to diagnostics. */
    fun drainTransitions(): List<GpuSafetyTransition>
}

@Serializable
private data class PersistedGpuWarnings(val warnings: List<GpuTransientWarning> = emptyList())

/**
 * Uses one active fingerprint slot. Fingerprint change resets its lock, warning window and canary;
 * a hard lock can also reopen after a qualifying fresh profile and minimum wait. Hard locks and
 * warning timestamps are durable; persistence failures fail closed in this process.
 */
class AndroidGpuRuntimeSafetyStore(
    private val preferences: SharedPreferences,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) : GpuRuntimeSafetyStore {
    @Volatile private var activeFingerprintHash: String? = null
    @Volatile private var failSafeDisabledFingerprintHash: String? = null
    @Volatile private var memoryLockReason: BenchmarkDiagnosticReason? = null
    @Volatile private var memoryLockedAtEpochMs: Long? = null
    private val pendingTransitions = mutableListOf<GpuSafetyTransition>()
    @Volatile private var inMemoryCanaryCount: Int = 0

    companion object {
        fun forContext(context: Context): AndroidGpuRuntimeSafetyStore = AndroidGpuRuntimeSafetyStore(
            context.applicationContext.getSharedPreferences("gen_edge_1_gpu_safety", Context.MODE_PRIVATE)
        )

        private const val ACTIVE_FINGERPRINT = "active_fingerprint"
        private const val DISABLED = "gpu_disabled"
        private const val DISABLED_REASON = "gpu_disabled_reason"
        private const val DISABLED_AT = "gpu_disabled_at"
        private const val WARNINGS = "gpu_transient_warnings"
        private const val CANARY_FINGERPRINT = "canary_fingerprint"
        private const val CANARY_COUNT = "canary_count"
        private val FINGERPRINT_HASH = Regex("[0-9a-f]{64}")
    }

    @Synchronized
    override fun isGpuDisabled(fingerprintHash: String, nowEpochMs: Long): Boolean {
        if (!FINGERPRINT_HASH.matches(fingerprintHash)) return true
        activateFingerprint(fingerprintHash, nowEpochMs)
        return isLockedFor(fingerprintHash)
    }

    @Synchronized
    override fun disableGpu(
        fingerprintHash: String,
        reason: BenchmarkDiagnosticReason,
        nowEpochMs: Long
    ): Boolean {
        if (!FINGERPRINT_HASH.matches(fingerprintHash)) return false
        activateFingerprint(fingerprintHash, nowEpochMs)
        return lockInternal(fingerprintHash, reason, nowEpochMs, reason)
    }

    @Synchronized
    override fun recordTransientWarning(
        fingerprintHash: String,
        reason: BenchmarkDiagnosticReason,
        nowEpochMs: Long
    ): GpuTransientWarningDecision {
        if (!FINGERPRINT_HASH.matches(fingerprintHash)) {
            return GpuTransientWarningDecision(0, HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_LIMIT, false)
        }
        activateFingerprint(fingerprintHash, nowEpochMs)
        if (isLockedFor(fingerprintHash)) {
            return GpuTransientWarningDecision(
                activeWarnings(nowEpochMs).size,
                HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_LIMIT,
                false
            )
        }

        val nextWarnings = activeWarnings(nowEpochMs) + GpuTransientWarning(nowEpochMs, reason)
        val limit = HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_LIMIT
        if (nextWarnings.size >= limit) {
            val persisted = lockInternal(
                fingerprintHash,
                BenchmarkDiagnosticReason.GPU_TRANSIENT_WARNING_LIMIT,
                nowEpochMs,
                reason
            )
            if (persisted) persistWarnings(emptyList())
            return GpuTransientWarningDecision(nextWarnings.size, limit, true)
        }

        if (!persistWarnings(nextWarnings)) {
            lockInternal(
                fingerprintHash,
                BenchmarkDiagnosticReason.GPU_DISABLE_PERSISTENCE_FAILED,
                nowEpochMs,
                reason
            )
            return GpuTransientWarningDecision(nextWarnings.size, limit, true)
        }
        return GpuTransientWarningDecision(nextWarnings.size, limit, false)
    }

    @Synchronized
    override fun tryUnlockForFreshProfile(
        fingerprintHash: String,
        result: DeviceBenchmarkResult,
        nowEpochMs: Long
    ): Boolean {
        if (!FINGERPRINT_HASH.matches(fingerprintHash) ||
            !result.isFreshValidMeasurement(fingerprintHash, nowEpochMs)
        ) return false

        activateFingerprint(fingerprintHash, nowEpochMs)
        if (!isLockedFor(fingerprintHash)) return false
        val lockedAt = lockTimestamp(fingerprintHash) ?: return false
        val eligibleAt = lockedAt + HardwareBenchmarkAssumptions.GPU_REENABLE_MIN_WAIT_MS
        val measuredAt = result.profile?.measuredAtEpochMs ?: return false
        if (nowEpochMs < eligibleAt || measuredAt < eligibleAt) return false

        val persisted = runCatching {
            preferences.edit()
                .putString(ACTIVE_FINGERPRINT, fingerprintHash)
                .remove(DISABLED)
                .remove(DISABLED_REASON)
                .remove(DISABLED_AT)
                .remove(WARNINGS)
                .putString(CANARY_FINGERPRINT, fingerprintHash)
                .putInt(CANARY_COUNT, 0)
                .commit()
        }.getOrDefault(false)
        if (!persisted) return false

        failSafeDisabledFingerprintHash = null
        memoryLockReason = null
        memoryLockedAtEpochMs = null
        inMemoryCanaryCount = 0
        pendingTransitions += GpuSafetyTransition(
            kind = GpuSafetyTransitionKind.UNLOCKED,
            fingerprintHash = fingerprintHash,
            reason = BenchmarkDiagnosticReason.GPU_UNLOCK_FRESH_PROFILE,
            timestampEpochMs = nowEpochMs,
            lockedAtEpochMs = lockedAt,
            unlockCondition = GpuUnlockCondition.VALID_FRESH_PROFILE_AFTER_WAIT,
            unlockEligibleAtEpochMs = eligibleAt
        )
        return true
    }

    @Synchronized
    override fun safetySnapshot(fingerprintHash: String, nowEpochMs: Long): GpuRuntimeSafetySnapshot {
        if (!FINGERPRINT_HASH.matches(fingerprintHash)) {
            return GpuRuntimeSafetySnapshot(fingerprintHash, GpuLockStatus.DISABLED, BenchmarkDiagnosticReason.INVALID_INPUT)
        }
        activateFingerprint(fingerprintHash, nowEpochMs)
        val locked = isLockedFor(fingerprintHash)
        val warnings = activeWarnings(nowEpochMs).size
        val lockedAt = if (locked) lockTimestamp(fingerprintHash) else null
        return GpuRuntimeSafetySnapshot(
            fingerprintHash = fingerprintHash,
            status = if (locked) GpuLockStatus.DISABLED else GpuLockStatus.ENABLED,
            reason = if (locked) lockReason(fingerprintHash) else null,
            lockedAtEpochMs = lockedAt,
            unlockCondition = if (locked) GpuUnlockCondition.VALID_FRESH_PROFILE_AFTER_WAIT else null,
            unlockEligibleAtEpochMs = lockedAt?.plus(HardwareBenchmarkAssumptions.GPU_REENABLE_MIN_WAIT_MS),
            activeTransientWarningCount = warnings
        )
    }

    @Synchronized
    override fun nextCanaryOrdinal(fingerprintHash: String, nowEpochMs: Long): Int {
        if (!FINGERPRINT_HASH.matches(fingerprintHash)) return Int.MAX_VALUE
        activateFingerprint(fingerprintHash, nowEpochMs)
        val storedCount = runCatching {
            if (preferences.getString(CANARY_FINGERPRINT, null) == fingerprintHash) {
                preferences.getInt(CANARY_COUNT, 0)
            } else 0
        }.getOrDefault(inMemoryCanaryCount)
        val next = (maxOf(storedCount, inMemoryCanaryCount).toLong() + 1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        inMemoryCanaryCount = next
        val persisted = runCatching {
            preferences.edit()
                .putString(ACTIVE_FINGERPRINT, fingerprintHash)
                .putString(CANARY_FINGERPRINT, fingerprintHash)
                .putInt(CANARY_COUNT, next)
                .commit()
        }.getOrDefault(false)
        if (!persisted) {
            lockInternal(
                fingerprintHash,
                BenchmarkDiagnosticReason.GPU_DISABLE_PERSISTENCE_FAILED,
                nowEpochMs,
                BenchmarkDiagnosticReason.GPU_DISABLE_PERSISTENCE_FAILED
            )
        }
        return next
    }

    @Synchronized
    override fun drainTransitions(): List<GpuSafetyTransition> = pendingTransitions.toList().also {
        pendingTransitions.clear()
    }

    private fun activateFingerprint(fingerprintHash: String, nowEpochMs: Long) {
        val persistedActive = runCatching { preferences.getString(ACTIVE_FINGERPRINT, null) }.getOrNull()
        val current = activeFingerprintHash ?: persistedActive
        if (current == fingerprintHash) {
            activeFingerprintHash = fingerprintHash
            return
        }

        val wasLocked = current != null && isLockedFor(current)
        val previousReason = current?.let(::lockReason)
        val previousLockedAt = current?.let(::lockTimestamp)
        activeFingerprintHash = fingerprintHash
        failSafeDisabledFingerprintHash = null
        memoryLockReason = null
        memoryLockedAtEpochMs = null
        inMemoryCanaryCount = 0
        val resetPersisted = runCatching {
            preferences.edit()
                .putString(ACTIVE_FINGERPRINT, fingerprintHash)
                .remove(DISABLED)
                .remove(DISABLED_REASON)
                .remove(DISABLED_AT)
                .remove(WARNINGS)
                .putString(CANARY_FINGERPRINT, fingerprintHash)
                .putInt(CANARY_COUNT, 0)
                .commit()
        }.getOrDefault(false)
        if (wasLocked) {
            pendingTransitions += GpuSafetyTransition(
                kind = GpuSafetyTransitionKind.UNLOCKED,
                fingerprintHash = fingerprintHash,
                previousFingerprintHash = current,
                reason = BenchmarkDiagnosticReason.GPU_UNLOCK_FINGERPRINT_CHANGED,
                triggerReason = previousReason,
                timestampEpochMs = nowEpochMs,
                lockedAtEpochMs = previousLockedAt,
                unlockCondition = GpuUnlockCondition.FINGERPRINT_CHANGED,
                unlockEligibleAtEpochMs = nowEpochMs
            )
        }
        if (!resetPersisted) {
            lockInternal(
                fingerprintHash,
                BenchmarkDiagnosticReason.GPU_DISABLE_PERSISTENCE_FAILED,
                nowEpochMs,
                BenchmarkDiagnosticReason.GPU_DISABLE_PERSISTENCE_FAILED
            )
        }
    }

    private fun lockInternal(
        fingerprintHash: String,
        reason: BenchmarkDiagnosticReason,
        nowEpochMs: Long,
        triggerReason: BenchmarkDiagnosticReason
    ): Boolean {
        if (isLockedFor(fingerprintHash)) return true
        failSafeDisabledFingerprintHash = fingerprintHash
        memoryLockReason = reason
        memoryLockedAtEpochMs = nowEpochMs
        val persisted = runCatching {
            preferences.edit()
                .putString(ACTIVE_FINGERPRINT, fingerprintHash)
                .putBoolean(DISABLED, true)
                .putString(DISABLED_REASON, reason.name)
                .putLong(DISABLED_AT, nowEpochMs)
                .remove(WARNINGS)
                .commit()
        }.getOrDefault(false)
        if (!persisted) {
            memoryLockReason = BenchmarkDiagnosticReason.GPU_DISABLE_PERSISTENCE_FAILED
            runCatching {
                preferences.edit()
                    .putString(ACTIVE_FINGERPRINT, fingerprintHash)
                    .putBoolean(DISABLED, true)
                    .putString(DISABLED_REASON, BenchmarkDiagnosticReason.GPU_DISABLE_PERSISTENCE_FAILED.name)
                    .putLong(DISABLED_AT, nowEpochMs)
                    .commit()
            }
        }
        pendingTransitions += GpuSafetyTransition(
            kind = GpuSafetyTransitionKind.LOCKED,
            fingerprintHash = fingerprintHash,
            reason = if (persisted) reason else BenchmarkDiagnosticReason.GPU_DISABLE_PERSISTENCE_FAILED,
            triggerReason = triggerReason,
            timestampEpochMs = nowEpochMs,
            lockedAtEpochMs = nowEpochMs,
            unlockCondition = GpuUnlockCondition.VALID_FRESH_PROFILE_AFTER_WAIT,
            unlockEligibleAtEpochMs = nowEpochMs + HardwareBenchmarkAssumptions.GPU_REENABLE_MIN_WAIT_MS
        )
        return persisted
    }

    private fun isLockedFor(fingerprintHash: String): Boolean {
        if (failSafeDisabledFingerprintHash == fingerprintHash) return true
        return runCatching { preferences.getBoolean(DISABLED, false) }.getOrDefault(false) &&
            runCatching { preferences.getString(ACTIVE_FINGERPRINT, null) == fingerprintHash }.getOrDefault(false)
    }

    private fun lockReason(fingerprintHash: String): BenchmarkDiagnosticReason? =
        if (failSafeDisabledFingerprintHash == fingerprintHash) memoryLockReason
        else runCatching {
            preferences.getString(ACTIVE_FINGERPRINT, null).takeIf { it == fingerprintHash }
                ?.let { preferences.getString(DISABLED_REASON, null) }
                ?.let(BenchmarkDiagnosticReason::valueOf)
        }.getOrNull()

    private fun lockTimestamp(fingerprintHash: String): Long? =
        if (failSafeDisabledFingerprintHash == fingerprintHash) memoryLockedAtEpochMs
        else runCatching {
            preferences.getString(ACTIVE_FINGERPRINT, null).takeIf { it == fingerprintHash }
                ?.let { preferences.getLong(DISABLED_AT, 0L) }
                ?.takeIf { it > 0L }
        }.getOrNull()

    private fun readWarnings(): List<GpuTransientWarning> = runCatching {
        preferences.getString(WARNINGS, null)?.let {
            json.decodeFromString<PersistedGpuWarnings>(it).warnings
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun activeWarnings(nowEpochMs: Long): List<GpuTransientWarning> {
        val cutoff = nowEpochMs - HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_WINDOW_MS
        return readWarnings().filter { it.timestampEpochMs > cutoff && it.timestampEpochMs <= nowEpochMs }
    }

    /**
     * The synchronous result is required to fail closed when warning-state persistence fails.
     * `apply()` cannot report whether the durable write succeeded.
     */
    @SuppressLint("ApplySharedPref")
    private fun persistWarnings(warnings: List<GpuTransientWarning>): Boolean = runCatching {
        if (warnings.isEmpty()) preferences.edit().remove(WARNINGS).commit()
        else preferences.edit().putString(WARNINGS, json.encodeToString(PersistedGpuWarnings(warnings))).commit()
    }.getOrDefault(false)
}

/** Test seam only. Production wiring uses [AndroidGpuRuntimeSafetyStore]. */
class InMemoryGpuRuntimeSafetyStore : GpuRuntimeSafetyStore {
    private var activeFingerprintHash: String? = null
    private var disabledFingerprintHash: String? = null
    private var disabledReason: BenchmarkDiagnosticReason? = null
    private var disabledAtEpochMs: Long? = null
    private var canaryCount = 0
    private var warnings = emptyList<GpuTransientWarning>()
    private val pendingTransitions = mutableListOf<GpuSafetyTransition>()

    @Synchronized
    override fun isGpuDisabled(fingerprintHash: String, nowEpochMs: Long): Boolean {
        activateFingerprint(fingerprintHash, nowEpochMs)
        return disabledFingerprintHash == fingerprintHash
    }

    @Synchronized
    override fun disableGpu(
        fingerprintHash: String,
        reason: BenchmarkDiagnosticReason,
        nowEpochMs: Long
    ): Boolean {
        activateFingerprint(fingerprintHash, nowEpochMs)
        return lockInternal(fingerprintHash, reason, nowEpochMs, reason)
    }

    @Synchronized
    override fun recordTransientWarning(
        fingerprintHash: String,
        reason: BenchmarkDiagnosticReason,
        nowEpochMs: Long
    ): GpuTransientWarningDecision {
        activateFingerprint(fingerprintHash, nowEpochMs)
        val cutoff = nowEpochMs - HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_WINDOW_MS
        warnings = warnings.filter { it.timestampEpochMs > cutoff && it.timestampEpochMs <= nowEpochMs }
        warnings = warnings + GpuTransientWarning(nowEpochMs, reason)
        val limit = HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_LIMIT
        val locked = if (warnings.size >= limit) {
            lockInternal(fingerprintHash, BenchmarkDiagnosticReason.GPU_TRANSIENT_WARNING_LIMIT, nowEpochMs, reason)
            warnings = emptyList()
            true
        } else false
        return GpuTransientWarningDecision(if (locked) limit else warnings.size, limit, locked)
    }

    @Synchronized
    override fun tryUnlockForFreshProfile(
        fingerprintHash: String,
        result: DeviceBenchmarkResult,
        nowEpochMs: Long
    ): Boolean {
        activateFingerprint(fingerprintHash, nowEpochMs)
        if (disabledFingerprintHash != fingerprintHash || !result.isFreshValidMeasurement(fingerprintHash, nowEpochMs)) return false
        val lockAt = disabledAtEpochMs ?: return false
        val eligibleAt = lockAt + HardwareBenchmarkAssumptions.GPU_REENABLE_MIN_WAIT_MS
        val measuredAt = result.profile?.measuredAtEpochMs ?: return false
        if (nowEpochMs < eligibleAt || measuredAt < eligibleAt) return false
        val reason = disabledReason
        disabledFingerprintHash = null
        disabledReason = null
        disabledAtEpochMs = null
        warnings = emptyList()
        canaryCount = 0
        pendingTransitions += GpuSafetyTransition(
            GpuSafetyTransitionKind.UNLOCKED,
            fingerprintHash,
            reason = BenchmarkDiagnosticReason.GPU_UNLOCK_FRESH_PROFILE,
            triggerReason = reason,
            timestampEpochMs = nowEpochMs,
            lockedAtEpochMs = lockAt,
            unlockCondition = GpuUnlockCondition.VALID_FRESH_PROFILE_AFTER_WAIT,
            unlockEligibleAtEpochMs = eligibleAt
        )
        return true
    }

    @Synchronized
    override fun safetySnapshot(fingerprintHash: String, nowEpochMs: Long): GpuRuntimeSafetySnapshot {
        activateFingerprint(fingerprintHash, nowEpochMs)
        val disabled = disabledFingerprintHash == fingerprintHash
        val lockAt = if (disabled) disabledAtEpochMs else null
        val cutoff = nowEpochMs - HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_WINDOW_MS
        warnings = warnings.filter { it.timestampEpochMs > cutoff && it.timestampEpochMs <= nowEpochMs }
        return GpuRuntimeSafetySnapshot(
            fingerprintHash,
            if (disabled) GpuLockStatus.DISABLED else GpuLockStatus.ENABLED,
            if (disabled) disabledReason else null,
            lockAt,
            if (disabled) GpuUnlockCondition.VALID_FRESH_PROFILE_AFTER_WAIT else null,
            lockAt?.plus(HardwareBenchmarkAssumptions.GPU_REENABLE_MIN_WAIT_MS),
            warnings.size
        )
    }

    @Synchronized
    override fun nextCanaryOrdinal(fingerprintHash: String, nowEpochMs: Long): Int {
        activateFingerprint(fingerprintHash, nowEpochMs)
        canaryCount = (canaryCount + 1).coerceAtMost(Int.MAX_VALUE)
        return canaryCount
    }

    @Synchronized
    override fun drainTransitions(): List<GpuSafetyTransition> = pendingTransitions.toList().also {
        pendingTransitions.clear()
    }

    private fun activateFingerprint(fingerprintHash: String, nowEpochMs: Long) {
        if (activeFingerprintHash == fingerprintHash) return
        val previous = activeFingerprintHash
        val previousLocked = disabledFingerprintHash == previous
        val reason = disabledReason
        val lockedAt = disabledAtEpochMs
        activeFingerprintHash = fingerprintHash
        disabledFingerprintHash = null
        disabledReason = null
        disabledAtEpochMs = null
        canaryCount = 0
        warnings = emptyList()
        if (previousLocked && previous != null) {
            pendingTransitions += GpuSafetyTransition(
                GpuSafetyTransitionKind.UNLOCKED,
                fingerprintHash,
                previous,
                BenchmarkDiagnosticReason.GPU_UNLOCK_FINGERPRINT_CHANGED,
                reason,
                nowEpochMs,
                lockedAt,
                GpuUnlockCondition.FINGERPRINT_CHANGED,
                nowEpochMs
            )
        }
    }

    private fun lockInternal(
        fingerprintHash: String,
        reason: BenchmarkDiagnosticReason,
        nowEpochMs: Long,
        triggerReason: BenchmarkDiagnosticReason
    ): Boolean {
        if (disabledFingerprintHash == fingerprintHash) return true
        disabledFingerprintHash = fingerprintHash
        disabledReason = reason
        disabledAtEpochMs = nowEpochMs
        pendingTransitions += GpuSafetyTransition(
            GpuSafetyTransitionKind.LOCKED,
            fingerprintHash,
            reason = reason,
            triggerReason = triggerReason,
            timestampEpochMs = nowEpochMs,
            lockedAtEpochMs = nowEpochMs,
            unlockCondition = GpuUnlockCondition.VALID_FRESH_PROFILE_AFTER_WAIT,
            unlockEligibleAtEpochMs = nowEpochMs + HardwareBenchmarkAssumptions.GPU_REENABLE_MIN_WAIT_MS
        )
        return true
    }
}
