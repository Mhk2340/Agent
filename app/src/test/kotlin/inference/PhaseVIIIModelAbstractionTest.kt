package inference

import contracts.GenerateParams
import contracts.InferenceError
import contracts.InferenceRequest
import contracts.InferenceResponse
import contracts.ProviderType
import contracts.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Phase VIII Tier 1 Test Suite:
 * - GEN-271: Model Abstraction Layer
 * - GEN-278: Local Inference Engine
 * - GEN-285: Context Window Budget Policy (4096 ceiling, KV cache calculation)
 * - GEN-NET-1: Model Asset Download Protocol (HTTPS, Checksum, Range Resume, Metered)
 * - GEN-EDGE-1: Hardware Delegate Selection (Safety-over-Speed & Profile Caching)
 */
class PhaseVIIIModelAbstractionTest {

    // 1. GEN-285 Context Window Budget Policy Tests
    @Test
    fun testContextBudgetCalculationAndAdmission() {
        // PROVISIONAL - pending GEN-PERF-1: 4096 default, 64 margin
        val policy = ContextWindowBudgetPolicy(maxContextTokens = 4096, safetyMarginTokens = 64)

        val requestedOutput = 512
        val expectedUsable = 4096 - 512 - 64 // 3520
        assertEquals(expectedUsable, policy.calculateUsableContextTokens(requestedOutput))

        assertTrue(policy.checkAdmission(3000, requestedOutput))
        assertFalse("Context exceeding usable budget must be rejected by Admission Check", policy.checkAdmission(3600, requestedOutput))
    }

    @Test
    fun testKvCacheMemoryEstimation() {
        val policy = ContextWindowBudgetPolicy()
        // PROVISIONAL - pending GEN-PERF-1: ~144 KB per token
        val kvBytesAt4K = policy.estimateKvCacheBytes(4096)
        // 4096 * 144 * 1024 = 603,979,776 bytes (~576-590 MB)
        val mb = kvBytesAt4K / (1024 * 1024)
        assertTrue("KV Cache at 4K context must be around 576-590MB", mb in 570..600)
    }

    // 2. GEN-EDGE-1 Hardware Delegate Selection (Safety over Speed & Profile Caching)
    @Test
    fun testHardwareDelegateSelectionAndCaching() = kotlinx.coroutines.runBlocking {
        val store = InMemoryDeviceProfileStore()
        val now = System.currentTimeMillis()
        val selector = HardwareDelegateSelector(store, InMemoryGpuRuntimeSafetyStore()) { now }
        val fingerprint = DeviceFingerprint(
            device = "arm64 test device",
            osBuild = "test-os-build",
            modelSha256 = "a".repeat(64),
            runtimeVersion = "test-runtime-1"
        )

        fun profile(peakAnonymousMb: Long, sampleCount: Int = 50) = DevicePerformanceProfile(
            fingerprint = fingerprint,
            contextTokens = HardwareBenchmarkAssumptions.CONTEXT_TOKENS,
            warmupRuns = 3,
            sampleCount = sampleCount,
            gpuPrefillLatency = LatencyStatistics(1000.0, 1500.0, 1800.0, 120.0),
            gpuDecodeRate = DecodeRateStatistics(18.0, 20.0, 22.0, 1.2),
            gpuPeakAnonymousMemoryMb = peakAnonymousMb,
            lmkKillWatermarkMb = 700L,
            measuredAtEpochMs = now,
            measurementLabel = BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF,
            conditions = BenchmarkConditions(
                BenchmarkConditionSnapshot(true, 80, false, true, BenchmarkThermalStatus.NORMAL, now),
                BenchmarkConditionSnapshot(true, 80, false, true, BenchmarkThermalStatus.NORMAL, now)
            )
        )

        // Prefill/decode are retained as separate summaries; memory remains a hard safety gate.
        val highMemGpuProfile = profile(peakAnonymousMb = 850L)
        assertEquals(
            "Safety-over-Speed selects CPU even when timing and decode summaries are present",
            HardwareDelegate.CPU,
            selector.selectDelegate(fingerprint.stableKey, highMemGpuProfile)
        )

        // A valid profile under a different fingerprint is never reused.
        val changedFingerprint = fingerprint.copy(osBuild = "updated-os-build")
        assertEquals(
            HardwareDelegate.CPU,
            selector.selectDelegate(changedFingerprint.stableKey, highMemGpuProfile)
        )

        val safeMemGpuProfile = profile(peakAnonymousMb = 450L)
        store.saveResult(
            DeviceBenchmarkResult(
                fingerprint = fingerprint,
                status = DeviceBenchmarkStatus.MEASURED,
                profile = safeMemGpuProfile,
                updatedAtEpochMs = safeMemGpuProfile.measuredAtEpochMs,
                measurementLabel = BenchmarkMeasurementLabel.BACKGROUND_SCREEN_OFF,
                conditions = safeMemGpuProfile.conditions
            )
        )
        assertEquals(
            "Cached valid profile with GPU Peak Anonymous below the watermark selects GPU",
            HardwareDelegate.GPU,
            selector.selectDelegate(fingerprint.stableKey)
        )

        // Null/stale/unprofiled state and undersized methodology samples all remain CPU.
        assertEquals(HardwareDelegate.CPU, selector.selectDelegate(null))
        assertEquals(HardwareDelegate.CPU, selector.selectDelegate("unknown-device"))
        assertEquals(
            HardwareDelegate.CPU,
            selector.selectDelegate(fingerprint.stableKey, safeMemGpuProfile.copy(sampleCount = 20))
        )
    }

