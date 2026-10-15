package inference

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlinx.coroutines.Job

@Config(sdk = [28], manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class HardwareBenchmarkProfilePersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun terminalResultSurvivesStoreRecreationAndFingerprintChangeIsNotReused() = runBlocking {
        val fileName = "edge-profile-${UUID.randomUUID()}"
        val file = context.preferencesDataStoreFile(fileName)
        val fingerprint = DeviceFingerprint(
            device = "persisted-test-device",
            osBuild = "build-A",
            modelSha256 = "c".repeat(64),
            runtimeVersion = "runtime-A"
        )
        val result = DeviceBenchmarkResult(
            fingerprint = fingerprint,
            status = DeviceBenchmarkStatus.NOT_MEASURED,
            reason = "benchmark adapter intentionally reports no measurements",
            updatedAtEpochMs = 2_000L
        )
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val firstStore = DataStoreDeviceProfileStore(
                PreferenceDataStoreFactory.create(
                    scope = firstScope,
                    produceFile = { file }
                )
            )
            firstStore.saveResult(result)
            assertEquals(result, firstStore.getResult(fingerprint))
            assertEquals(result, firstStore.peek(fingerprint.stableKey))
            firstScope.coroutineContext[Job]!!.cancelAndJoin()

            val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val reopenedStore = DataStoreDeviceProfileStore(
                    PreferenceDataStoreFactory.create(
                        scope = reopenedScope,
                        produceFile = { file }
                    )
                )
                assertEquals("DataStore result is read again after store/process recreation", result, reopenedStore.getResult(fingerprint))
                assertEquals(result, reopenedStore.peek(fingerprint.stableKey))

                val changedFingerprint = fingerprint.copy(osBuild = "build-B")
                assertTrue("Fingerprint hash changes with the OS build", fingerprint.stableKey != changedFingerprint.stableKey)
                assertNull("A previous result is not reused after fingerprint change", reopenedStore.getResult(changedFingerprint))
                assertNull(reopenedStore.peek(changedFingerprint.stableKey))
            } finally {
                reopenedScope.coroutineContext[Job]!!.cancelAndJoin()
            }
        } finally {
            firstScope.coroutineContext[Job]?.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun changedFingerprintInvalidatesOldResultAndSchedulesMeasurementForNewFingerprint() = runBlocking {
        val fileName = "edge-rescheduled-profile-${UUID.randomUUID()}"
        val file = context.preferencesDataStoreFile(fileName)
        val oldFingerprint = DeviceFingerprint("scheduler-test-device", "build-A", "f".repeat(64), "runtime-A")
        val changedFingerprint = oldFingerprint.copy(osBuild = "build-B")
        val oldResult = DeviceBenchmarkResult(
            fingerprint = oldFingerprint,
            status = DeviceBenchmarkStatus.NOT_MEASURED,
            reason = "prior fingerprint result",
            updatedAtEpochMs = 2_500L
        )
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val firstStore = DataStoreDeviceProfileStore(
                PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file })
            )
            firstStore.saveResult(oldResult)
            assertEquals(oldResult, firstStore.getResult(oldFingerprint))
            firstScope.coroutineContext[Job]!!.cancelAndJoin()

            val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val reopenedStore = DataStoreDeviceProfileStore(
                    PreferenceDataStoreFactory.create(scope = reopenedScope, produceFile = { file })
                )
                assertEquals(oldResult, reopenedStore.getResult(oldFingerprint))
                assertTrue(oldFingerprint.stableKey != changedFingerprint.stableKey)
                assertNull(reopenedStore.getResult(changedFingerprint))
                assertNull(reopenedStore.peek(changedFingerprint.stableKey))

                var enqueuedName: String? = null
                var enqueuedPolicy: ExistingWorkPolicy? = null
                var enqueuedRequest: OneTimeWorkRequest? = null
                val scheduler = HardwareBenchmarkWorkScheduler(
                    fingerprintProvider = DeviceFingerprintProvider { changedFingerprint },
                    profileStore = reopenedStore,
                    enqueuePort = UniqueWorkEnqueuePort { name, policy, request ->
                        enqueuedName = name
                        enqueuedPolicy = policy
                        enqueuedRequest = request
                    }
                )

                scheduler.enqueue()

                assertEquals(hardwareBenchmarkWorkPlan(changedFingerprint).uniqueName, enqueuedName)
                assertEquals(ExistingWorkPolicy.KEEP, enqueuedPolicy)
                assertEquals(
                    changedFingerprint.stableKey,
                    enqueuedRequest!!.workSpec.input.getString("fingerprint_key")
                )
                assertEquals("build-B", enqueuedRequest!!.workSpec.input.getString("os_build"))
            } finally {
                reopenedScope.coroutineContext[Job]!!.cancelAndJoin()
            }
        } finally {
            firstScope.coroutineContext[Job]?.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun measuredProfileIsReusedAfterRestartOnlyAfterThePersistentSnapshotIsLoaded() = runBlocking {
        val fileName = "edge-measured-profile-${UUID.randomUUID()}"
        val file = context.preferencesDataStoreFile(fileName)
        val fingerprint = DeviceFingerprint("gpu-test-device", "build-C", "d".repeat(64), "runtime-C")
        val profile = DevicePerformanceProfile(
            fingerprint = fingerprint,
            contextTokens = HardwareBenchmarkAssumptions.CONTEXT_TOKENS,
            warmupRuns = HardwareBenchmarkAssumptions.MAX_WARMUP_RUNS,
            sampleCount = HardwareBenchmarkAssumptions.MIN_SAMPLES_FOR_P99,
            gpuPrefillLatency = LatencyStatistics(900.0, 1200.0, 1500.0, 90.0),
            gpuDecodeRate = DecodeRateStatistics(18.0, 20.0, 22.0, 1.0),
            gpuPeakAnonymousMemoryMb = 400L,
            lmkKillWatermarkMb = 700L,
            measuredAtEpochMs = 3_000L,
            measurementLabel = BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF,
            conditions = BenchmarkConditions(
                benchmarkSnapshot(3_000L, screenOn = false),
                benchmarkSnapshot(3_000L, screenOn = false)
            )
        )
        val result = DeviceBenchmarkResult(
            fingerprint = fingerprint,
            status = DeviceBenchmarkStatus.MEASURED,
            profile = profile,
            updatedAtEpochMs = 3_000L,
            measurementLabel = BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF,
            conditions = profile.conditions
        )
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val firstStore = DataStoreDeviceProfileStore(
                PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file })
            )
            firstStore.saveResult(result)
            firstScope.coroutineContext[Job]!!.cancelAndJoin()

            val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val reopenedStore = DataStoreDeviceProfileStore(
                    PreferenceDataStoreFactory.create(scope = reopenedScope, produceFile = { file })
                )
                val selector = HardwareDelegateSelector(
                    reopenedStore,
                    InMemoryGpuRuntimeSafetyStore(),
                    { 3_000L }
                )
                assertEquals("Missing process snapshot is conservative", HardwareDelegate.CPU, selector.selectDelegate(fingerprint.stableKey))
                assertEquals(result, reopenedStore.getResult(fingerprint))
                assertEquals("Valid persisted profile becomes reusable after reload", HardwareDelegate.GPU, selector.selectDelegate(fingerprint.stableKey))
            } finally {
                reopenedScope.coroutineContext[Job]!!.cancelAndJoin()
            }
        } finally {
            firstScope.coroutineContext[Job]?.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun stableKeyIsDeterministicAndDistinguishesUnknownIdentityFromAStringValue() {
        val base = DeviceFingerprint("device", "build", null, null)
        val same = DeviceFingerprint("device", "build", null, null)
        val literal = DeviceFingerprint("device", "build", "null", "null")

        assertEquals(base.stableKey, same.stableKey)
        assertTrue(base.stableKey != literal.stableKey)
        assertTrue(base.stableKey != base.copy(device = "other-device").stableKey)
        assertTrue(base.stableKey != base.copy(osBuild = "other-build").stableKey)
        assertTrue(base.stableKey != base.copy(modelSha256 = "e".repeat(64)).stableKey)
        assertTrue(base.stableKey != base.copy(runtimeVersion = "runtime").stableKey)
    }
}

private fun benchmarkSnapshot(timestamp: Long, screenOn: Boolean) = BenchmarkConditionSnapshot(
    isCharging = true,
    batteryPercent = 80,
    screenInteractive = screenOn,
    deviceIdle = true,
    thermalStatus = BenchmarkThermalStatus.NORMAL,
    capturedAtEpochMs = timestamp
)
