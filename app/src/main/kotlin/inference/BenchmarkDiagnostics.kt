package inference

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class BenchmarkDiagnosticType {
    WORK_ENQUEUED,
    WORKER_EXECUTION,
    CANARY,
    INFERENCE_BACKEND_SELECTED,
    GPU_FALLBACK,
    GPU_WARNING,
    GPU_LOCKED,
    GPU_UNLOCKED,
    BENCHMARK_STARVED
}

@Serializable
enum class BenchmarkDiagnosticResult {
    ENQUEUED,
    BACKEND_SELECTED,
    RETRY_SCHEDULED,
    MEASURED,
    NOT_MEASURED,
    MODEL_UNAVAILABLE,
    THERMAL_HIGH,
    UNSTABLE,
    STOPPED_BY_SYSTEM,
    TIMEOUT,
    CANARY_PASSED,
    GPU_DISABLED,
    CPU_FALLBACK,
    TRANSIENT_WARNING,
    GPU_UNLOCKED,
    BENCHMARK_STARVED
}

@Serializable
enum class BenchmarkDiagnosticReason {
    NONE,
    INVALID_INPUT,
    STALE_FINGERPRINT,
    EXISTING_RESULT,
    NOT_CHARGING,
    BATTERY_UNKNOWN,
    BATTERY_LOW,
    IDLE_NOT_CONFIRMED,
    THERMAL_HIGH,
    THERMAL_UNKNOWN,
    MODEL_UNAVAILABLE,
    ADAPTER_NOT_WIRED,
    PROFILE_INVALID,
    UNSTABLE,
    GPU_BACKEND_ERROR,
    MEMORY_PRESSURE,
    CANARY_LATENCY,
    CANARY_TELEMETRY_MISSING,
    GPU_TRANSIENT_WARNING_LIMIT,
    GPU_UNLOCK_FINGERPRINT_CHANGED,
    GPU_UNLOCK_FRESH_PROFILE,
    SESSION_GPU_FALLBACK,
    GPU_DISABLED_FOR_FINGERPRINT,
    GPU_DISABLE_PERSISTENCE_FAILED,
    OPERATING_MODE_OVERRIDE,
    NO_VALID_PROFILE,
    PROFILE_EXPIRED,
    WATERMARK_SAFETY_MARGIN,
    BENCHMARK_MEASUREMENT_TARGET,
    BENCHMARK_STARVED,
    WORKER_STOPPED,
    WORKER_FAILURE,
    APPROXIMATE_WORKER_LIMIT
}

@Serializable
enum class DiagnosticBackend {
    NONE,
    CPU,
    GPU
}

@Serializable
enum class BackendSelectionReason {
    NO_FINGERPRINT,
    NO_PROFILE,
    PROFILE_NOT_MEASURED,
    PROFILE_INVALID,
    PROFILE_EXPIRED,
    GPU_DISABLED_FOR_FINGERPRINT,
    WATERMARK_HEADROOM_NOT_MET,
    SAFETY_PROFILE_ACCEPTED,
    OPERATING_MODE_FORCES_CPU,
    MEMORY_PRESSURE_FORCES_CPU,
    SESSION_GPU_FALLBACK,
    BENCHMARK_MEASUREMENT_TARGET
}

@Serializable
enum class GpuLockStatus {
    ENABLED,
    DISABLED
}

@Serializable
enum class GpuUnlockCondition {
    FINGERPRINT_CHANGED,
    VALID_FRESH_PROFILE_AFTER_WAIT
}

@Serializable
data class GpuLockDiagnosticSnapshot(
    val fingerprintHash: String,
    val status: GpuLockStatus,
    val reason: BenchmarkDiagnosticReason? = null,
    val lockedAtEpochMs: Long? = null,
    val unlockCondition: GpuUnlockCondition? = null,
    val unlockEligibleAtEpochMs: Long? = null,
    val activeTransientWarningCount: Int = 0,
    val transientWarningLimit: Int
)

