package inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkRequest
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.UUID

@Config(sdk = [28], manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class HardwareBenchmarkWorkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 20_000L

    @Test
    fun productionMeasurementReturnsOnlyUnavailableOrNotMeasuredStatuses() = runBlocking {
        val measurement = ProductionDeviceBenchmarkMeasurement()
        val missingRuntime = availableFingerprint().copy(modelSha256 = null, runtimeVersion = null)
        assertTrue(measurement.measure(missingRuntime) is BenchmarkMeasurementOutcome.ModelUnavailable)
        assertTrue(measurement.measure(availableFingerprint()) is BenchmarkMeasurementOutcome.NotMeasured)
    }

    @Test
    fun schedulerUsesUniqueKeepWorkAndOnlyChargingAndIdleConstraints() {
        val fingerprint = availableFingerprint()
        val enqueuedNames = mutableListOf<String>()
        val enqueuedPolicies = mutableListOf<ExistingWorkPolicy>()
        var request: OneTimeWorkRequest? = null
        var monitorEnsureCalls = 0
        val port = UniqueWorkEnqueuePort { name, policy, workRequest ->
            enqueuedNames += name
            enqueuedPolicies += policy
            request = workRequest
        }
        val store = InMemoryDeviceProfileStore()
        val scheduler = HardwareBenchmarkWorkScheduler(
            fingerprintProvider = DeviceFingerprintProvider { fingerprint },
            profileStore = store,
            enqueuePort = port,
            diagnostics = diagnostics(),
            clock = { now },
            starvationMonitorEnqueuer = BenchmarkStarvationMonitorEnqueuer { monitorEnsureCalls++ }
        )

        scheduler.enqueue()
        scheduler.enqueue()

        val expectedPlan = hardwareBenchmarkWorkPlan(fingerprint)
        assertEquals(listOf(expectedPlan.uniqueName, expectedPlan.uniqueName), enqueuedNames)
        assertEquals(listOf(ExistingWorkPolicy.KEEP, ExistingWorkPolicy.KEEP), enqueuedPolicies)
        assertEquals(2, monitorEnsureCalls)
        assertTrue(expectedPlan.requiresCharging)
        assertTrue(expectedPlan.requiresDeviceIdle)
        assertEquals(MAX_HARDWARE_BENCHMARK_RETRIES, expectedPlan.maxRetryCount)
        assertNotNull(request)
        assertTrue(request!!.workSpec.constraints.requiresCharging())
        assertTrue(request!!.workSpec.constraints.requiresDeviceIdle())
        assertEquals(fingerprint.stableKey, request!!.workSpec.input.getString("fingerprint_key"))
        assertEquals(BackoffPolicy.EXPONENTIAL, request!!.workSpec.backoffPolicy)
        assertEquals(WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS, request!!.workSpec.backoffDelayDuration)

        runBlocking {
            store.saveResult(result(fingerprint, DeviceBenchmarkStatus.MODEL_UNAVAILABLE))
        }
        val callsBeforeTerminalResult = enqueuedNames.size
        scheduler.enqueue()
        assertEquals(callsBeforeTerminalResult, enqueuedNames.size)
    }

    @Test
    fun workerMeasuresWithScreenOffAndStoresBackgroundLabelAndSnapshots() = runBlocking {
        val fingerprint = availableFingerprint()
        val store = InMemoryDeviceProfileStore()
        var measured = false
        var idleDuration: Long? = null
        val diag = diagnostics()
        val deps = dependencies(
            fingerprint = fingerprint,
            store = store,
            diagnostics = diag,
            measurement = DeviceBenchmarkMeasurement {
                measured = true
                BenchmarkMeasurementOutcome.Measured(profile(it))
            },
            condition = snapshot(screenInteractive = false),
            idleGate = ContinuousDeviceIdleGate { duration -> idleDuration = duration; true }
        )

        val outcome = worker(deps, fingerprint).doWork()
        assertEquals(ListenableWorker.Result.success(), outcome)
        assertTrue(measured)
        assertEquals(HardwareBenchmarkAssumptions.MIN_IDLE_DURATION_MS, idleDuration)
        val saved = store.getResult(fingerprint)!!
        assertEquals(DeviceBenchmarkStatus.MEASURED, saved.status)
        assertEquals(BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF, saved.measurementLabel)
        assertEquals(false, saved.conditions!!.beforeMeasurement.screenInteractive)
        assertEquals(false, saved.conditions!!.afterMeasurement.screenInteractive)
        assertEquals(BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF, saved.profile!!.measurementLabel)
        assertEquals(DiagnosticBackend.GPU, diag.readEvents().last().selectedBackend)
        assertEquals(BenchmarkDiagnosticResult.MEASURED, diag.readEvents().last().result)
    }

    @Test
    fun api28ThermalStatusIsUnknownAndWorkerTreatsItConservatively() {
        assertEquals(
            BenchmarkThermalStatus.UNKNOWN,
            AndroidDeviceConditionSnapshotProvider(context).snapshot(now).thermalStatus
        )
    }

    @Test
    fun screenOnIsRecordedButDoesNotRejectTheBackgroundWorker() = runBlocking {
        val fingerprint = availableFingerprint()
        val store = InMemoryDeviceProfileStore()
        var measured = false
        val deps = dependencies(
            fingerprint,
            store,
            diagnostics(),
            DeviceBenchmarkMeasurement { measured = true; BenchmarkMeasurementOutcome.NotMeasured("fake") },
            condition = snapshot(screenInteractive = true)
        )

        assertEquals(ListenableWorker.Result.success(), worker(deps, fingerprint).doWork())
        assertTrue(measured)
        val saved = store.getResult(fingerprint)!!
        assertEquals(BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF, saved.measurementLabel)
        assertTrue(saved.conditions!!.beforeMeasurement.screenInteractive)
    }

    @Test
    fun highOrUnknownThermalStateRetriesAndNeverSavesAProfile() = runBlocking {
        val fingerprint = availableFingerprint()
        val store = InMemoryDeviceProfileStore()
        var measured = false
        val diag = diagnostics()
        val deps = dependencies(
            fingerprint,
            store,
            diag,
            DeviceBenchmarkMeasurement { measured = true; BenchmarkMeasurementOutcome.Measured(profile(it)) },
            condition = snapshot(thermal = BenchmarkThermalStatus.UNKNOWN)
        )
        val worker = worker(deps, fingerprint, attemptCount = MAX_HARDWARE_BENCHMARK_RETRIES)

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertFalse(measured)
        val saved = store.getResult(fingerprint)!!
        assertEquals(DeviceBenchmarkStatus.THERMAL_HIGH, saved.status)
        assertNull(saved.profile)
        assertEquals(BenchmarkDiagnosticReason.THERMAL_UNKNOWN, diag.readEvents().last().reason)
    }

    @Test
    fun thermalRiseAfterMeasurementDiscardsTheProfileAndLeavesRetryableStatus() = runBlocking {
        val fingerprint = availableFingerprint()
        val store = InMemoryDeviceProfileStore()
        var snapshotsTaken = 0
        var measurements = 0
        val provider = DeviceConditionSnapshotProvider { timestamp ->
            val thermal = if (snapshotsTaken++ >= 2) BenchmarkThermalStatus.SEVERE else BenchmarkThermalStatus.NORMAL
            snapshot(thermal = thermal).copy(capturedAtEpochMs = timestamp)
        }
        val deps = dependencies(
            fingerprint,
            store,
            diagnostics(),
            DeviceBenchmarkMeasurement { measurements++; BenchmarkMeasurementOutcome.Measured(profile(it)) },
            conditionProvider = provider
        )

        assertEquals(ListenableWorker.Result.retry(), worker(deps, fingerprint, attemptCount = 0).doWork())
        assertNull(store.getResult(fingerprint))
        assertEquals(1, measurements)
        assertEquals(ListenableWorker.Result.success(), worker(deps, fingerprint, attemptCount = MAX_HARDWARE_BENCHMARK_RETRIES).doWork())
        val final = store.getResult(fingerprint)!!
        assertEquals(DeviceBenchmarkStatus.THERMAL_HIGH, final.status)
        assertNull(final.profile)
        assertEquals(BenchmarkThermalStatus.SEVERE, final.conditions!!.afterMeasurement.thermalStatus)
    }

    @Test
    fun unstableStatisticsAreRecordedButNeverAcceptedOrSelected() = runBlocking {
        val fingerprint = availableFingerprint()
        val store = InMemoryDeviceProfileStore()
        val deps = dependencies(
            fingerprint,
            store,
            diagnostics(),
            DeviceBenchmarkMeasurement { BenchmarkMeasurementOutcome.Measured(profile(it, unstable = true)) }
        )

        assertEquals(ListenableWorker.Result.success(), worker(deps, fingerprint).doWork())
        val saved = store.getResult(fingerprint)!!
        assertEquals(DeviceBenchmarkStatus.UNSTABLE, saved.status)
        assertNull(saved.profile)
        assertEquals(HardwareDelegate.CPU, HardwareDelegateSelector(store, InMemoryGpuRuntimeSafetyStore())
            .selectDelegate(fingerprint.stableKey))
    }

    @Test
    fun cancelledWorkerRecordsStoppedBySystemWithoutSavingPartialResult() = runBlocking {
        val fingerprint = availableFingerprint()
        val store = InMemoryDeviceProfileStore()
        val diag = diagnostics()
        val measurementStarted = CompletableDeferred<Unit>()
        val deps = dependencies(
            fingerprint,
            store,
            diag,
            DeviceBenchmarkMeasurement {
                measurementStarted.complete(Unit)
                awaitCancellation()
            }
        )
        val job = async { runCatching { worker(deps, fingerprint).doWork() } }
        measurementStarted.await()
        job.cancelAndJoin()

        assertNull(store.getResult(fingerprint))
        val stopEvent = diag.readEvents().last()
        assertEquals(BenchmarkDiagnosticResult.STOPPED_BY_SYSTEM, stopEvent.result)
        assertEquals(BenchmarkDiagnosticReason.WORKER_STOPPED, stopEvent.reason)
    }

    @Test
    fun lossOfChargingRetriesAndDoesNotPersistMeasurements() = runBlocking {
        val fingerprint = availableFingerprint()
        val store = InMemoryDeviceProfileStore()
        val diag = diagnostics()
        val deps = dependencies(
            fingerprint,
            store,
            diag,
            DeviceBenchmarkMeasurement { BenchmarkMeasurementOutcome.Measured(profile(it)) },
            condition = snapshot(charging = false)
        )

        assertEquals(ListenableWorker.Result.retry(), worker(deps, fingerprint, attemptCount = 0).doWork())
        assertNull(store.getResult(fingerprint))
        assertEquals(ListenableWorker.Result.success(), worker(deps, fingerprint, attemptCount = MAX_HARDWARE_BENCHMARK_RETRIES).doWork())
        assertEquals(BenchmarkDiagnosticReason.NOT_CHARGING, diag.readEvents().last().reason)
        assertNull(store.getResult(fingerprint)!!.profile)
    }

    @Test
    fun lowBatteryRetriesThenRecordsNotMeasuredWithoutAProfile() = runBlocking {
        val fingerprint = availableFingerprint()
        val store = InMemoryDeviceProfileStore()
        val diag = diagnostics()
        val deps = dependencies(
            fingerprint,
            store,
            diag,
            DeviceBenchmarkMeasurement { BenchmarkMeasurementOutcome.Measured(profile(it)) },
            condition = snapshot(battery = 30)
        )

        assertEquals(ListenableWorker.Result.retry(), worker(deps, fingerprint, attemptCount = 0).doWork())
        assertNull(store.getResult(fingerprint))
        assertEquals(ListenableWorker.Result.success(), worker(deps, fingerprint, attemptCount = MAX_HARDWARE_BENCHMARK_RETRIES).doWork())
        val saved = store.getResult(fingerprint)!!
        assertEquals(DeviceBenchmarkStatus.NOT_MEASURED, saved.status)
        assertNull(saved.profile)
        assertEquals(BenchmarkDiagnosticReason.BATTERY_LOW, diag.readEvents().last().reason)
    }

    @Test
    fun modelUnavailableIsPersistedAndDoesNotRunMeasurement() = runBlocking {
        val fingerprint = availableFingerprint().copy(modelSha256 = null, runtimeVersion = null)
        val store = InMemoryDeviceProfileStore()
        var measured = false
        val deps = dependencies(
            fingerprint,
            store,
            diagnostics(),
            DeviceBenchmarkMeasurement { measured = true; BenchmarkMeasurementOutcome.Measured(profile(it)) }
        )

        assertEquals(ListenableWorker.Result.success(), worker(deps, fingerprint).doWork())
        assertFalse(measured)
        assertEquals(DeviceBenchmarkStatus.MODEL_UNAVAILABLE, store.getResult(fingerprint)!!.status)
    }

    private fun dependencies(
        fingerprint: DeviceFingerprint,
        store: DeviceProfileStore,
        diagnostics: BenchmarkDiagnosticsStore,
        measurement: DeviceBenchmarkMeasurement,
        condition: BenchmarkConditionSnapshot = snapshot(),
        idleGate: ContinuousDeviceIdleGate = ContinuousDeviceIdleGate { true },
        conditionProvider: DeviceConditionSnapshotProvider? = null,
        elapsedRealtimeMs: () -> Long = android.os.SystemClock::elapsedRealtime
    ) = HardwareBenchmarkWorkerDependencies(
        fingerprintProvider = DeviceFingerprintProvider { fingerprint },
        profileStore = store,
        measurement = measurement,
        idleGate = idleGate,
        conditionSnapshotProvider = conditionProvider ?: DeviceConditionSnapshotProvider { timestamp ->
            condition.copy(capturedAtEpochMs = timestamp)
        },
        diagnostics = diagnostics,
        clock = { now },
        elapsedRealtimeMs = elapsedRealtimeMs
    )

    private fun worker(
        dependencies: HardwareBenchmarkWorkerDependencies,
        fingerprint: DeviceFingerprint,
        attemptCount: Int = 0
    ): HardwareBenchmarkWorker = TestListenableWorkerBuilder<HardwareBenchmarkWorker>(context)
        .setInputData(fingerprint.toHardwareBenchmarkWorkData())
        .setRunAttemptCount(attemptCount)
        .setWorkerFactory(HardwareBenchmarkWorkerFactory { dependencies })
        .build()

    private fun diagnostics() = BenchmarkDiagnosticsStore(
        context.getSharedPreferences("worker-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    )

    private fun availableFingerprint() = DeviceFingerprint(
        device = "test-device",
        osBuild = "test-build",
        modelSha256 = "b".repeat(64),
        runtimeVersion = "fake-runtime"
    )

    private fun snapshot(
        charging: Boolean = true,
        battery: Int? = 80,
        screenInteractive: Boolean = false,
        idle: Boolean = true,
        thermal: BenchmarkThermalStatus = BenchmarkThermalStatus.NORMAL
    ) = BenchmarkConditionSnapshot(charging, battery, screenInteractive, idle, thermal, now)

    private fun profile(fingerprint: DeviceFingerprint, unstable: Boolean = false) = DevicePerformanceProfile(
        fingerprint = fingerprint,
        contextTokens = HardwareBenchmarkAssumptions.CONTEXT_TOKENS,
        warmupRuns = HardwareBenchmarkAssumptions.MAX_WARMUP_RUNS,
        sampleCount = HardwareBenchmarkAssumptions.MIN_SAMPLES_FOR_P99,
        gpuPrefillLatency = LatencyStatistics(100.0, 200.0, 250.0, if (unstable) 60.0 else 10.0),
        gpuDecodeRate = DecodeRateStatistics(18.0, 20.0, 22.0, 1.0),
        gpuPeakAnonymousMemoryMb = 400L,
        lmkKillWatermarkMb = 700L,
        measuredAtEpochMs = now
    )

    private fun result(fingerprint: DeviceFingerprint, status: DeviceBenchmarkStatus) =
        DeviceBenchmarkResult(fingerprint, status, reason = "test", updatedAtEpochMs = now)
}
