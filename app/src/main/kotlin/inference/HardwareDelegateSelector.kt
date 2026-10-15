package inference

/** The execution delegate hidden behind the existing InferenceProvider contract. */
enum class HardwareDelegate {
    CPU,
    GPU
}

data class HardwareDelegateSelection(
    val delegate: HardwareDelegate,
    val reason: BackendSelectionReason
)

/** Pure, measurement-independent Safety-over-Speed decision. */
object HardwareDelegateDecision {
    fun select(
        fingerprintKey: String?,
        result: DeviceBenchmarkResult?,
        gpuRuntimeSafetyStore: GpuRuntimeSafetyStore,
        nowEpochMs: Long
    ): HardwareDelegateSelection {
        if (fingerprintKey.isNullOrBlank()) {
            return HardwareDelegateSelection(HardwareDelegate.CPU, BackendSelectionReason.NO_FINGERPRINT)
        }
        if (result?.isFreshValidMeasurement(fingerprintKey, nowEpochMs) == true) {
            gpuRuntimeSafetyStore.tryUnlockForFreshProfile(fingerprintKey, result, nowEpochMs)
        }
        if (gpuRuntimeSafetyStore.isGpuDisabled(fingerprintKey, nowEpochMs)) {
            return HardwareDelegateSelection(HardwareDelegate.CPU, BackendSelectionReason.GPU_DISABLED_FOR_FINGERPRINT)
        }
        if (result == null) {
            return HardwareDelegateSelection(HardwareDelegate.CPU, BackendSelectionReason.NO_PROFILE)
        }
        if (result.fingerprint.stableKey != fingerprintKey || result.status != DeviceBenchmarkStatus.MEASURED) {
            return HardwareDelegateSelection(HardwareDelegate.CPU, BackendSelectionReason.PROFILE_NOT_MEASURED)
        }

        val profile = result.profile
            ?: return HardwareDelegateSelection(HardwareDelegate.CPU, BackendSelectionReason.PROFILE_INVALID)
        if (result.measurementLabel != BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF ||
            result.conditions == null || result.conditions != profile.conditions ||
            !profile.isUsableFor(fingerprintKey)
        ) {
            return HardwareDelegateSelection(HardwareDelegate.CPU, BackendSelectionReason.PROFILE_INVALID)
        }
        val ageMs = nowEpochMs - profile.measuredAtEpochMs
        if (ageMs < 0L || ageMs > HardwareBenchmarkAssumptions.PROFILE_MAX_AGE_MS) {
            return HardwareDelegateSelection(HardwareDelegate.CPU, BackendSelectionReason.PROFILE_EXPIRED)
        }

        // PROVISIONAL 20% headroom margin; stricter than Peak Anonymous < raw LMK watermark.
        val safeLimit = profile.lmkKillWatermarkMb.toDouble() *
            HardwareBenchmarkAssumptions.GPU_WATERMARK_SAFETY_FACTOR
        return if (profile.gpuPeakAnonymousMemoryMb.toDouble() < safeLimit) {
            HardwareDelegateSelection(HardwareDelegate.GPU, BackendSelectionReason.SAFETY_PROFILE_ACCEPTED)
        } else {
            HardwareDelegateSelection(HardwareDelegate.CPU, BackendSelectionReason.WATERMARK_HEADROOM_NOT_MET)
        }
    }
}

/**
 * Resolves the current DataStore snapshot. Until a complete, stable, safe, fresh profile is loaded,
 * CPU is selected. A durable GPU lock clears only after a fingerprint change or a qualifying fresh
 * profile measured after the lock's minimum wait.
 */
class HardwareDelegateSelector(
    private val profileStore: DeviceProfileStore = InMemoryDeviceProfileStore(),
    val gpuRuntimeSafetyStore: GpuRuntimeSafetyStore = InMemoryGpuRuntimeSafetyStore(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun selectSelection(
        deviceFingerprint: String?,
        liveProfile: DevicePerformanceProfile? = null
    ): HardwareDelegateSelection {
        val result = when {
            liveProfile != null -> DeviceBenchmarkResult(
                fingerprint = liveProfile.fingerprint,
                status = DeviceBenchmarkStatus.MEASURED,
                profile = liveProfile,
                updatedAtEpochMs = liveProfile.measuredAtEpochMs,
                measurementLabel = liveProfile.measurementLabel,
                conditions = liveProfile.conditions
            )
            deviceFingerprint != null -> profileStore.peek(deviceFingerprint)
            else -> null
        }
        return HardwareDelegateDecision.select(deviceFingerprint, result, gpuRuntimeSafetyStore, clock())
    }

    fun profileForFingerprint(
        deviceFingerprint: String?,
        liveProfile: DevicePerformanceProfile? = null
    ): DevicePerformanceProfile? = when {
        liveProfile != null -> liveProfile
        deviceFingerprint != null -> profileStore.peek(deviceFingerprint)?.profile
        else -> null
    }

    fun selectDelegate(
        deviceFingerprint: String?,
        liveProfile: DevicePerformanceProfile? = null
    ): HardwareDelegate = selectSelection(deviceFingerprint, liveProfile).delegate
}
