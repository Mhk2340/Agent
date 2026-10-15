package inference

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

const val MAX_HARDWARE_BENCHMARK_RETRIES = 3 // PROVISIONAL - pending GEN-PERF-1; transient retries, excluding the initial attempt.
const val APPROXIMATE_WORKER_LIMIT_MS = 10L * 60L * 1000L // PROVISIONAL; diagnostic classification only, does not enforce a timeout.
const val HARDWARE_BENCHMARK_WORK_TAG = "gen-edge-1-hardware-benchmark"
const val HARDWARE_BENCHMARK_STARVATION_WORK_NAME = "gen-edge-1.starvation-monitor"
const val HARDWARE_BENCHMARK_STARVATION_WORK_TAG = "gen-edge-1-starvation-monitor"

fun interface HardwareBenchmarkWorkEnqueuer {
    /** Enqueues work only; it must not execute a benchmark in the caller's process/thread. */
    fun enqueue()
}

/** Work parameters kept explicit so constraint and unique-work policy can be unit-tested. */
data class HardwareBenchmarkWorkPlan(
    val uniqueName: String,
    val existingWorkPolicy: ExistingWorkPolicy,
    val requiresCharging: Boolean,
    val requiresDeviceIdle: Boolean,
    val maxRetryCount: Int
)

fun hardwareBenchmarkWorkPlan(fingerprint: DeviceFingerprint): HardwareBenchmarkWorkPlan =
    HardwareBenchmarkWorkPlan(
        uniqueName = "gen-edge-1.hardware-benchmark.${fingerprint.stableKey}",
        existingWorkPolicy = ExistingWorkPolicy.KEEP,
        requiresCharging = true,
        requiresDeviceIdle = true,
        maxRetryCount = MAX_HARDWARE_BENCHMARK_RETRIES
    )

fun interface UniqueWorkEnqueuePort {
    fun enqueueUniqueWork(
        uniqueName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest
    )
}

fun interface BenchmarkStarvationMonitorEnqueuer {
    fun ensureScheduled()
}

class WorkManagerUniqueWorkEnqueuePort(context: Context) : UniqueWorkEnqueuePort, BenchmarkStarvationMonitorEnqueuer {
    private val appContext = context.applicationContext

    override fun enqueueUniqueWork(
        uniqueName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest
    ) {
        WorkManager.getInstance(appContext).enqueueUniqueWork(uniqueName, existingWorkPolicy, request)
    }

    override fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<HardwareBenchmarkStarvationMonitorWorker>(
            HardwareBenchmarkAssumptions.WORKER_STARVATION_DAYS,
            TimeUnit.DAYS
        ).addTag(HARDWARE_BENCHMARK_STARVATION_WORK_TAG).build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            HARDWARE_BENCHMARK_STARVATION_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}

class HardwareBenchmarkWorkScheduler(
    private val fingerprintProvider: DeviceFingerprintProvider,
    private val profileStore: DeviceProfileStore,
    private val enqueuePort: UniqueWorkEnqueuePort,
    private val diagnostics: BenchmarkDiagnosticsStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val starvationMonitorEnqueuer: BenchmarkStarvationMonitorEnqueuer? = null
) : HardwareBenchmarkWorkEnqueuer {
    override fun enqueue() {
        val fingerprint = fingerprintProvider.currentFingerprint()
        runCatching { starvationMonitorEnqueuer?.ensureScheduled() }.onFailure {
            diagnostics?.record(
                BenchmarkDiagnosticEvent(
                    timestampEpochMs = clock(),
                    type = BenchmarkDiagnosticType.WORK_ENQUEUED,
                    fingerprintHash = fingerprint.stableKey,
                    result = BenchmarkDiagnosticResult.NOT_MEASURED,
                    reason = BenchmarkDiagnosticReason.WORKER_FAILURE
                )
            )
        }
        val existing = profileStore.peek(fingerprint.stableKey)
        val now = clock()
        val validMeasurement = existing?.isFreshValidMeasurement(fingerprint.stableKey, now) == true
        if (validMeasurement || existing?.status == DeviceBenchmarkStatus.MODEL_UNAVAILABLE) {
            diagnostics?.clearQueueForFingerprint(fingerprint.stableKey)
            return
        }
        diagnostics?.noteEnqueued(fingerprint.stableKey, now, validResultExists = false)
        diagnostics?.checkStarvation(fingerprint.stableKey, now, validResultExists = false)

        val plan = hardwareBenchmarkWorkPlan(fingerprint)
        // WorkManager's device-idle constraint reflects system idle/Doze, not a precise
        // five-minute inactivity timer; the Worker adds a timed idle gate and recheck.
        val constraints = Constraints.Builder()
            .setRequiresCharging(plan.requiresCharging)
            .setRequiresDeviceIdle(plan.requiresDeviceIdle)
            .build()
        val request = OneTimeWorkRequestBuilder<HardwareBenchmarkWorker>()
            .setInputData(fingerprint.toHardwareBenchmarkWorkData())
            .setConstraints(constraints)
            // Explicit setBackoffCriteria is forbidden for WorkManager idle-mode requests.
            // Result.retry() uses WorkManager's default backoff; retryOrFinish caps retries.
            .addTag(HARDWARE_BENCHMARK_WORK_TAG)
            .build()

        try {
            enqueuePort.enqueueUniqueWork(
                plan.uniqueName,
                plan.existingWorkPolicy,
                request
            )
        } catch (error: Exception) {
            diagnostics?.clearQueueForFingerprint(fingerprint.stableKey)
            diagnostics?.record(
                BenchmarkDiagnosticEvent(
                    timestampEpochMs = clock(),
                    type = BenchmarkDiagnosticType.WORKER_EXECUTION,
                    fingerprintHash = fingerprint.stableKey,
                    result = BenchmarkDiagnosticResult.NOT_MEASURED,
                    reason = BenchmarkDiagnosticReason.WORKER_FAILURE
                )
            )
            throw error
        }
    }
}