/** Typed-only telemetry. No prompt, request body, key, model path, provider name, or free-form text. */
@Serializable
data class BenchmarkDiagnosticEvent(
    val timestampEpochMs: Long,
    val type: BenchmarkDiagnosticType,
    val fingerprintHash: String?,
    val measurementLabel: BenchmarkMeasurementLabel = BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF,
    val result: BenchmarkDiagnosticResult,
    val reason: BenchmarkDiagnosticReason,
    val before: BenchmarkConditionSnapshot? = null,
    val after: BenchmarkConditionSnapshot? = null,
    val retryCount: Int = 0,
    val selectedBackend: DiagnosticBackend = DiagnosticBackend.NONE,
    val selectionReason: BackendSelectionReason? = null,
    val canaryOrdinal: Int? = null,
    val latencyMs: Long? = null,
    val referenceP95Ms: Double? = null,
    val memoryPressureDetected: Boolean? = null,
    val transientWarningCount: Int? = null,
    val transientWarningLimit: Int? = null,
    val triggerReason: BenchmarkDiagnosticReason? = null,
    val gpuLockSnapshot: GpuLockDiagnosticSnapshot? = null
)

@Serializable
private data class PersistedBenchmarkDiagnostics(
    val events: List<BenchmarkDiagnosticEvent> = emptyList(),
    val queuedSinceByFingerprint: Map<String, Long> = emptyMap(),
    val starvedFingerprints: Set<String> = emptySet(),
    val gpuLockSnapshot: GpuLockDiagnosticSnapshot? = null
)

/**
 * Local, bounded diagnostic ring for GEN-EDGE-1. The read surface is [readEvents] and
 * [renderTextReport]; write operations are internal to the inference/Worker implementation.
 */
