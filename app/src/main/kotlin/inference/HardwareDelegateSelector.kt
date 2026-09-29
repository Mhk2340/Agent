package inference

/**
 * GEN-EDGE-1 — Hardware Delegate Selection
 * Selects between CPU and GPU Delegate based on Safety-over-Speed criteria.
 *
 * PROVISIONAL - pending GEN-PERF-1:
 * Roadmap Requirement:
 * 1. Benchmark timing: Scheduled via WorkManager with constraints (device charging + >= 5 min idle),
 *    NOT during initial app startup.
 * 2. Methodology: n >= 20 runs for p95, stable decode tokens/sec, peak anonymous memory.
 * 3. Caching: The resulting fingerprint (device + os + model) is persisted and reused across restarts.
 * 4. Safety-over-Speed Decision Rule:
 *    If Peak Anonymous (GPU) < Device LMK Kill Watermark -> GPU.
 *    Otherwise -> CPU (even if GPU is faster).
 *
 * STATUS IN CURRENT SANDBOX:
 * The profiling execution pipeline is BLOCKED on physical ARM64 hardware (same as GEN-PERF-1).
 * In this sandbox environment, the decision logic and caching layer are fully implemented and tested,
 * while the live measurement trigger defaults conservatively to CPU pending actual on-device execution.
 */
enum class HardwareDelegate {
    CPU,
    GPU
}

data class DevicePerformanceProfile(
    // PROVISIONAL - pending GEN-PERF-1
    val deviceFingerprint: String,
    val prefillP95Ms: Long,
    val decodeTokensPerSec: Float,
    val peakAnonymousMemoryMb: Long,
    val lmkKillWatermarkMb: Long,
    val sampleCount: Int = 20 // n >= 20 requirement
)

interface DeviceProfileStore {
    fun getCachedProfile(fingerprint: String): DevicePerformanceProfile?
    fun saveProfile(profile: DevicePerformanceProfile)
}

class InMemoryDeviceProfileStore : DeviceProfileStore {
    private val cache = mutableMapOf<String, DevicePerformanceProfile>()

    override fun getCachedProfile(fingerprint: String): DevicePerformanceProfile? = cache[fingerprint]

    override fun saveProfile(profile: DevicePerformanceProfile) {
        cache[profile.deviceFingerprint] = profile
    }
}

class HardwareDelegateSelector(
    private val profileStore: DeviceProfileStore = InMemoryDeviceProfileStore()
) {
    /**
     * Resolves the hardware delegate.
     * Uses cached profile if available from previous WorkManager benchmarking runs.
     * Falls back conservatively to CPU if unprofiled or if memory exceeds watermark.
     */
    fun selectDelegate(deviceFingerprint: String?, liveProfile: DevicePerformanceProfile? = null): HardwareDelegate {
        val profile = liveProfile 
            ?: (deviceFingerprint?.let { profileStore.getCachedProfile(it) })

        if (profile == null) {
            // PROVISIONAL - pending GEN-PERF-1: Default conservative fallback is CPU
            return HardwareDelegate.CPU
        }

        // Enforce n >= 20 methodology requirement
        if (profile.sampleCount < 20) {
            return HardwareDelegate.CPU
        }

        return if (profile.peakAnonymousMemoryMb < profile.lmkKillWatermarkMb) {
            HardwareDelegate.GPU
        } else {
            HardwareDelegate.CPU
        }
    }

    fun cacheProfile(profile: DevicePerformanceProfile) {
        profileStore.saveProfile(profile)
    }
}
