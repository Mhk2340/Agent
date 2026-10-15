package inference

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** The four inputs that identify a hardware/model/runtime benchmark result. */
@Serializable
data class DeviceFingerprint(
    val device: String,
    val osBuild: String,
    val modelSha256: String?,
    val runtimeVersion: String?
) {
    /** Stable key used for both persisted-result lookup and unique WorkManager work. */
    val stableKey: String
        get() {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(device, osBuild, modelSha256, runtimeVersion).forEach { value ->
                val bytes = value?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                val length = if (value == null) -1 else bytes.size
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(length).array())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

    val hasModelAndRuntimeIdentity: Boolean
        get() = modelSha256?.matches(SHA256_HEX) == true && !runtimeVersion.isNullOrBlank()

    companion object {
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    }
}

@Serializable
enum class BenchmarkMeasurementLabel {
    BACKGROUND_SCREEN_OFF
}

@Serializable
enum class BenchmarkThermalStatus {
    UNKNOWN,
    NORMAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN
}

@Serializable
data class BenchmarkConditionSnapshot(
    val isCharging: Boolean,
    val batteryPercent: Int?,
    val screenInteractive: Boolean,
    val deviceIdle: Boolean,
    val thermalStatus: BenchmarkThermalStatus,
    val capturedAtEpochMs: Long
) {
    fun isSafeForBenchmark(): Boolean =
        isCharging && batteryPercent != null && batteryPercent >= HardwareBenchmarkAssumptions.MIN_BATTERY_PERCENT &&
            deviceIdle && thermalStatus.isSafeForMeasurement()
}

// PROVISIONAL threshold: MODERATE is accepted; SEVERE or higher and UNKNOWN fail closed.
private fun BenchmarkThermalStatus.isSafeForMeasurement(): Boolean =
    this == BenchmarkThermalStatus.NORMAL || this == BenchmarkThermalStatus.LIGHT ||
        this == BenchmarkThermalStatus.MODERATE

@Serializable
data class BenchmarkConditions(
    val beforeMeasurement: BenchmarkConditionSnapshot,
    val afterMeasurement: BenchmarkConditionSnapshot
) {
    fun areSafeForBenchmark(): Boolean =
        beforeMeasurement.isSafeForBenchmark() && afterMeasurement.isSafeForBenchmark()
}

data class ModelRuntimeIdentity(
    val modelSha256: String?,
    val runtimeVersion: String?
)

fun interface LocalModelRuntimeIdentitySource {
    fun currentIdentity(): ModelRuntimeIdentity
}

/**
 * The current app has no installed-model/runtime registry. This source deliberately reports
 * unknown identity instead of inventing a model hash or runtime version.
 */
class UnconfiguredLocalModelRuntimeIdentitySource : LocalModelRuntimeIdentitySource {
    override fun currentIdentity(): ModelRuntimeIdentity =
        ModelRuntimeIdentity(modelSha256 = null, runtimeVersion = null)
}

fun interface DeviceFingerprintProvider {
    fun currentFingerprint(): DeviceFingerprint
}

class AndroidDeviceFingerprintProvider(
    private val modelRuntimeIdentitySource: LocalModelRuntimeIdentitySource =
        UnconfiguredLocalModelRuntimeIdentitySource()
) : DeviceFingerprintProvider {
    override fun currentFingerprint(): DeviceFingerprint {
        val identity = modelRuntimeIdentitySource.currentIdentity()
        return DeviceFingerprint(
            device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            osBuild = Build.FINGERPRINT,
            modelSha256 = identity.modelSha256,
            runtimeVersion = identity.runtimeVersion
        )
    }
}

@Serializable
data class LatencyStatistics(
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val standardDeviationMs: Double
) {
    fun isValid(): Boolean =
        p50Ms.isFinite() && p95Ms.isFinite() && p99Ms.isFinite() && standardDeviationMs.isFinite() &&
            p50Ms > 0.0 && p50Ms <= p95Ms && p95Ms <= p99Ms && standardDeviationMs >= 0.0
}

@Serializable
data class DecodeRateStatistics(
    val p50TokensPerSecond: Double,
    val p95TokensPerSecond: Double,
    val p99TokensPerSecond: Double,
    val standardDeviationTokensPerSecond: Double
) {
    fun isValid(): Boolean =
        p50TokensPerSecond.isFinite() && p95TokensPerSecond.isFinite() &&
            p99TokensPerSecond.isFinite() && standardDeviationTokensPerSecond.isFinite() &&
            p50TokensPerSecond > 0.0 && p50TokensPerSecond <= p95TokensPerSecond &&
            p95TokensPerSecond <= p99TokensPerSecond && standardDeviationTokensPerSecond >= 0.0
}

/** GPU-only candidate metrics; CPU remains the safe default and is not assigned synthetic metrics. */
@Serializable
data class DevicePerformanceProfile(
    val fingerprint: DeviceFingerprint,
    val contextTokens: Int,
    val warmupRuns: Int,
    val sampleCount: Int,
    val gpuPrefillLatency: LatencyStatistics,
    val gpuDecodeRate: DecodeRateStatistics,
    val gpuPeakAnonymousMemoryMb: Long,
    val lmkKillWatermarkMb: Long,
    val measuredAtEpochMs: Long,
    val measurementLabel: BenchmarkMeasurementLabel = BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF,
    val conditions: BenchmarkConditions? = null
) {
    fun hasStableStatistics(): Boolean =
        gpuPrefillLatency.isValid() && gpuDecodeRate.isValid() &&
            gpuPrefillLatency.standardDeviationMs / gpuPrefillLatency.p95Ms <=
                HardwareBenchmarkAssumptions.MAX_STDEV_TO_P95_RATIO &&
            gpuDecodeRate.standardDeviationTokensPerSecond / gpuDecodeRate.p95TokensPerSecond <=
                HardwareBenchmarkAssumptions.MAX_STDEV_TO_P95_RATIO

    /**
     * A profile is eligible only when the distinct GEN-PERF-1 summaries are all present and
     * statistically sized; no combined speed score is calculated.
     */
    fun isUsableFor(expectedFingerprintKey: String): Boolean =
        fingerprint.stableKey == expectedFingerprintKey && fingerprint.hasModelAndRuntimeIdentity &&
            contextTokens == HardwareBenchmarkAssumptions.CONTEXT_TOKENS &&
            warmupRuns in HardwareBenchmarkAssumptions.MIN_WARMUP_RUNS..HardwareBenchmarkAssumptions.MAX_WARMUP_RUNS &&
            sampleCount >= HardwareBenchmarkAssumptions.MIN_SAMPLES_FOR_P95 &&
            sampleCount >= HardwareBenchmarkAssumptions.MIN_SAMPLES_FOR_P99 &&
            hasStableStatistics() &&
            measurementLabel == BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF &&
            conditions?.areSafeForBenchmark() == true &&
            gpuPeakAnonymousMemoryMb > 0L && lmkKillWatermarkMb > 0L && measuredAtEpochMs > 0L
}

@Serializable
enum class DeviceBenchmarkStatus {
    MEASURED,
    NOT_MEASURED,
    MODEL_UNAVAILABLE,
    UNSTABLE,
    THERMAL_HIGH
}

@Serializable
data class DeviceBenchmarkResult(
    val fingerprint: DeviceFingerprint,
    val status: DeviceBenchmarkStatus,
    val profile: DevicePerformanceProfile? = null,
    val reason: String? = null,
    val updatedAtEpochMs: Long,
    val measurementLabel: BenchmarkMeasurementLabel = BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF,
    val conditions: BenchmarkConditions? = null
)

fun DeviceBenchmarkResult.isFreshValidMeasurement(fingerprintKey: String, nowEpochMs: Long): Boolean {
    val measuredProfile = profile ?: return false
    val ageMs = nowEpochMs - measuredProfile.measuredAtEpochMs
    return fingerprint.stableKey == fingerprintKey && status == DeviceBenchmarkStatus.MEASURED &&
        measurementLabel == BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF &&
        conditions != null && conditions == measuredProfile.conditions &&
        measuredProfile.isUsableFor(fingerprintKey) && ageMs in 0L..HardwareBenchmarkAssumptions.PROFILE_MAX_AGE_MS
}

/** Provisional methodology constants are centralized so they can be replaced from GEN-PERF-1. */
object HardwareBenchmarkAssumptions {
    const val MIN_IDLE_DURATION_MS = 5L * 60L * 1000L // PROVISIONAL - pending GEN-PERF-1
    const val MIN_BATTERY_PERCENT = 50 // PROVISIONAL - pending GEN-PERF-1
    const val CONTEXT_TOKENS = 4096 // PROVISIONAL - pending GEN-PERF-1
    const val MIN_SAMPLES_FOR_P95 = 20 // PROVISIONAL - pending GEN-PERF-1
    const val MIN_SAMPLES_FOR_P99 = 50 // PROVISIONAL - pending GEN-PERF-1
    const val MIN_WARMUP_RUNS = 2 // PROVISIONAL - pending GEN-PERF-1
    const val MAX_WARMUP_RUNS = 3 // PROVISIONAL - pending GEN-PERF-1
    const val MAX_STDEV_TO_P95_RATIO = 0.25 // PROVISIONAL - stability ceiling proposed for GEN-EDGE-1.
    const val GPU_WATERMARK_SAFETY_FACTOR = 0.80 // PROVISIONAL - require 20% headroom below the LMK watermark.
    const val PROFILE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L // PROVISIONAL - benchmark freshness window.
    const val WORKER_STARVATION_DAYS = 7L // PROVISIONAL - report a queued benchmark with no valid result.
    const val GPU_CANARY_INVOCATIONS = 5 // PROVISIONAL - number of initial real GPU calls monitored.
    const val GPU_CANARY_MAX_LATENCY_MULTIPLIER = 2.0 // PROVISIONAL - measured prefill latency vs prefill p95.
    const val GPU_TRANSIENT_WARNING_LIMIT = 3 // PROVISIONAL - pending GEN-PERF-1
    const val GPU_TRANSIENT_WARNING_WINDOW_MS = 24L * 60L * 60L * 1000L // PROVISIONAL - pending GEN-PERF-1
    const val GPU_REENABLE_MIN_WAIT_MS = 24L * 60L * 60L * 1000L // PROVISIONAL - pending GEN-PERF-1
}

interface DeviceProfileStore {
    /** Synchronous in-memory snapshot only; an absent snapshot always means CPU until preloaded. */
    fun peek(fingerprintKey: String): DeviceBenchmarkResult?

    /** Reads the persisted result and refreshes the selector snapshot. */
    suspend fun getResult(fingerprint: DeviceFingerprint): DeviceBenchmarkResult?

    /** Persists the result and updates the current-process selector snapshot. */
    suspend fun saveResult(result: DeviceBenchmarkResult)
}

class InMemoryDeviceProfileStore : DeviceProfileStore {
    private val records = ConcurrentHashMap<String, DeviceBenchmarkResult>()

    override fun peek(fingerprintKey: String): DeviceBenchmarkResult? = records[fingerprintKey]

    override suspend fun getResult(fingerprint: DeviceFingerprint): DeviceBenchmarkResult? =
        records[fingerprint.stableKey]?.takeIf { it.fingerprint == fingerprint }

    override suspend fun saveResult(result: DeviceBenchmarkResult) {
        records[result.fingerprint.stableKey] = result
    }
}

private val Context.hardwareBenchmarkPreferences: DataStore<Preferences> by
    preferencesDataStore(name = "hardware_delegate_profiles")

fun deviceProfileStoreFor(context: Context): DeviceProfileStore =
    DataStoreDeviceProfileStore(context.applicationContext.hardwareBenchmarkPreferences)

/**
 * Persistent profile/result store. DataStore is authoritative; the concurrent map is only a
 * non-blocking selector snapshot and is repopulated by [getResult] after process restart.
 */
class DataStoreDeviceProfileStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) : DeviceProfileStore {
    private val snapshots = ConcurrentHashMap<String, DeviceBenchmarkResult>()