class BenchmarkDiagnosticsStore(
    private val preferences: SharedPreferences,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    companion object {
        const val MAX_EVENTS = 64 // PROVISIONAL - small bounded ring; no telemetry upload.
        private const val STATE_KEY = "gen_edge_1_diagnostic_ring_v1"
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val MAX_TRACKED_FINGERPRINTS = 16 // PROVISIONAL - bounded queue-state map.
        private val FINGERPRINT_HASH = Regex("[0-9a-f]{64}")

        fun forContext(context: Context): BenchmarkDiagnosticsStore =
            BenchmarkDiagnosticsStore(
                context.applicationContext.getSharedPreferences(
                    "gen_edge_1_diagnostics",
                    Context.MODE_PRIVATE
                )
            )
    }

    @Synchronized
    fun readEvents(): List<BenchmarkDiagnosticEvent> = readState().events.toList()

    @Synchronized
    fun renderTextReport(): String = buildString {
        val state = readState()
        val events = state.events
        appendLine("GEN-EDGE-1 diagnostics (${events.size}/$MAX_EVENTS events; local ring)")
        val lock = state.gpuLockSnapshot
        if (lock == null) {
            appendLine("GPU lock: not observed")
        } else {
            append("GPU lock: status=").append(lock.status)
                .append(" reason=").append(lock.reason ?: BenchmarkDiagnosticReason.NONE)
                .append(" lockedAt=").append(lock.lockedAtEpochMs ?: "none")
                .append(" unlockCondition=").append(lock.unlockCondition ?: "none")
                .append(" unlockEligibleAt=").append(lock.unlockEligibleAtEpochMs ?: "none")
                .append(" transientWarnings=").append(lock.activeTransientWarningCount)
                .append('/').append(lock.transientWarningLimit)
                .appendLine()
        }
        events.forEach { event ->
            append("t=").append(event.timestampEpochMs)
                .append(" type=").append(event.type)
                .append(" result=").append(event.result)
                .append(" reason=").append(event.reason)
                .append(" label=").append(event.measurementLabel)
                .append(" retries=").append(event.retryCount)
                .append(" backend=").append(event.selectedBackend)
            event.fingerprintHash?.let { append(" fp=").append(it.take(12)) }
            event.selectionReason?.let { append(" selection=").append(it) }
            event.before?.let { append(" before={").append(it.toSafeText()).append('}') }
            event.after?.let { append(" after={").append(it.toSafeText()).append('}') }
            event.canaryOrdinal?.let { append(" canary=").append(it) }
            event.latencyMs?.let { append(" latencyMs=").append(it) }
            event.referenceP95Ms?.let { append(" referenceP95Ms=").append(it) }
            event.memoryPressureDetected?.let { append(" memoryPressure=").append(it) }
            event.transientWarningCount?.let { append(" transientWarnings=").append(it) }
            event.transientWarningLimit?.let { append(" warningLimit=").append(it) }
            event.triggerReason?.let { append(" triggerReason=").append(it) }
            appendLine()
        }
    }

    @Synchronized
    internal fun record(event: BenchmarkDiagnosticEvent) {
        val safeSnapshot = event.gpuLockSnapshot?.copy(
            fingerprintHash = event.gpuLockSnapshot.fingerprintHash.takeIf(FINGERPRINT_HASH::matches) ?: ""
        )
        val safeEvent = event.copy(
            fingerprintHash = event.fingerprintHash?.takeIf(FINGERPRINT_HASH::matches),
            gpuLockSnapshot = safeSnapshot
        )
        mutate { state ->
            val events = (state.events + safeEvent).takeLast(MAX_EVENTS)
            var queued = state.queuedSinceByFingerprint
            var starved = state.starvedFingerprints
            if (safeEvent.type == BenchmarkDiagnosticType.WORKER_EXECUTION &&
                safeEvent.result == BenchmarkDiagnosticResult.MEASURED &&
                !safeEvent.fingerprintHash.isNullOrBlank()
            ) {
                queued = queued - safeEvent.fingerprintHash
                starved = starved - safeEvent.fingerprintHash
            }
            val trackedFingerprints = queued.keys
            starved = starved.filterTo(mutableSetOf()) { it in trackedFingerprints }
            PersistedBenchmarkDiagnostics(events, queued, starved, safeSnapshot ?: state.gpuLockSnapshot)
        }
    }

    @Synchronized
    internal fun updateGpuLockSnapshot(snapshot: GpuLockDiagnosticSnapshot) {
        if (!FINGERPRINT_HASH.matches(snapshot.fingerprintHash)) return
        mutate { state ->
            val previous = state.gpuLockSnapshot
            val preserveLastUnlock = snapshot.status == GpuLockStatus.ENABLED &&
                snapshot.reason == null && snapshot.unlockCondition == null &&
                previous?.status == GpuLockStatus.ENABLED &&
                previous.unlockCondition != null &&
                previous.fingerprintHash == snapshot.fingerprintHash
            val current = if (preserveLastUnlock) {
                previous!!.copy(
                    activeTransientWarningCount = snapshot.activeTransientWarningCount,
                    transientWarningLimit = snapshot.transientWarningLimit
                )
            } else snapshot
            state.copy(gpuLockSnapshot = current)
        }
    }

    @Synchronized
    internal fun noteEnqueued(fingerprintHash: String, nowEpochMs: Long, validResultExists: Boolean) {
        if (!FINGERPRINT_HASH.matches(fingerprintHash)) return
        if (validResultExists) {
            mutate { state ->
                PersistedBenchmarkDiagnostics(
                    events = state.events,
                    queuedSinceByFingerprint = state.queuedSinceByFingerprint - fingerprintHash,
                    starvedFingerprints = state.starvedFingerprints - fingerprintHash
                )
            }
            return
        }
        mutate { state ->
            val queued = (state.queuedSinceByFingerprint + (fingerprintHash to
                (state.queuedSinceByFingerprint[fingerprintHash] ?: nowEpochMs)))
                .entries
                .toList()
                .takeLast(MAX_TRACKED_FINGERPRINTS)
                .associate { it.toPair() }
            val events = if (fingerprintHash in state.queuedSinceByFingerprint) {
                state.events
            } else {
                val event = BenchmarkDiagnosticEvent(
                    timestampEpochMs = nowEpochMs,
                    type = BenchmarkDiagnosticType.WORK_ENQUEUED,
                    fingerprintHash = fingerprintHash,
                    result = BenchmarkDiagnosticResult.ENQUEUED,
                    reason = BenchmarkDiagnosticReason.NONE
                )
                (state.events + event).takeLast(MAX_EVENTS)
            }
            PersistedBenchmarkDiagnostics(events, queued, state.starvedFingerprints)
        }
    }

    @Synchronized
    internal fun checkStarvation(fingerprintHash: String, nowEpochMs: Long, validResultExists: Boolean) {
        if (!FINGERPRINT_HASH.matches(fingerprintHash)) return
        mutate { state ->
            if (validResultExists) {
                return@mutate PersistedBenchmarkDiagnostics(
                    state.events,
                    state.queuedSinceByFingerprint - fingerprintHash,
                    state.starvedFingerprints - fingerprintHash
                )
            }
            val queuedAt = state.queuedSinceByFingerprint[fingerprintHash] ?: return@mutate state
            val elapsed = nowEpochMs - queuedAt
            val limit = HardwareBenchmarkAssumptions.WORKER_STARVATION_DAYS * DAY_MS
            if (elapsed < limit || fingerprintHash in state.starvedFingerprints) return@mutate state
            val event = BenchmarkDiagnosticEvent(
                timestampEpochMs = nowEpochMs,
                type = BenchmarkDiagnosticType.BENCHMARK_STARVED,
                fingerprintHash = fingerprintHash,
                result = BenchmarkDiagnosticResult.BENCHMARK_STARVED,
                reason = BenchmarkDiagnosticReason.BENCHMARK_STARVED
            )
            PersistedBenchmarkDiagnostics(
                (state.events + event).takeLast(MAX_EVENTS),
                state.queuedSinceByFingerprint,
                state.starvedFingerprints + fingerprintHash
            )
        }
    }

    @Synchronized
    internal fun clearQueueForFingerprint(fingerprintHash: String) {
        if (!FINGERPRINT_HASH.matches(fingerprintHash)) return
        mutate { state ->
            PersistedBenchmarkDiagnostics(
                state.events,
                state.queuedSinceByFingerprint - fingerprintHash,
                state.starvedFingerprints - fingerprintHash
            )
        }
    }

    private fun mutate(transform: (PersistedBenchmarkDiagnostics) -> PersistedBenchmarkDiagnostics) {
        runCatching {
            val next = transform(readState())
            preferences.edit().putString(STATE_KEY, json.encodeToString(next)).commit()
        }
    }

    private fun readState(): PersistedBenchmarkDiagnostics = runCatching {
        preferences.getString(STATE_KEY, null)?.let {
            json.decodeFromString<PersistedBenchmarkDiagnostics>(it).let { state ->
                state.copy(
                    events = state.events.map { event ->
                        event.copy(
                            fingerprintHash = event.fingerprintHash?.takeIf(FINGERPRINT_HASH::matches),
                            gpuLockSnapshot = event.gpuLockSnapshot?.let { snapshot ->
                                snapshot.copy(
                                    fingerprintHash = snapshot.fingerprintHash
                                        .takeIf(FINGERPRINT_HASH::matches) ?: ""
                                )
                            }
                        )
                    }.takeLast(MAX_EVENTS),
                    queuedSinceByFingerprint = state.queuedSinceByFingerprint
                        .filterKeys(FINGERPRINT_HASH::matches)
                        .entries.toList().takeLast(MAX_TRACKED_FINGERPRINTS).associate { it.toPair() },
                    starvedFingerprints = state.starvedFingerprints
                        .filter(FINGERPRINT_HASH::matches)
                        .takeLast(MAX_TRACKED_FINGERPRINTS)
                        .toSet(),
                    gpuLockSnapshot = state.gpuLockSnapshot?.let { snapshot ->
                        if (!FINGERPRINT_HASH.matches(snapshot.fingerprintHash)) null else snapshot.copy(
                            activeTransientWarningCount = snapshot.activeTransientWarningCount.coerceAtLeast(0),
                            transientWarningLimit = HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_LIMIT
                        )
                    }
                )
            }
        }
    }.getOrNull() ?: PersistedBenchmarkDiagnostics()

    private fun BenchmarkConditionSnapshot.toSafeText(): String =
        "charging=$isCharging,battery=$batteryPercent,screenInteractive=$screenInteractive," +
            "idle=$deviceIdle,thermal=$thermalStatus,at=$capturedAtEpochMs"

}