    // 3. GEN-271 & GEN-278 Local Inference Engine Integration with Mock
    @Test
    fun testLocalInferenceEngineGeneration() {
        val engine = LocalInferenceEngine(
            budgetPolicy = ContextWindowBudgetPolicy(maxContextTokens = 4096),
            backendExecutor = { _ ->
                Result.success(
                    InferenceResponse(
                        text = "{\"intent\": \"navigate_home\", \"confidence\": 0.98}",
                        usage = TokenUsage(prompt_tokens = 50, completion_tokens = 15),
                        finish_reason = "stop"
                    )
                )
            }
        )

        val caps = engine.capabilities()
        assertEquals(ProviderType.LOCAL, caps.provider_type)
        assertEquals(4096, caps.max_context_tokens)

        val req = InferenceRequest(
            prompt = "Go home",
            context = "current_screen: settings",
            params = GenerateParams(max_tokens = 128, temperature = 0.0f, top_p = 0.9f)
        )

        val res = engine.generate(req)
        assertTrue(res.isSuccess)
        val response = res.getOrNull()!!
        assertEquals("stop", response.finish_reason)
        assertEquals(50, response.usage.prompt_tokens)
    }

    @Test
    fun testLocalInferenceEngineContextAdmissionRejection() {
        val engine = LocalInferenceEngine(
            budgetPolicy = ContextWindowBudgetPolicy(maxContextTokens = 1000, safetyMarginTokens = 50)
        )

        // Usable = 1000 - 200 - 50 = 750 tokens
        val longContext = "x".repeat(800)
        val req = InferenceRequest(
            prompt = "Analyze",
            context = longContext,
            params = GenerateParams(max_tokens = 200, temperature = 0.0f, top_p = 0.9f)
        )

        val res = engine.generate(req)
        assertTrue(res.isFailure)
        val exception = res.exceptionOrNull() as? AgentModelException
        assertTrue(exception != null)
        assertTrue(exception!!.error is InferenceError.ResourceExhausted)
    }

    // 4. GEN-NET-1 Model Asset Download Protocol Tests (with Stub payload)
    @Test
    fun testModelDownloadProtocolWithStub() {
        val dummyModelBytes = "QWEN3-4B-Q4_K_M-STUB-BYTES-MODEL-PAYLOAD".toByteArray(Charsets.UTF_8)
        val expectedSha256 = ChecksumUtil.sha256(dummyModelBytes)

        val stubStreamSource = object : ModelDownloadProtocol.HttpStreamSource {
            override fun openRangeStream(url: String, startByte: Long): InputStream {
                return ByteArrayInputStream(dummyModelBytes, startByte.toInt(), dummyModelBytes.size - startByte.toInt())
            }

            override fun getContentLength(url: String): Long {
                return dummyModelBytes.size.toLong()
            }
        }

        // Test Non-HTTPS Rejection
        val downloader = ModelDownloadProtocol(isMeteredNetworkProvider = { false })
        val insecureResult = downloader.downloadAsset("http://insecure.models/stub.bin", expectedSha256, stubStreamSource)
        assertTrue(insecureResult is ModelDownloadProtocol.DownloadResult.NetworkFailure)

        // Test Metered Network Confirmation Requirement
        val meteredDownloader = ModelDownloadProtocol(isMeteredNetworkProvider = { true })
        val meteredResult = meteredDownloader.downloadAsset(
            "https://secure.models/stub.bin",
            expectedSha256,
            stubStreamSource,
            userMeteredConfirmed = false
        )
        assertTrue(meteredResult is ModelDownloadProtocol.DownloadResult.RequiresMeteredConfirmation)

        // Test Successful Resumable Download with Checksum Verification
        val successfulResult = meteredDownloader.downloadAsset(
            "https://secure.models/stub.bin",
            expectedSha256,
            stubStreamSource,
            userMeteredConfirmed = true
        )
        assertTrue(successfulResult is ModelDownloadProtocol.DownloadResult.Success)
        val successData = successfulResult as ModelDownloadProtocol.DownloadResult.Success
        assertEquals(expectedSha256, successData.sha256)

        // Test Checksum Failure Detection (Corrupted Model Payload)
        val corruptedResult = downloader.downloadAsset(
            "https://secure.models/stub.bin",
            "WRONG_HASH_00000000000000000000000000000000000000000000000000000000",
            stubStreamSource
        )
        assertTrue(corruptedResult is ModelDownloadProtocol.DownloadResult.IntegrityFailure)
    }
}
