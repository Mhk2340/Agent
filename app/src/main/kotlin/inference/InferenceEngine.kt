package inference

import contracts.InferenceError
import contracts.InferenceProvider
import contracts.InferenceRequest
import contracts.InferenceResponse
import contracts.ProviderCapabilities
import contracts.ProviderType
import java.util.concurrent.ConcurrentHashMap

/**
 * GEN-271 & GEN-278 — Model Abstraction Layer & Inference Engine Implementation.
 * Production may inject the pinned CPU JNI backend; JVM tests continue to inject deterministic fakes.
 */
class LocalInferenceEngine(
    private val budgetPolicy: ContextWindowBudgetPolicy = ContextWindowBudgetPolicy(),
    private val delegateSelector: HardwareDelegateSelector = HardwareDelegateSelector(),
    private val deviceFingerprint: String? = null,
    private val deviceProfile: DevicePerformanceProfile? = null,
    /** Legacy single-backend seam retained for existing contract tests. */
    private val backendExecutor: ((InferenceRequest) -> Result<InferenceResponse>)? = null,
    private val delegateBackendExecutor: InferenceDelegateExecutor? = null,
    private val memoryPressureSignalProvider: MemoryPressureSignalProvider = NoMemoryPressureSignal,
    private val modeDelegateOverride: () -> HardwareDelegate? = { null },
    private val onMemoryPressureFallback: () -> Unit = {},
    private val diagnostics: BenchmarkDiagnosticsStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val supportsStreaming: Boolean = true,
    private val supportsSeed: Boolean = true,
    private val supportsCancellation: Boolean = true
) : InferenceProvider {

    // A transient canary warning keeps this engine/session on CPU without creating a durable lock.
    private val sessionCpuOnlyFingerprints = ConcurrentHashMap.newKeySet<String>()

    /** Re-evaluated from durable profile/disable state; absent, invalid, stale, or disabled is CPU. */
    val selectedDelegate: HardwareDelegate
        get() = currentSelection().delegate

    override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        provider_type = ProviderType.LOCAL,
        // PROVISIONAL - pending GEN-PERF-1: max 4096 tokens
        max_context_tokens = budgetPolicy.maxContextTokens,
        max_output_tokens = 512,
        supports_streaming = supportsStreaming,
        supports_structured_output = true,
        supports_seed = supportsSeed,
        supports_cancellation = supportsCancellation
    )

    override fun generate(request: InferenceRequest): Result<InferenceResponse> {
        val estimatedContextTokens = request.context.length // approximate 1 token ~= 1 char in test logic
        val requestedMaxTokens = request.params.max_tokens
        if (!budgetPolicy.checkAdmission(estimatedContextTokens, requestedMaxTokens)) {
            val usable = budgetPolicy.calculateUsableContextTokens(requestedMaxTokens)
            return Result.failure(
                AgentModelException(
                    InferenceError.ResourceExhausted(
                        "Context admission rejected: context ($estimatedContextTokens tokens) exceeds usable context ($usable tokens)"
                    )
                )
            )
        }

        // Preserve the old injectable executor contract; delegate-aware implementations use the
        // separate safety/canary path below.
        if (backendExecutor != null) return backendExecutor.invoke(request)
        if (delegateBackendExecutor != null) return executeWithDelegateSafety(request)

        // No model is silently fabricated when the production CPU backend is absent or not configured.
        return Result.failure(
            AgentModelException(InferenceError.Unavailable("No local inference backend is configured"))
        )
    }

    private fun currentSelection(): HardwareDelegateSelection {
        // Unknown memory pressure is treated as pressure: the safe choice is CPU, not GPU.
        val memoryPressure = runCatching { memoryPressureSignalProvider.isMemoryPressureActive() }.getOrDefault(true)
        if (memoryPressure) {
            enterLowMemoryMode()
            return HardwareDelegateSelection(
                HardwareDelegate.CPU,
                BackendSelectionReason.MEMORY_PRESSURE_FORCES_CPU
            )
        }
        if (deviceFingerprint != null && deviceFingerprint in sessionCpuOnlyFingerprints) {
            return HardwareDelegateSelection(HardwareDelegate.CPU, BackendSelectionReason.SESSION_GPU_FALLBACK)
        }
        if (modeDelegateOverride() != null) {
            return HardwareDelegateSelection(
                HardwareDelegate.CPU,
                BackendSelectionReason.OPERATING_MODE_FORCES_CPU
            )
        }
        val selection = delegateSelector.selectSelection(deviceFingerprint, deviceProfile)
        recordSafetyTransitions()
        updateGpuLockSnapshot()
        return selection
    }

    private fun executeWithDelegateSafety(request: InferenceRequest): Result<InferenceResponse> {
        val selection = currentSelection()
        recordSelection(selection)
        if (selection.delegate == HardwareDelegate.CPU) return executeCpu(request)

        val fingerprintHash = deviceFingerprint
        val now = clock()
        val ordinal = fingerprintHash?.let {
            delegateSelector.gpuRuntimeSafetyStore.nextCanaryOrdinal(it, now)
        } ?: Int.MAX_VALUE
        recordSafetyTransitions()
        // A failed counter write latches GPU off in-process before this invocation reaches GPU.
        if (fingerprintHash != null && delegateSelector.gpuRuntimeSafetyStore.isGpuDisabled(fingerprintHash, clock())) {
            recordSafetyTransitions()
            recordFallback(
                BenchmarkDiagnosticReason.GPU_DISABLE_PERSISTENCE_FAILED,
                BackendSelectionReason.GPU_DISABLED_FOR_FINGERPRINT
            )
            return executeCpu(request)
        }
        val inCanary = ordinal in 1..HardwareBenchmarkAssumptions.GPU_CANARY_INVOCATIONS
        val profile = delegateSelector.profileForFingerprint(deviceFingerprint, deviceProfile)
        val gpuResult: Result<InferenceDelegateOutput> = try {
            delegateBackendExecutor!!.execute(HardwareDelegate.GPU, request)
        } catch (error: Exception) {
            Result.failure(error)
        }

        val gpuError = gpuResult.exceptionOrNull()
        if (gpuError != null && isMemoryPressureFailure(gpuError)) {
            if (inCanary && fingerprintHash != null) {
                recordCanary(
                    BenchmarkDiagnosticResult.TRANSIENT_WARNING,
                    BenchmarkDiagnosticReason.MEMORY_PRESSURE,
                    ordinal,
                    null,
                    profile?.gpuPrefillLatency?.p95Ms,
                    true
                )
                recordTransientWarning(
                    fingerprintHash,
                    BenchmarkDiagnosticReason.MEMORY_PRESSURE,
                    ordinal,
                    latencyMs = null,
                    referenceP95Ms = profile?.gpuPrefillLatency?.p95Ms,
                    memoryPressure = true
                )
            }
            enterLowMemoryMode()
            recordFallback(
                BenchmarkDiagnosticReason.MEMORY_PRESSURE,
                BackendSelectionReason.MEMORY_PRESSURE_FORCES_CPU
            )
            return executeCpu(request)
        }

        if (gpuError != null) {
            fingerprintHash?.let {
                disableGpuForFingerprint(it, BenchmarkDiagnosticReason.GPU_BACKEND_ERROR)
            }
            if (inCanary) {
                recordCanary(
                    BenchmarkDiagnosticResult.GPU_DISABLED,
                    BenchmarkDiagnosticReason.GPU_BACKEND_ERROR,
                    ordinal, null, profile?.gpuPrefillLatency?.p95Ms, null
                )
            }
            recordFallback(BenchmarkDiagnosticReason.GPU_BACKEND_ERROR, BackendSelectionReason.GPU_DISABLED_FOR_FINGERPRINT)
            return executeCpu(request)
        }

        if (gpuResult.isFailure) return Result.failure(IllegalStateException("GPU delegate failed without an error"))

        val gpuOutput = gpuResult.getOrThrow()
        val measuredPrefillLatencyMs = gpuOutput.prefillLatencyMs
        val referenceP95Ms = profile?.gpuPrefillLatency?.p95Ms
        val latencyLimitMs = referenceP95Ms?.times(
            HardwareBenchmarkAssumptions.GPU_CANARY_MAX_LATENCY_MULTIPLIER
        )
        val missingCanaryTelemetry = inCanary &&
            (measuredPrefillLatencyMs == null || measuredPrefillLatencyMs <= 0L || latencyLimitMs == null)
        if (missingCanaryTelemetry) {
            fingerprintHash?.let { disableGpuForFingerprint(it, BenchmarkDiagnosticReason.CANARY_TELEMETRY_MISSING) }
            recordCanary(
                BenchmarkDiagnosticResult.GPU_DISABLED,
                BenchmarkDiagnosticReason.CANARY_TELEMETRY_MISSING,
                ordinal,
                measuredPrefillLatencyMs,
                referenceP95Ms,
                null
            )
            recordFallback(
                BenchmarkDiagnosticReason.CANARY_TELEMETRY_MISSING,
                BackendSelectionReason.GPU_DISABLED_FOR_FINGERPRINT
            )
            return executeCpu(request)
        }

        val memoryPressure = if (inCanary) {
            runCatching { memoryPressureSignalProvider.isMemoryPressureActive() }.getOrDefault(true)
        } else false
        val latencyViolation = inCanary && measuredPrefillLatencyMs!!.toDouble() > latencyLimitMs!!
        val transientReason = when {
            memoryPressure -> BenchmarkDiagnosticReason.MEMORY_PRESSURE
            latencyViolation -> BenchmarkDiagnosticReason.CANARY_LATENCY
            else -> null
        }

        if (inCanary) {
            recordCanary(
                if (transientReason == null) BenchmarkDiagnosticResult.CANARY_PASSED
                else BenchmarkDiagnosticResult.TRANSIENT_WARNING,
                transientReason ?: BenchmarkDiagnosticReason.NONE,
                ordinal,
                measuredPrefillLatencyMs,
                referenceP95Ms,
                memoryPressure
            )
        }

        if (transientReason != null) {
            fingerprintHash?.let {
                recordTransientWarning(
                    it,
                    transientReason,
                    ordinal,
                    measuredPrefillLatencyMs,
                    referenceP95Ms,
                    memoryPressure
                )
            }
            if (memoryPressure) enterLowMemoryMode()
            recordFallback(
                transientReason,
                if (memoryPressure) BackendSelectionReason.MEMORY_PRESSURE_FORCES_CPU
                else BackendSelectionReason.SESSION_GPU_FALLBACK
            )
            return executeCpu(request)
        }
        return Result.success(gpuOutput.response)
    }

    private fun enterLowMemoryMode() {
        // Mode transitions are best-effort; they must not prevent the immediate CPU fallback.
        runCatching { onMemoryPressureFallback() }
    }

    private fun executeCpu(request: InferenceRequest): Result<InferenceResponse> = try {
        delegateBackendExecutor!!.execute(HardwareDelegate.CPU, request).map { it.response }
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun isMemoryPressureFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is AgentModelException && it.error is InferenceError.ResourceExhausted
        }

    private fun recordSelection(selection: HardwareDelegateSelection) {
        diagnostics?.record(
            BenchmarkDiagnosticEvent(
                timestampEpochMs = clock(),
                type = BenchmarkDiagnosticType.INFERENCE_BACKEND_SELECTED,
                fingerprintHash = deviceFingerprint,
                result = BenchmarkDiagnosticResult.BACKEND_SELECTED,
                reason = selection.reason.toDiagnosticReason(),
                selectedBackend = selection.delegate.toDiagnosticBackend(),
                selectionReason = selection.reason
            )
        )
    }

    private fun recordCanary(
        result: BenchmarkDiagnosticResult,
        reason: BenchmarkDiagnosticReason,
        ordinal: Int?,
        latencyMs: Long?,
        referenceP95Ms: Double?,
        memoryPressure: Boolean?
    ) {
        diagnostics?.record(
            BenchmarkDiagnosticEvent(
                timestampEpochMs = clock(),
                type = BenchmarkDiagnosticType.CANARY,
                fingerprintHash = deviceFingerprint,
                result = result,
                reason = reason,
                selectedBackend = DiagnosticBackend.GPU,
                canaryOrdinal = ordinal,
                latencyMs = latencyMs,
                referenceP95Ms = referenceP95Ms,
                memoryPressureDetected = memoryPressure
            )
        )
    }

    private fun disableGpuForFingerprint(fingerprintHash: String, reason: BenchmarkDiagnosticReason) {
        val persisted = delegateSelector.gpuRuntimeSafetyStore.disableGpu(fingerprintHash, reason, clock())
        recordSafetyTransitions()
        updateGpuLockSnapshot(fingerprintHash)
        if (!persisted) {
            diagnostics?.record(
                BenchmarkDiagnosticEvent(
                    timestampEpochMs = clock(),
                    type = BenchmarkDiagnosticType.GPU_FALLBACK,
                    fingerprintHash = fingerprintHash,
                    result = BenchmarkDiagnosticResult.GPU_DISABLED,
                    reason = BenchmarkDiagnosticReason.GPU_DISABLE_PERSISTENCE_FAILED,
                    triggerReason = reason,
                    selectedBackend = DiagnosticBackend.CPU,
                    selectionReason = BackendSelectionReason.GPU_DISABLED_FOR_FINGERPRINT
                )
            )
        }
    }

    private fun recordTransientWarning(
        fingerprintHash: String,
        reason: BenchmarkDiagnosticReason,
        ordinal: Int,
        latencyMs: Long?,
        referenceP95Ms: Double?,
        memoryPressure: Boolean
    ) {
        sessionCpuOnlyFingerprints += fingerprintHash
        val decision = delegateSelector.gpuRuntimeSafetyStore.recordTransientWarning(
            fingerprintHash,
            reason,
            clock()
        )
        diagnostics?.record(
            BenchmarkDiagnosticEvent(
                timestampEpochMs = clock(),
                type = BenchmarkDiagnosticType.GPU_WARNING,
                fingerprintHash = fingerprintHash,
                result = BenchmarkDiagnosticResult.TRANSIENT_WARNING,
                reason = reason,
                selectedBackend = DiagnosticBackend.CPU,
                selectionReason = BackendSelectionReason.SESSION_GPU_FALLBACK,
                canaryOrdinal = ordinal,
                latencyMs = latencyMs,
                referenceP95Ms = referenceP95Ms,
                memoryPressureDetected = memoryPressure,
                transientWarningCount = decision.activeWarningCount,
                transientWarningLimit = decision.warningLimit,
                triggerReason = when {
                    !decision.lockCreated -> null
                    decision.activeWarningCount >= decision.warningLimit -> BenchmarkDiagnosticReason.GPU_TRANSIENT_WARNING_LIMIT
                    else -> BenchmarkDiagnosticReason.GPU_DISABLE_PERSISTENCE_FAILED
                }
            )
        )
        recordSafetyTransitions()
        updateGpuLockSnapshot(fingerprintHash)
    }

    private fun recordSafetyTransitions() {
        val safetyStore = delegateSelector.gpuRuntimeSafetyStore
        safetyStore.drainTransitions().forEach { transition ->
            if (transition.kind == GpuSafetyTransitionKind.UNLOCKED) {
                transition.previousFingerprintHash?.let(sessionCpuOnlyFingerprints::remove)
                sessionCpuOnlyFingerprints.remove(transition.fingerprintHash)
            }
            val status = if (transition.kind == GpuSafetyTransitionKind.LOCKED) {
                GpuLockStatus.DISABLED
            } else GpuLockStatus.ENABLED
            val snapshotState = safetyStore.safetySnapshot(transition.fingerprintHash, clock())
            val reportSnapshot = GpuLockDiagnosticSnapshot(
                fingerprintHash = transition.fingerprintHash,
                status = status,
                reason = transition.reason,
                lockedAtEpochMs = transition.lockedAtEpochMs,
                unlockCondition = transition.unlockCondition,
                unlockEligibleAtEpochMs = transition.unlockEligibleAtEpochMs,
                activeTransientWarningCount = snapshotState.activeTransientWarningCount,
                transientWarningLimit = HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_LIMIT
            )
            diagnostics?.record(
                BenchmarkDiagnosticEvent(
                    timestampEpochMs = transition.timestampEpochMs,
                    type = if (transition.kind == GpuSafetyTransitionKind.LOCKED)
                        BenchmarkDiagnosticType.GPU_LOCKED else BenchmarkDiagnosticType.GPU_UNLOCKED,
                    fingerprintHash = transition.fingerprintHash,
                    result = if (transition.kind == GpuSafetyTransitionKind.LOCKED)
                        BenchmarkDiagnosticResult.GPU_DISABLED else BenchmarkDiagnosticResult.GPU_UNLOCKED,
                    reason = transition.reason,
                    selectedBackend = if (transition.kind == GpuSafetyTransitionKind.LOCKED)
                        DiagnosticBackend.CPU else DiagnosticBackend.NONE,
                    triggerReason = transition.triggerReason,
                    gpuLockSnapshot = reportSnapshot
                )
            )
        }
    }

    private fun updateGpuLockSnapshot(fingerprintHash: String? = deviceFingerprint) {
        if (fingerprintHash == null) return
        val state = delegateSelector.gpuRuntimeSafetyStore.safetySnapshot(fingerprintHash, clock())
        diagnostics?.updateGpuLockSnapshot(
            GpuLockDiagnosticSnapshot(
                fingerprintHash = state.fingerprintHash,
                status = state.status,
                reason = state.reason,
                lockedAtEpochMs = state.lockedAtEpochMs,
                unlockCondition = state.unlockCondition,
                unlockEligibleAtEpochMs = state.unlockEligibleAtEpochMs,
                activeTransientWarningCount = state.activeTransientWarningCount,
                transientWarningLimit = HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_LIMIT
            )
        )
    }

    private fun recordFallback(reason: BenchmarkDiagnosticReason, selectionReason: BackendSelectionReason) {
        diagnostics?.record(
            BenchmarkDiagnosticEvent(
                timestampEpochMs = clock(),
                type = BenchmarkDiagnosticType.GPU_FALLBACK,
                fingerprintHash = deviceFingerprint,
                result = BenchmarkDiagnosticResult.CPU_FALLBACK,
                reason = reason,
                selectedBackend = DiagnosticBackend.CPU,
                selectionReason = selectionReason
            )
        )
    }

    private fun HardwareDelegate.toDiagnosticBackend(): DiagnosticBackend = when (this) {
        HardwareDelegate.CPU -> DiagnosticBackend.CPU
        HardwareDelegate.GPU -> DiagnosticBackend.GPU
    }

    private fun BackendSelectionReason.toDiagnosticReason(): BenchmarkDiagnosticReason = when (this) {
        BackendSelectionReason.NO_FINGERPRINT -> BenchmarkDiagnosticReason.NO_VALID_PROFILE
        BackendSelectionReason.NO_PROFILE -> BenchmarkDiagnosticReason.NO_VALID_PROFILE
        BackendSelectionReason.PROFILE_NOT_MEASURED -> BenchmarkDiagnosticReason.PROFILE_INVALID
        BackendSelectionReason.PROFILE_INVALID -> BenchmarkDiagnosticReason.PROFILE_INVALID
        BackendSelectionReason.PROFILE_EXPIRED -> BenchmarkDiagnosticReason.PROFILE_EXPIRED
        BackendSelectionReason.GPU_DISABLED_FOR_FINGERPRINT -> BenchmarkDiagnosticReason.GPU_DISABLED_FOR_FINGERPRINT
        BackendSelectionReason.WATERMARK_HEADROOM_NOT_MET -> BenchmarkDiagnosticReason.WATERMARK_SAFETY_MARGIN
        BackendSelectionReason.SAFETY_PROFILE_ACCEPTED -> BenchmarkDiagnosticReason.NONE
        BackendSelectionReason.OPERATING_MODE_FORCES_CPU -> BenchmarkDiagnosticReason.OPERATING_MODE_OVERRIDE
        BackendSelectionReason.MEMORY_PRESSURE_FORCES_CPU -> BenchmarkDiagnosticReason.MEMORY_PRESSURE
        BackendSelectionReason.SESSION_GPU_FALLBACK -> BenchmarkDiagnosticReason.SESSION_GPU_FALLBACK
        BackendSelectionReason.BENCHMARK_MEASUREMENT_TARGET -> BenchmarkDiagnosticReason.BENCHMARK_MEASUREMENT_TARGET
    }
}

class AgentModelException(val error: InferenceError) : Exception(error.toString())
