package inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import contracts.GenerateParams
import contracts.InferenceError
import contracts.InferenceRequest
import contracts.InferenceResponse
import contracts.TokenUsage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@Config(sdk = [28], manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class HardwareDelegateSafetyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 2_000_000L
    private val fingerprint = DeviceFingerprint("canary-device", "build-A", "c".repeat(64), "fake-runtime")

    @Test
    fun invariantCpuUntilFreshSafeStableProfileAndGpuDisabledIsFingerprintScoped() = runBlocking {
        val profiles = InMemoryDeviceProfileStore()
        val runtimeSafety = InMemoryGpuRuntimeSafetyStore()
        val selector = HardwareDelegateSelector(profiles, runtimeSafety) { now }
        assertFalse("The no-profile invariant is independent of GPU_DISABLED", runtimeSafety.isGpuDisabled(fingerprint.stableKey, now))
        assertEquals(HardwareDelegate.CPU, selector.selectDelegate(fingerprint.stableKey))

        val fresh = profile(fingerprint)
        profiles.saveResult(measured(fresh))
        assertEquals(HardwareDelegate.GPU, selector.selectDelegate(fingerprint.stableKey))

        val changedFingerprint = fingerprint.copy(osBuild = "build-B")
        runtimeSafety.disableGpu(fingerprint.stableKey, BenchmarkDiagnosticReason.GPU_BACKEND_ERROR)
        assertTrue(runtimeSafety.isGpuDisabled(fingerprint.stableKey))
        assertFalse(runtimeSafety.isGpuDisabled(changedFingerprint.stableKey))
        assertFalse("Changing fingerprint clears the previous fingerprint latch", runtimeSafety.isGpuDisabled(fingerprint.stableKey))
        runtimeSafety.disableGpu(fingerprint.stableKey, BenchmarkDiagnosticReason.GPU_BACKEND_ERROR)
        assertEquals(HardwareDelegate.CPU, selector.selectDelegate(fingerprint.stableKey))
        assertEquals(HardwareDelegate.GPU, selector.selectDelegate(changedFingerprint.stableKey, profile(changedFingerprint)))

        val expired = profile(fingerprint).copy(measuredAtEpochMs = now - HardwareBenchmarkAssumptions.PROFILE_MAX_AGE_MS - 1L)
        assertEquals(HardwareDelegate.CPU, selector.selectDelegate(fingerprint.stableKey, expired))
        val safetyBoundary = profile(fingerprint).copy(gpuPeakAnonymousMemoryMb = 560L)
        assertEquals(
            "Equality at watermark times the safety factor is rejected",
            HardwareDelegate.CPU,
            HardwareDelegateSelector(InMemoryDeviceProfileStore(), InMemoryGpuRuntimeSafetyStore()) { now }
                .selectDelegate(fingerprint.stableKey, safetyBoundary)
        )
        val unsafeSpread = profile(fingerprint).copy(
            gpuPrefillLatency = LatencyStatistics(100.0, 200.0, 250.0, 60.0)
        )
        val isolatedSelector = HardwareDelegateSelector(
            InMemoryDeviceProfileStore(), InMemoryGpuRuntimeSafetyStore(), { now }
        )
        assertEquals(HardwareDelegate.CPU, isolatedSelector.selectDelegate(fingerprint.stableKey, unsafeSpread))
        val unstableResultStore = InMemoryDeviceProfileStore().also {
            runBlocking { it.saveResult(measured(profile(fingerprint)).copy(status = DeviceBenchmarkStatus.UNSTABLE)) }
        }
        val unstableSafety = InMemoryGpuRuntimeSafetyStore()
        assertFalse("UNSTABLE is rejected without a GPU_DISABLED latch", unstableSafety.isGpuDisabled(fingerprint.stableKey, now))
        assertEquals(
            "An explicitly UNSTABLE result cannot enable GPU",
            HardwareDelegate.CPU,
            HardwareDelegateSelector(unstableResultStore, unstableSafety) { now }
                .selectDelegate(fingerprint.stableKey)
        )
    }

    @Test
    fun androidGpuDisableLatchIsClearedWhenActiveFingerprintChanges() {
        val prefs = context.getSharedPreferences("gpu-reset-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val safety = AndroidGpuRuntimeSafetyStore(prefs)
        val changedFingerprint = fingerprint.copy(osBuild = "build-C")

        assertTrue(safety.disableGpu(fingerprint.stableKey, BenchmarkDiagnosticReason.GPU_BACKEND_ERROR))
        assertTrue(safety.isGpuDisabled(fingerprint.stableKey))
        assertTrue(safety.drainTransitions().any { it.kind == GpuSafetyTransitionKind.LOCKED })
        assertFalse(safety.isGpuDisabled(changedFingerprint.stableKey))
        val resetTransition = safety.drainTransitions().single { it.kind == GpuSafetyTransitionKind.UNLOCKED }
        assertEquals(BenchmarkDiagnosticReason.GPU_UNLOCK_FINGERPRINT_CHANGED, resetTransition.reason)
        assertEquals(fingerprint.stableKey, resetTransition.previousFingerprintHash)

        val reopened = AndroidGpuRuntimeSafetyStore(prefs)
        assertFalse(reopened.isGpuDisabled(changedFingerprint.stableKey))
        assertFalse("A fingerprint change resets the former latch", reopened.isGpuDisabled(fingerprint.stableKey))
    }

    @Test
    fun gpuBackendFailureFallsBackToCpuAndPersistsGpuDisabledAcrossStoreRecreation() {
        val prefs = context.getSharedPreferences("gpu-disable-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val runtimeSafety = AndroidGpuRuntimeSafetyStore(prefs)
        val profiles = InMemoryDeviceProfileStore().also { runBlocking { it.saveResult(measured(profile(fingerprint))) } }
        var gpuCalls = 0
        var cpuCalls = 0
        val executor = InferenceDelegateExecutor { backend, _ ->
            when (backend) {
                HardwareDelegate.GPU -> {
                    gpuCalls++
                    Result.failure(IllegalStateException("injected GPU backend failure"))
                }
                HardwareDelegate.CPU -> {
                    cpuCalls++
                    Result.success(delegateOutput(response("cpu")))
                }
            }
        }
        val diagnostics = diagnostics()
        val engine = engine(profiles, runtimeSafety, executor, diagnostics)

        assertEquals("cpu", engine.generate(request()).getOrThrow().text)
        assertTrue(runtimeSafety.isGpuDisabled(fingerprint.stableKey))
        assertEquals(1, gpuCalls)
        assertEquals(1, cpuCalls)
        assertTrue(diagnostics.readEvents().any {
            it.type == BenchmarkDiagnosticType.GPU_FALLBACK &&
                it.reason == BenchmarkDiagnosticReason.GPU_BACKEND_ERROR
        })
        assertTrue(diagnostics.readEvents().any {
            it.type == BenchmarkDiagnosticType.GPU_LOCKED &&
                it.reason == BenchmarkDiagnosticReason.GPU_BACKEND_ERROR
        })

        val reopenedSafety = AndroidGpuRuntimeSafetyStore(prefs)
        assertTrue(reopenedSafety.isGpuDisabled(fingerprint.stableKey))
        assertEquals("Canary ordinal is persistent across store recreation", 2, reopenedSafety.nextCanaryOrdinal(fingerprint.stableKey))
        val secondEngine = engine(profiles, reopenedSafety, executor, diagnostics)
        assertEquals(HardwareDelegate.CPU, secondEngine.selectedDelegate)
        assertEquals("cpu", secondEngine.generate(request()).getOrThrow().text)
        assertEquals(1, gpuCalls)
        assertEquals(2, cpuCalls)
    }

    @Test
    fun memoryPressureSignalFailureFailsClosedToCpu() {
        val profiles = InMemoryDeviceProfileStore().also { runBlocking { it.saveResult(measured(profile(fingerprint))) } }
        val runtimeSafety = InMemoryGpuRuntimeSafetyStore()
        var gpuCalls = 0
        var cpuCalls = 0
        val engine = LocalInferenceEngine(
            delegateSelector = HardwareDelegateSelector(profiles, runtimeSafety) { now },
            deviceFingerprint = fingerprint.stableKey,
            delegateBackendExecutor = InferenceDelegateExecutor { backend, _ ->
                if (backend == HardwareDelegate.GPU) gpuCalls++ else cpuCalls++
                Result.success(delegateOutput(response(backend.name.lowercase())))
            },
            memoryPressureSignalProvider = MemoryPressureSignalProvider { error("signal unavailable") },
            onMemoryPressureFallback = { error("mode transition is best-effort") },
            clock = { now }
        )

        assertEquals("cpu", engine.generate(request()).getOrThrow().text)
        assertEquals(0, gpuCalls)
        assertEquals(1, cpuCalls)
        assertFalse(runtimeSafety.isGpuDisabled(fingerprint.stableKey))
    }

    @Test
    fun lowMemoryModeResourceExhaustionUsesSeparateMemoryFallbackWithoutGpuDisable() {
        val profiles = InMemoryDeviceProfileStore().also { runBlocking { it.saveResult(measured(profile(fingerprint))) } }
        val runtimeSafety = InMemoryGpuRuntimeSafetyStore()
        repeat(HardwareBenchmarkAssumptions.GPU_CANARY_INVOCATIONS) {
            runtimeSafety.nextCanaryOrdinal(fingerprint.stableKey)
        }
        var lowMemoryMode = false
        var gpuCalls = 0
        var cpuCalls = 0
        val engine = LocalInferenceEngine(
            delegateSelector = HardwareDelegateSelector(profiles, runtimeSafety) { now },
            deviceFingerprint = fingerprint.stableKey,
            delegateBackendExecutor = InferenceDelegateExecutor { backend, _ ->
                when (backend) {
                    HardwareDelegate.GPU -> {
                        gpuCalls++
                        Result.failure(
                            GpuDelegateExecutionException(
                                GpuDelegateFailureCode.DEVICE_LOST,
                                AgentModelException(InferenceError.ResourceExhausted("injected pressure"))
                            )
                        )
                    }
                    HardwareDelegate.CPU -> {
                        cpuCalls++
                        Result.success(delegateOutput(response("cpu")))
                    }
                }
            },
            modeDelegateOverride = { if (lowMemoryMode) HardwareDelegate.CPU else null },
            onMemoryPressureFallback = { lowMemoryMode = true },
            clock = { now }
        )

        assertEquals("cpu", engine.generate(request()).getOrThrow().text)
        assertTrue(lowMemoryMode)
        assertFalse(runtimeSafety.isGpuDisabled(fingerprint.stableKey))
        assertEquals(HardwareDelegate.CPU, engine.selectedDelegate)
        assertEquals(1, gpuCalls)
        assertEquals(1, cpuCalls)
    }

    @Test
    fun canaryMissingPrefillTelemetryFailsClosed() {
        val profiles = InMemoryDeviceProfileStore().also { runBlocking { it.saveResult(measured(profile(fingerprint))) } }
        val runtimeSafety = InMemoryGpuRuntimeSafetyStore()
        val diag = diagnostics()
        val engine = LocalInferenceEngine(
            delegateSelector = HardwareDelegateSelector(profiles, runtimeSafety) { now },
            deviceFingerprint = fingerprint.stableKey,
            delegateBackendExecutor = InferenceDelegateExecutor { backend, _ ->
                Result.success(delegateOutput(response(if (backend == HardwareDelegate.GPU) "gpu" else "cpu")))
            },
            diagnostics = diag,
            clock = { now }
        )

        assertEquals("cpu", engine.generate(request()).getOrThrow().text)
        assertTrue(runtimeSafety.isGpuDisabled(fingerprint.stableKey))
        assertTrue(diag.readEvents().any {
            it.type == BenchmarkDiagnosticType.CANARY &&
                it.reason == BenchmarkDiagnosticReason.CANARY_TELEMETRY_MISSING &&
                it.result == BenchmarkDiagnosticResult.GPU_DISABLED
        })
        assertTrue(diag.readEvents().any { it.type == BenchmarkDiagnosticType.GPU_LOCKED })
        assertEquals(GpuLockStatus.DISABLED, diag.readEvents().last { it.type == BenchmarkDiagnosticType.GPU_LOCKED }
            .gpuLockSnapshot?.status)
    }

    @Test
    fun singleTransientCanaryWarningFallsBackForThisEngineSessionWithoutDurableLock() {
        val profiles = InMemoryDeviceProfileStore().also {
            runBlocking { it.saveResult(measured(profile(fingerprint, p95Ms = 100.0))) }
        }
        val runtimeSafety = InMemoryGpuRuntimeSafetyStore()
        val diag = diagnostics()
        var gpuCalls = 0
        var cpuCalls = 0
        val engine = LocalInferenceEngine(
            delegateSelector = HardwareDelegateSelector(profiles, runtimeSafety) { now },
            deviceFingerprint = fingerprint.stableKey,
            delegateBackendExecutor = InferenceDelegateExecutor { backend, _ ->
                when (backend) {
                    HardwareDelegate.GPU -> {
                        gpuCalls++
                        Result.success(delegateOutput(response("gpu"), prefillLatencyMs = 250L))
                    }
                    HardwareDelegate.CPU -> {
                        cpuCalls++
                        Result.success(delegateOutput(response("cpu")))
                    }
                }
            },
            memoryPressureSignalProvider = MemoryPressureSignalProvider { false },
            diagnostics = diag,
            clock = { now }
        )

        assertEquals("cpu", engine.generate(request()).getOrThrow().text)
        assertFalse(runtimeSafety.isGpuDisabled(fingerprint.stableKey))
        assertEquals(HardwareDelegate.CPU, engine.selectedDelegate)
        assertEquals("cpu", engine.generate(request()).getOrThrow().text)
        assertEquals("The same engine session must not retry GPU after its transient warning", 1, gpuCalls)
        assertEquals(2, cpuCalls)
        val warning = diag.readEvents().single { it.type == BenchmarkDiagnosticType.GPU_WARNING }
        assertEquals(now, warning.timestampEpochMs)
        assertEquals(BenchmarkDiagnosticReason.CANARY_LATENCY, warning.reason)
        assertEquals(1, warning.transientWarningCount)
        assertFalse(diag.readEvents().any { it.type == BenchmarkDiagnosticType.GPU_LOCKED })
    }

    @Test
    fun inferenceDiagnosticsNeverPersistMarkedPromptContextOrResponseText() {
        val marker = "SENTINEL_PRIVATE_INFERENCE_CONTENT_7f91"
        val profiles = InMemoryDeviceProfileStore().also {
            runBlocking { it.saveResult(measured(profile(fingerprint))) }
        }
        val runtimeSafety = InMemoryGpuRuntimeSafetyStore()
        val prefs = context.getSharedPreferences("diag-content-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val diag = BenchmarkDiagnosticsStore(prefs)
        val engine = LocalInferenceEngine(
            delegateSelector = HardwareDelegateSelector(profiles, runtimeSafety) { now },
            deviceFingerprint = fingerprint.stableKey,
            delegateBackendExecutor = InferenceDelegateExecutor { backend, _ ->
                Result.success(delegateOutput(response(if (backend == HardwareDelegate.GPU) marker else "cpu"), 100L))
            },
            memoryPressureSignalProvider = MemoryPressureSignalProvider { false },
            diagnostics = diag,
            clock = { now }
        )

        val markedRequest = request().copy(prompt = marker, context = marker)
        assertEquals(marker, engine.generate(markedRequest).getOrThrow().text)
        val persistedDiagnostics = buildString {
            append(diag.readEvents())
            append(diag.renderTextReport())
            append(prefs.all.values.joinToString(separator = " "))
        }
        assertFalse(persistedDiagnostics.contains(marker))
    }

    @Test
    fun rollingWarningsAcrossSessionsLockOnlyAtProvisionalThreshold() {
        val profiles = InMemoryDeviceProfileStore().also {
            runBlocking { it.saveResult(measured(profile(fingerprint, p95Ms = 100.0))) }
        }
        val prefs = context.getSharedPreferences("gpu-warning-limit-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val diag = diagnostics()
        val warningLimit = HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_LIMIT
        val warningWindowMs = HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_WINDOW_MS
        val sessionSpacingMs = (warningWindowMs - 1L) / (warningLimit - 1)

        repeat(warningLimit) { index ->
            // Place the final injected session just inside the 24-hour boundary.
            val sessionTime = now + index * sessionSpacingMs
            assertTrue(sessionTime - now < warningWindowMs)
            val runtimeSafety = AndroidGpuRuntimeSafetyStore(prefs)
            val engine = LocalInferenceEngine(
                delegateSelector = HardwareDelegateSelector(profiles, runtimeSafety) { sessionTime },
                deviceFingerprint = fingerprint.stableKey,
                delegateBackendExecutor = InferenceDelegateExecutor { backend, _ ->
                    if (backend == HardwareDelegate.GPU) {
                        Result.success(delegateOutput(response("gpu"), prefillLatencyMs = 250L))
                    } else Result.success(delegateOutput(response("cpu")))
                },
                memoryPressureSignalProvider = MemoryPressureSignalProvider { false },
                diagnostics = diag,
                clock = { sessionTime }
            )
            assertEquals("cpu", engine.generate(request()).getOrThrow().text)
            assertEquals(index == warningLimit - 1, runtimeSafety.isGpuDisabled(fingerprint.stableKey, sessionTime))
        }

        val warningEvents = diag.readEvents().filter { it.type == BenchmarkDiagnosticType.GPU_WARNING }
        assertEquals(warningLimit, warningEvents.size)
        assertEquals((1..warningLimit).toList(), warningEvents.mapNotNull { it.transientWarningCount })
        val lockEvent = diag.readEvents().single { it.type == BenchmarkDiagnosticType.GPU_LOCKED }
        assertEquals(BenchmarkDiagnosticReason.GPU_TRANSIENT_WARNING_LIMIT, lockEvent.reason)
        assertEquals(BenchmarkDiagnosticReason.CANARY_LATENCY, lockEvent.triggerReason)
        assertEquals(GpuLockStatus.DISABLED, lockEvent.gpuLockSnapshot?.status)
        assertTrue(diag.renderTextReport().contains("unlockCondition=VALID_FRESH_PROFILE_AFTER_WAIT"))
    }

    @Test
    fun transientWarningsExpireOutsideRollingWindow() {
        val prefs = context.getSharedPreferences("gpu-warning-expiry-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val firstStore = AndroidGpuRuntimeSafetyStore(prefs)
        val first = firstStore.recordTransientWarning(
            fingerprint.stableKey,
            BenchmarkDiagnosticReason.CANARY_LATENCY,
            now
        )
        assertEquals(1, first.activeWarningCount)
        val windowMs = HardwareBenchmarkAssumptions.GPU_TRANSIENT_WARNING_WINDOW_MS
        val exactExpiryBoundary = now + windowMs
        val reopened = AndroidGpuRuntimeSafetyStore(prefs)
        // The store uses timestamp arguments, not wall-clock time; cutoff is exclusive.
        assertEquals(0, reopened.safetySnapshot(fingerprint.stableKey, exactExpiryBoundary).activeTransientWarningCount)

        val second = reopened.recordTransientWarning(
            fingerprint.stableKey,
            BenchmarkDiagnosticReason.MEMORY_PRESSURE,
            exactExpiryBoundary
        )
        assertEquals(1, second.activeWarningCount)
        assertFalse(second.lockCreated)
        assertEquals(
            1,
            reopened.safetySnapshot(fingerprint.stableKey, exactExpiryBoundary + 1L).activeTransientWarningCount
        )
        assertFalse(reopened.isGpuDisabled(fingerprint.stableKey, exactExpiryBoundary + 1L))
    }

    @Test
    fun hardLockReopensOnlyForQualifyingPostWaitProfileAndRestartsCanary() {
        val runtimeSafety = InMemoryGpuRuntimeSafetyStore()
        assertTrue(runtimeSafety.disableGpu(fingerprint.stableKey, BenchmarkDiagnosticReason.GPU_BACKEND_ERROR, now))
        val eligibleAt = now + HardwareBenchmarkAssumptions.GPU_REENABLE_MIN_WAIT_MS
        val staleProfile = profile(fingerprint).copy(measuredAtEpochMs = now)
        assertFalse(runtimeSafety.tryUnlockForFreshProfile(
            fingerprint.stableKey,
            measured(staleProfile),
            eligibleAt
        ))
        assertTrue(runtimeSafety.isGpuDisabled(fingerprint.stableKey, eligibleAt))

        val freshProfile = profile(fingerprint).copy(measuredAtEpochMs = eligibleAt)
        assertTrue(runtimeSafety.tryUnlockForFreshProfile(
            fingerprint.stableKey,
            measured(freshProfile),
            eligibleAt
        ))
        assertFalse(runtimeSafety.isGpuDisabled(fingerprint.stableKey, eligibleAt))
        val unlock = runtimeSafety.drainTransitions().single { it.kind == GpuSafetyTransitionKind.UNLOCKED }
        assertEquals(BenchmarkDiagnosticReason.GPU_UNLOCK_FRESH_PROFILE, unlock.reason)
        assertEquals(GpuUnlockCondition.VALID_FRESH_PROFILE_AFTER_WAIT, unlock.unlockCondition)
        assertEquals("The canary starts over after re-enable", 1, runtimeSafety.nextCanaryOrdinal(fingerprint.stableKey, eligibleAt))
    }

    @Test
    fun freshProfileUnlockIsRecordedWithCurrentGpuStateAndUnlockCondition() {
        val runtimeSafety = InMemoryGpuRuntimeSafetyStore()
        assertTrue(runtimeSafety.disableGpu(fingerprint.stableKey, BenchmarkDiagnosticReason.GPU_BACKEND_ERROR, now))
        val eligibleAt = now + HardwareBenchmarkAssumptions.GPU_REENABLE_MIN_WAIT_MS
        val profiles = InMemoryDeviceProfileStore().also {
            runBlocking {
                it.saveResult(measured(profile(fingerprint).copy(measuredAtEpochMs = eligibleAt)))
            }
        }
        val diag = diagnostics()
        val engine = LocalInferenceEngine(
            delegateSelector = HardwareDelegateSelector(profiles, runtimeSafety) { eligibleAt },
            deviceFingerprint = fingerprint.stableKey,
            delegateBackendExecutor = InferenceDelegateExecutor { _, _ ->
                Result.success(delegateOutput(response("ok"), prefillLatencyMs = 100L))
            },
            memoryPressureSignalProvider = MemoryPressureSignalProvider { false },
            diagnostics = diag,
            clock = { eligibleAt }
        )

        assertEquals(HardwareDelegate.GPU, engine.selectedDelegate)
        val unlock = diag.readEvents().single { it.type == BenchmarkDiagnosticType.GPU_UNLOCKED }
        assertEquals(BenchmarkDiagnosticReason.GPU_UNLOCK_FRESH_PROFILE, unlock.reason)
        assertEquals(GpuLockStatus.ENABLED, unlock.gpuLockSnapshot?.status)
        assertEquals(GpuUnlockCondition.VALID_FRESH_PROFILE_AFTER_WAIT, unlock.gpuLockSnapshot?.unlockCondition)
        assertTrue(diag.renderTextReport().contains("GPU lock: status=ENABLED"))
    }

    @Test
    fun canaryMemoryPressureAlsoWarnsWithoutImmediateDurableLock() {
        val profiles = InMemoryDeviceProfileStore().also {
            runBlocking { it.saveResult(measured(profile(fingerprint))) }
        }
        val runtimeSafety = InMemoryGpuRuntimeSafetyStore()
        val pressureDiagnostics = diagnostics()
        var pressureChecks = 0
        var fallback = false
        val engine = LocalInferenceEngine(
            delegateSelector = HardwareDelegateSelector(profiles, runtimeSafety) { now },
            deviceFingerprint = fingerprint.stableKey,
            delegateBackendExecutor = InferenceDelegateExecutor { backend, _ ->
                if (backend == HardwareDelegate.GPU) {
                    Result.success(delegateOutput(response("gpu"), prefillLatencyMs = 100L))
                } else Result.success(delegateOutput(response("cpu")))
            },
            memoryPressureSignalProvider = MemoryPressureSignalProvider { ++pressureChecks > 1 },
            onMemoryPressureFallback = { fallback = true },
            diagnostics = pressureDiagnostics,
            clock = { now }
        )

        assertEquals("cpu", engine.generate(request()).getOrThrow().text)
        assertTrue(fallback)
        assertFalse("A first canary memory warning is transient", runtimeSafety.isGpuDisabled(fingerprint.stableKey))
        assertTrue(pressureDiagnostics.readEvents().any {
            it.type == BenchmarkDiagnosticType.CANARY &&
                it.reason == BenchmarkDiagnosticReason.MEMORY_PRESSURE &&
                it.result == BenchmarkDiagnosticResult.TRANSIENT_WARNING
        })
        assertTrue(pressureDiagnostics.readEvents().any {
            it.type == BenchmarkDiagnosticType.GPU_WARNING && it.reason == BenchmarkDiagnosticReason.MEMORY_PRESSURE
        })
        assertEquals(HardwareDelegate.CPU, engine.selectedDelegate)
    }

    private fun engine(
        profiles: DeviceProfileStore,
        safety: GpuRuntimeSafetyStore,
        executor: InferenceDelegateExecutor,
        diagnostics: BenchmarkDiagnosticsStore
    ) = LocalInferenceEngine(
        delegateSelector = HardwareDelegateSelector(profiles, safety) { now },
        deviceFingerprint = fingerprint.stableKey,
        delegateBackendExecutor = executor,
        diagnostics = diagnostics,
        clock = { now }
    )

    private fun profile(fingerprint: DeviceFingerprint, p95Ms: Double = 200.0) = DevicePerformanceProfile(
        fingerprint = fingerprint,
        contextTokens = HardwareBenchmarkAssumptions.CONTEXT_TOKENS,
        warmupRuns = HardwareBenchmarkAssumptions.MAX_WARMUP_RUNS,
        sampleCount = HardwareBenchmarkAssumptions.MIN_SAMPLES_FOR_P99,
        gpuPrefillLatency = LatencyStatistics(100.0, p95Ms, p95Ms * 1.2, 10.0),
        gpuDecodeRate = DecodeRateStatistics(18.0, 20.0, 22.0, 1.0),
        gpuPeakAnonymousMemoryMb = 400L,
        lmkKillWatermarkMb = 700L,
        measuredAtEpochMs = now,
        measurementLabel = BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF,
        conditions = conditions()
    )

    private fun measured(profile: DevicePerformanceProfile) = DeviceBenchmarkResult(
        fingerprint = profile.fingerprint,
        status = DeviceBenchmarkStatus.MEASURED,
        profile = profile,
        updatedAtEpochMs = profile.measuredAtEpochMs,
        measurementLabel = profile.measurementLabel,
        conditions = profile.conditions
    )

    private fun conditions() = BenchmarkConditions(
        conditionAt(screenOn = false), conditionAt(screenOn = false)
    )

    private fun conditionAt(screenOn: Boolean) = BenchmarkConditionSnapshot(
        isCharging = true,
        batteryPercent = 80,
        screenInteractive = screenOn,
        deviceIdle = true,
        thermalStatus = BenchmarkThermalStatus.NORMAL,
        capturedAtEpochMs = now
    )

    private fun request() = InferenceRequest(
        prompt = "do-not-log-this-prompt",
        context = "context",
        params = GenerateParams(max_tokens = 32, temperature = 0.0f, top_p = 0.9f)
    )

    private fun delegateOutput(response: InferenceResponse, prefillLatencyMs: Long? = null) =
        InferenceDelegateOutput(response, prefillLatencyMs)

    private fun response(text: String) = InferenceResponse(
        text = text,
        usage = TokenUsage(prompt_tokens = 1, completion_tokens = 1),
        finish_reason = "stop"
    )

    private fun diagnostics() = BenchmarkDiagnosticsStore(
        context.getSharedPreferences("safety-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    )
}