fun DeviceFingerprint.toHardwareBenchmarkWorkData() = workDataOf(
    KEY_DEVICE to device,
    KEY_OS_BUILD to osBuild,
    KEY_MODEL_SHA256 to modelSha256,
    KEY_RUNTIME_VERSION to runtimeVersion,
    KEY_FINGERPRINT_KEY to stableKey
)

private fun fingerprintFromWorkData(data: androidx.work.Data): DeviceFingerprint? {
    val device = data.getString(KEY_DEVICE) ?: return null
    val osBuild = data.getString(KEY_OS_BUILD) ?: return null
    val modelSha256 = data.getString(KEY_MODEL_SHA256)
    val runtimeVersion = data.getString(KEY_RUNTIME_VERSION)
    val fingerprint = DeviceFingerprint(device, osBuild, modelSha256, runtimeVersion)
    return fingerprint.takeIf { it.stableKey == data.getString(KEY_FINGERPRINT_KEY) }
}

private const val KEY_DEVICE = "device"
private const val KEY_OS_BUILD = "os_build"
private const val KEY_MODEL_SHA256 = "model_sha256"
private const val KEY_RUNTIME_VERSION = "runtime_version"
private const val KEY_FINGERPRINT_KEY = "fingerprint_key"

fun interface DeviceConditionSnapshotProvider {
    fun snapshot(capturedAtEpochMs: Long): BenchmarkConditionSnapshot
}

class AndroidDeviceConditionSnapshotProvider(context: Context) : DeviceConditionSnapshotProvider {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Suppress("UnspecifiedRegisterReceiverFlag")
    override fun snapshot(capturedAtEpochMs: Long): BenchmarkConditionSnapshot {
        val percentage = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
        val status = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BenchmarkConditionSnapshot(
            isCharging = charging,
            batteryPercent = percentage,
            screenInteractive = powerManager.isInteractive,
            deviceIdle = powerManager.isDeviceIdleMode,
            thermalStatus = readThermalStatus(),
            capturedAtEpochMs = capturedAtEpochMs
        )
    }

    private fun readThermalStatus(): BenchmarkThermalStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return BenchmarkThermalStatus.UNKNOWN
        return readThermalStatusApi29()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun readThermalStatusApi29(): BenchmarkThermalStatus =
        when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> BenchmarkThermalStatus.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT -> BenchmarkThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> BenchmarkThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> BenchmarkThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> BenchmarkThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> BenchmarkThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> BenchmarkThermalStatus.SHUTDOWN
            else -> BenchmarkThermalStatus.UNKNOWN
        }
}

fun interface ContinuousDeviceIdleGate {
    /** Returns true only after the system idle state remains observable across the wait interval. */
    suspend fun awaitContinuousIdle(durationMs: Long): Boolean
}