    override fun peek(fingerprintKey: String): DeviceBenchmarkResult? = snapshots[fingerprintKey]

    override suspend fun getResult(fingerprint: DeviceFingerprint): DeviceBenchmarkResult? {
        val key = resultKey(fingerprint.stableKey)
        val encoded = dataStore.data.first()[key] ?: return null
        val result = runCatching { json.decodeFromString<DeviceBenchmarkResult>(encoded) }
            .getOrNull()
            ?.takeIf { it.fingerprint == fingerprint }
            ?: return null
        snapshots[fingerprint.stableKey] = result
        return result
    }

    override suspend fun saveResult(result: DeviceBenchmarkResult) {
        val fingerprintKey = result.fingerprint.stableKey
        dataStore.edit { preferences ->
            preferences[resultKey(fingerprintKey)] = json.encodeToString(result)
        }
        snapshots[fingerprintKey] = result
    }

    private fun resultKey(fingerprintKey: String) =
        stringPreferencesKey("benchmark_result_$fingerprintKey")
}

sealed interface BenchmarkMeasurementOutcome {
    data class Measured(val profile: DevicePerformanceProfile) : BenchmarkMeasurementOutcome
    data class NotMeasured(val reason: String) : BenchmarkMeasurementOutcome
    data class ModelUnavailable(val reason: String) : BenchmarkMeasurementOutcome
}

fun interface DeviceBenchmarkMeasurement {
    suspend fun measure(fingerprint: DeviceFingerprint): BenchmarkMeasurementOutcome
}

/**
 * Honest production placeholder: no inference runtime/benchmark adapter is currently installed.
 * It emits a status only, never fabricated timing or memory numbers.
 */
class ProductionDeviceBenchmarkMeasurement : DeviceBenchmarkMeasurement {
    override suspend fun measure(fingerprint: DeviceFingerprint): BenchmarkMeasurementOutcome =
        if (!fingerprint.hasModelAndRuntimeIdentity) {
            BenchmarkMeasurementOutcome.ModelUnavailable(
                "No installed model SHA-256 and inference runtime version are exposed by the current backend."
            )
        } else {
            BenchmarkMeasurementOutcome.NotMeasured(
                "A real on-device inference benchmark adapter is not wired; no numeric profile was generated."
            )
        }
}