class AndroidContinuousDeviceIdleGate(
    context: Context,
    private val wait: suspend (Long) -> Unit = { duration -> delay(duration) }
) : ContinuousDeviceIdleGate {
    private val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    override suspend fun awaitContinuousIdle(durationMs: Long): Boolean {
        if (!powerManager.isDeviceIdleMode) return false
        wait(durationMs)
        return powerManager.isDeviceIdleMode
    }
}

data class HardwareBenchmarkWorkerDependencies(
    val fingerprintProvider: DeviceFingerprintProvider,
    val profileStore: DeviceProfileStore,
    val measurement: DeviceBenchmarkMeasurement,
    val idleGate: ContinuousDeviceIdleGate,
    val conditionSnapshotProvider: DeviceConditionSnapshotProvider,
    val diagnostics: BenchmarkDiagnosticsStore,
    val clock: () -> Long = System::currentTimeMillis,
    val elapsedRealtimeMs: () -> Long = android.os.SystemClock::elapsedRealtime,
    val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
)

/** Application-owned dependency seam; production WorkManager uses its default reflective factory. */
data class BenchmarkStarvationMonitorDependencies(
    val fingerprintProvider: DeviceFingerprintProvider,
    val profileStore: DeviceProfileStore,
    val diagnostics: BenchmarkDiagnosticsStore,
    val clock: () -> Long = System::currentTimeMillis
)

interface HardwareBenchmarkDependenciesOwner {
    val hardwareBenchmarkWorkerDependencies: HardwareBenchmarkWorkerDependencies
    val benchmarkStarvationMonitorDependencies: BenchmarkStarvationMonitorDependencies
}

private fun dependenciesFromApplication(context: Context): HardwareBenchmarkWorkerDependencies =
    (context.applicationContext as? HardwareBenchmarkDependenciesOwner)
        ?.hardwareBenchmarkWorkerDependencies
        ?: throw IllegalStateException("Application does not provide hardware benchmark worker dependencies")

/**
 * The Worker gates and records a result only. App startup creates/enqueues this work but never
 * waits for idle or performs model inference itself.
 */
class HardwareBenchmarkWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
    private val dependencies: HardwareBenchmarkWorkerDependencies
) : CoroutineWorker(appContext, workerParameters) {
    /** Required by WorkManager's default reflective WorkerFactory. */
    constructor(appContext: Context, workerParameters: WorkerParameters) :
        this(appContext, workerParameters, dependenciesFromApplication(appContext))

    override suspend fun doWork(): Result = withContext(dependencies.ioDispatcher) {
        val startedElapsed = dependencies.elapsedRealtimeMs()
        var fingerprint: DeviceFingerprint? = null
        var initialSnapshot: BenchmarkConditionSnapshot? = null
        var finalSnapshot: BenchmarkConditionSnapshot? = null
        try {
            val parsedFingerprint = fingerprintFromWorkData(inputData)
            fingerprint = parsedFingerprint
            if (parsedFingerprint == null) {
                recordWorkerEvent(
                    fingerprint = null,
                    result = BenchmarkDiagnosticResult.NOT_MEASURED,
                    reason = BenchmarkDiagnosticReason.INVALID_INPUT,
                    before = null,
                    after = null
                )
                return@withContext Result.failure()
            }
            val requestedFingerprint = parsedFingerprint
            val fingerprintHash = requestedFingerprint.stableKey
            // PROVISIONAL GEN-EDGE-1 deviation approved for this Worker: do not gate on screen state.
            // Every persisted result/event keeps BACKGROUND_SCREEN_OFF as its exact label; the true
            // screenInteractive value remains a separate before/after snapshot field.
            val firstSnapshot = snapshot()
            initialSnapshot = firstSnapshot
            finalSnapshot = firstSnapshot

            if (dependencies.fingerprintProvider.currentFingerprint() != requestedFingerprint) {
                finalSnapshot = snapshot()
                recordWorkerEvent(
                    requestedFingerprint,
                    BenchmarkDiagnosticResult.NOT_MEASURED,
                    BenchmarkDiagnosticReason.STALE_FINGERPRINT,
                    initialSnapshot,
                    finalSnapshot
                )
                return@withContext Result.success()
            }

            val existing = dependencies.profileStore.getResult(requestedFingerprint)
            if (existing?.isFreshValidMeasurement(fingerprintHash, dependencies.clock()) == true ||
                existing?.status == DeviceBenchmarkStatus.MODEL_UNAVAILABLE
            ) {
                finalSnapshot = snapshot()
                val diagnosticResult = when (existing.status) {
                    DeviceBenchmarkStatus.MEASURED -> BenchmarkDiagnosticResult.MEASURED
                    DeviceBenchmarkStatus.MODEL_UNAVAILABLE -> BenchmarkDiagnosticResult.MODEL_UNAVAILABLE
                    else -> BenchmarkDiagnosticResult.NOT_MEASURED
                }
                recordWorkerEvent(
                    requestedFingerprint,
                    diagnosticResult,
                    BenchmarkDiagnosticReason.EXISTING_RESULT,
                    initialSnapshot,
                    finalSnapshot
                )
                return@withContext Result.success()
            }

            if (!requestedFingerprint.hasModelAndRuntimeIdentity) {
                finalSnapshot = snapshot()
                persistTerminalResult(
                    requestedFingerprint,
                    DeviceBenchmarkStatus.MODEL_UNAVAILABLE,
                    BenchmarkDiagnosticReason.MODEL_UNAVAILABLE,
                    initialSnapshot,
                    finalSnapshot
                )
                return@withContext Result.success()
            }

            val initialPrecondition = preconditionFailure(firstSnapshot, requireIdle = false)
            if (initialPrecondition != null) {
                return@withContext retryOrFinish(
                    requestedFingerprint,
                    initialPrecondition,
                    initialSnapshot,
                    snapshot(),
                    terminalStatusFor(initialPrecondition)
                )
            }

            if (!dependencies.idleGate.awaitContinuousIdle(HardwareBenchmarkAssumptions.MIN_IDLE_DURATION_MS)) {
                finalSnapshot = snapshot()
                return@withContext retryOrFinish(
                    requestedFingerprint,
                    BenchmarkDiagnosticReason.IDLE_NOT_CONFIRMED,
                    initialSnapshot,
                    finalSnapshot,
                    DeviceBenchmarkStatus.NOT_MEASURED
                )
            }

            val beforeMeasurement = snapshot()
            finalSnapshot = beforeMeasurement
            val preconditionFailure = preconditionFailure(beforeMeasurement, requireIdle = true)
            if (preconditionFailure != null) {
                return@withContext retryOrFinish(
                    requestedFingerprint,
                    preconditionFailure,
                    initialSnapshot,
                    beforeMeasurement,
                    terminalStatusFor(preconditionFailure)
                )
            }

            val outcome = dependencies.measurement.measure(requestedFingerprint)
            val afterMeasurement = snapshot()
            finalSnapshot = afterMeasurement
            val afterFailure = preconditionFailure(afterMeasurement, requireIdle = true)
            if (afterFailure != null) {
                return@withContext retryOrFinish(
                    requestedFingerprint,
                    afterFailure,
                    beforeMeasurement,
                    afterMeasurement,
                    terminalStatusFor(afterFailure)
                )
            }
            val conditions = BenchmarkConditions(beforeMeasurement, afterMeasurement)

            when (outcome) {
                is BenchmarkMeasurementOutcome.ModelUnavailable -> {
                    persistTerminalResult(
                        requestedFingerprint,
                        DeviceBenchmarkStatus.MODEL_UNAVAILABLE,
                        BenchmarkDiagnosticReason.MODEL_UNAVAILABLE,
                        beforeMeasurement,
                        finalSnapshot,
                        conditions,
                        outcome.reason
                    )
                }
                is BenchmarkMeasurementOutcome.NotMeasured -> {
                    persistTerminalResult(
                        requestedFingerprint,
                        DeviceBenchmarkStatus.NOT_MEASURED,
                        BenchmarkDiagnosticReason.ADAPTER_NOT_WIRED,
                        beforeMeasurement,
                        finalSnapshot,
                        conditions,
                        outcome.reason
                    )
                }
                is BenchmarkMeasurementOutcome.Measured -> {
                    val candidate = outcome.profile.copy(
                        measurementLabel = BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF,
                        conditions = conditions
                    )
                    val stable = candidate.hasStableStatistics()
                    val structurallyValid = candidate.fingerprint == requestedFingerprint &&
                        candidate.isUsableFor(fingerprintHash) &&
                        (dependencies.clock() - candidate.measuredAtEpochMs) in
                        0L..HardwareBenchmarkAssumptions.PROFILE_MAX_AGE_MS
                    if (!stable) {
                        persistTerminalResult(
                            requestedFingerprint,
                            DeviceBenchmarkStatus.UNSTABLE,
                            BenchmarkDiagnosticReason.UNSTABLE,
                            beforeMeasurement,
                            finalSnapshot,
                            conditions
                        )
                    } else if (!structurallyValid) {
                        persistTerminalResult(
                            requestedFingerprint,
                            DeviceBenchmarkStatus.NOT_MEASURED,
                            BenchmarkDiagnosticReason.PROFILE_INVALID,
                            beforeMeasurement,
                            finalSnapshot,
                            conditions
                        )
                    } else {
                        val result = DeviceBenchmarkResult(
                            fingerprint = requestedFingerprint,
                            status = DeviceBenchmarkStatus.MEASURED,
                            profile = candidate,
                            reason = null,
                            updatedAtEpochMs = dependencies.clock(),
                            measurementLabel = BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF,
                            conditions = conditions
                        )
                        // One DataStore edit writes the complete result at the end; no partial profile is exposed.
                        dependencies.profileStore.saveResult(result)
                        recordWorkerEvent(
                            requestedFingerprint,
                            BenchmarkDiagnosticResult.MEASURED,
                            BenchmarkDiagnosticReason.NONE,
                            beforeMeasurement,
                            finalSnapshot
                        )
                    }
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            val elapsed = (dependencies.elapsedRealtimeMs() - startedElapsed).coerceAtLeast(0L)
            val timedOut = elapsed >= APPROXIMATE_WORKER_LIMIT_MS
            finalSnapshot = runCatching { snapshot() }.getOrNull() ?: finalSnapshot
            recordWorkerEvent(
                fingerprint,
                if (timedOut) BenchmarkDiagnosticResult.TIMEOUT else BenchmarkDiagnosticResult.STOPPED_BY_SYSTEM,
                if (timedOut) BenchmarkDiagnosticReason.APPROXIMATE_WORKER_LIMIT
                else BenchmarkDiagnosticReason.WORKER_STOPPED,
                initialSnapshot,
                finalSnapshot
            )
            // Cancellation never persists a profile/result; the complete-result write is the final operation.
            throw cancelled
        } catch (_: Exception) {
            val requestedFingerprint = fingerprint ?: return@withContext Result.retry()
            finalSnapshot = runCatching { snapshot() }.getOrNull() ?: finalSnapshot
            retryOrFinish(
                requestedFingerprint,
                BenchmarkDiagnosticReason.WORKER_FAILURE,
                initialSnapshot,
                finalSnapshot,
                DeviceBenchmarkStatus.NOT_MEASURED
            )
        }
    }

    private fun snapshot(): BenchmarkConditionSnapshot =
        dependencies.conditionSnapshotProvider.snapshot(dependencies.clock())

    private fun preconditionFailure(
        snapshot: BenchmarkConditionSnapshot,
        requireIdle: Boolean
    ): BenchmarkDiagnosticReason? = when {
        !snapshot.isCharging -> BenchmarkDiagnosticReason.NOT_CHARGING
        snapshot.batteryPercent == null -> BenchmarkDiagnosticReason.BATTERY_UNKNOWN
        snapshot.batteryPercent < HardwareBenchmarkAssumptions.MIN_BATTERY_PERCENT ->
            BenchmarkDiagnosticReason.BATTERY_LOW
        requireIdle && !snapshot.deviceIdle -> BenchmarkDiagnosticReason.IDLE_NOT_CONFIRMED
        snapshot.thermalStatus == BenchmarkThermalStatus.UNKNOWN -> BenchmarkDiagnosticReason.THERMAL_UNKNOWN
        snapshot.thermalStatus.ordinal >= BenchmarkThermalStatus.SEVERE.ordinal ->
            BenchmarkDiagnosticReason.THERMAL_HIGH
        else -> null
    }

    private fun terminalStatusFor(reason: BenchmarkDiagnosticReason): DeviceBenchmarkStatus =
        if (reason == BenchmarkDiagnosticReason.THERMAL_HIGH || reason == BenchmarkDiagnosticReason.THERMAL_UNKNOWN) {
            DeviceBenchmarkStatus.THERMAL_HIGH
        } else {
            DeviceBenchmarkStatus.NOT_MEASURED
        }

    private suspend fun retryOrFinish(
        fingerprint: DeviceFingerprint,
        reason: BenchmarkDiagnosticReason,
        before: BenchmarkConditionSnapshot?,
        after: BenchmarkConditionSnapshot?,
        terminalStatus: DeviceBenchmarkStatus
    ): Result {
        if (runAttemptCount < MAX_HARDWARE_BENCHMARK_RETRIES) {
            recordWorkerEvent(
                fingerprint,
                BenchmarkDiagnosticResult.RETRY_SCHEDULED,
                reason,
                before,
                after
            )
            return Result.retry()
        }
        val finalStatus = if (terminalStatus == DeviceBenchmarkStatus.THERMAL_HIGH) {
            DeviceBenchmarkStatus.THERMAL_HIGH
        } else {
            DeviceBenchmarkStatus.NOT_MEASURED
        }
        persistTerminalResult(fingerprint, finalStatus, reason, before, after)
        return Result.success()
    }

    private suspend fun persistTerminalResult(
        fingerprint: DeviceFingerprint,
        status: DeviceBenchmarkStatus,
        reason: BenchmarkDiagnosticReason,
        before: BenchmarkConditionSnapshot?,
        after: BenchmarkConditionSnapshot?,
        conditions: BenchmarkConditions? = if (before != null && after != null) BenchmarkConditions(before, after) else null,
        persistedReason: String = reason.name
    ) {
        val result = DeviceBenchmarkResult(
            fingerprint = fingerprint,
            status = status,
            profile = null,
            reason = persistedReason,
            updatedAtEpochMs = dependencies.clock(),
            measurementLabel = BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF,
            conditions = conditions
        )
        dependencies.profileStore.saveResult(result)
        val diagnosticResult = when (status) {
            DeviceBenchmarkStatus.MODEL_UNAVAILABLE -> BenchmarkDiagnosticResult.MODEL_UNAVAILABLE
            DeviceBenchmarkStatus.THERMAL_HIGH -> BenchmarkDiagnosticResult.THERMAL_HIGH
            DeviceBenchmarkStatus.UNSTABLE -> BenchmarkDiagnosticResult.UNSTABLE
            DeviceBenchmarkStatus.NOT_MEASURED -> BenchmarkDiagnosticResult.NOT_MEASURED
            DeviceBenchmarkStatus.MEASURED -> BenchmarkDiagnosticResult.MEASURED
        }
        recordWorkerEvent(fingerprint, diagnosticResult, reason, before, after)
    }

    private fun recordWorkerEvent(
        fingerprint: DeviceFingerprint?,
        result: BenchmarkDiagnosticResult,
        reason: BenchmarkDiagnosticReason,
        before: BenchmarkConditionSnapshot?,
        after: BenchmarkConditionSnapshot?
    ) {
        dependencies.diagnostics.record(
            BenchmarkDiagnosticEvent(
                timestampEpochMs = dependencies.clock(),
                type = BenchmarkDiagnosticType.WORKER_EXECUTION,
                fingerprintHash = fingerprint?.stableKey,
                result = result,
                reason = reason,
                before = before,
                after = after,
                retryCount = runAttemptCount,
                selectedBackend = if (fingerprint?.hasModelAndRuntimeIdentity == true) DiagnosticBackend.GPU else DiagnosticBackend.NONE,
                selectionReason = if (fingerprint?.hasModelAndRuntimeIdentity == true)
                    BackendSelectionReason.BENCHMARK_MEASUREMENT_TARGET else null
            )
        )
    }
}

/** WorkManager asks this factory for the injectable measurement/store/gate dependencies. */
class HardwareBenchmarkWorkerFactory(
    private val dependenciesProvider: (Context) -> HardwareBenchmarkWorkerDependencies
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        if (workerClassName != HardwareBenchmarkWorker::class.java.name) return null
        return HardwareBenchmarkWorker(
            appContext,
            workerParameters,
            dependenciesProvider(appContext.applicationContext)
        )
    }
}

/** Constraint-free periodic check so an idle/charging-constrained benchmark can report starvation. */
class HardwareBenchmarkStarvationMonitorWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
    private val dependencies: BenchmarkStarvationMonitorDependencies
) : CoroutineWorker(appContext, workerParameters) {
    constructor(appContext: Context, workerParameters: WorkerParameters) : this(
        appContext,
        workerParameters,
        (appContext.applicationContext as? HardwareBenchmarkDependenciesOwner)
            ?.benchmarkStarvationMonitorDependencies
            ?: throw IllegalStateException("Application does not provide benchmark starvation dependencies")
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val fingerprint = dependencies.fingerprintProvider.currentFingerprint()
            val result = dependencies.profileStore.getResult(fingerprint)
            val valid = result?.isFreshValidMeasurement(fingerprint.stableKey, dependencies.clock()) == true
            dependencies.diagnostics.checkStarvation(fingerprint.stableKey, dependencies.clock(), valid)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
