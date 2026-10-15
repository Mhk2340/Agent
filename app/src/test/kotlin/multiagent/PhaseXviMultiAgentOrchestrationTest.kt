package multiagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import contracts.InferenceError
import contracts.InferenceProvider
import contracts.InferenceRequest
import contracts.InferenceResponse
import contracts.ProviderCapabilities
import contracts.ProviderType
import contracts.TokenUsage
import memory.AesGcmCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import persistence.AgentDatabase
import testutil.RoomTestEnvironment
import testutil.TestOnlyIntegrityMacKeyProvider
import testutil.TestOnlyKeystoreKeyProvider
import reliability.AgentOperatingModeManager
import reliability.InMemorySliEventSink
import reliability.InferenceRoutingTarget
import reliability.OperatingMode
import reliability.SliEvent
import security.KeystoreSecretManager

@RunWith(RobolectricTestRunner::class)
class PhaseXviMultiAgentOrchestrationTest {

    private lateinit var context: Context
    private lateinit var testKeys: TestOnlyKeystoreKeyProvider
    private lateinit var macKeyProvider: TestOnlyIntegrityMacKeyProvider
    private lateinit var database: AgentDatabase
    private lateinit var secretManager: KeystoreSecretManager
    private lateinit var providerPool: ExternalAiProviderPoolImpl
    private lateinit var sliEventSink: InMemorySliEventSink
    private lateinit var jobManager: AsyncJobManager
    private lateinit var container: runtime.di.AgentContainer
    private lateinit var epochPreferencesName: String
    private lateinit var epochIntegrityAlias: String
    private lateinit var lockdownPreferencesName: String
    private lateinit var lockdownIntegrityAlias: String

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testKeys = TestOnlyKeystoreKeyProvider()
        macKeyProvider = TestOnlyIntegrityMacKeyProvider()
        sliEventSink = InMemorySliEventSink()
        jobManager = AsyncJobManager(sliEventSink = sliEventSink)

        val memoryCipher = AesGcmCipher(testKeys, AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS)
        database = RoomTestEnvironment.inMemoryDatabase(context, memoryCipher)
        secretManager = KeystoreSecretManager(database = database, keyProvider = testKeys)
        providerPool = ExternalAiProviderPoolImpl(secretManager)

        val suffix = java.util.UUID.randomUUID().toString()
        epochPreferencesName = "phase-xvi-security-epoch-$suffix"
        epochIntegrityAlias = "phase-xvi-epoch-hmac-$suffix"
        lockdownPreferencesName = "phase-xvi-panic-lock-$suffix"
        lockdownIntegrityAlias = "phase-xvi-panic-lock-hmac-$suffix"
        context.getSharedPreferences("agent_policy_config", Context.MODE_PRIVATE)
            .edit().clear().commit()

        container = runtime.di.AgentContainer(
            applicationContext = context,
            databaseOverride = database,
            keystoreKeyProviderOverride = testKeys,
            securityEpochStoreOverride = security.AndroidSecurityEpochStore(
                context,
                macKeyProvider,
                epochPreferencesName,
                epochIntegrityAlias
            ),
            panicResetLockdownStoreOverride = security.AndroidPanicResetLockdownStore(
                context,
                macKeyProvider,
                lockdownPreferencesName,
                lockdownIntegrityAlias
            ),
            benchmarkWorkCancellerOverride = inference.PanicResetBenchmarkWorkCanceller { }
        )
    }

    @After
    fun tearDown() {
        database.close()
        testKeys.clearAllKeysForTest()
        macKeyProvider.clearForTest()
        context.getSharedPreferences(epochPreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(lockdownPreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agent_policy_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // 1. Integration: production AgentContainer cancels real jobs and purges its workspace.
    @Test
    fun testPanicResetInvokesRealPhaseXVIJobAndWorkspacePurge() {
        assertTrue(container.panicResetPhaseXVIHooks is RealPanicResetPhaseXVIHooks)
        val containerJobs = container.asyncJobManager
        val workspace = container.sharedWorkspaceManager
        val job1 = containerJobs.submitJob("job-01", "Create Video Script", "A short sci-fi video")
        val job2 = containerJobs.submitJob("job-02", "Generate Song", "Upbeat pop track")
        containerJobs.startNextJob()

        assertEquals(JobStatus.RUNNING, job1.status)
        assertEquals(JobStatus.QUEUED, job2.status)

        workspace.saveRoleOutput("job-01", "Writer", "Scene 1: Space station docking")
        workspace.saveRoleOutput("job-01", "Director", "Camera panning wide angle")
        assertEquals(2, workspace.getArtifactsForJob("job-01").size)

        val newEpoch = container.executePanicReset("Stolen device alert")
        assertEquals(2L, newEpoch)
        assertEquals(JobStatus.CANCELLED, job1.status)
        assertEquals(JobStatus.CANCELLED, job2.status)
        assertTrue(job1.cancellationReason!!.contains("Panic Reset"))
        assertTrue(workspace.getArtifactsForJob("job-01").isEmpty())
    }

    // 2. Integration: External AI Provider Pool connects to GEN-MODE-1/THERMAL
    @Test
    fun testExternalProviderPoolIntegrationWithThermalOperatingMode() {
        val mockExternalProvider = object : InferenceProvider {
            override fun capabilities() = ProviderCapabilities(
                provider_type = ProviderType.EXTERNAL,
                max_context_tokens = 8192,
                max_output_tokens = 2048,
                supports_streaming = false,
                supports_structured_output = true,
                supports_seed = true,
                supports_cancellation = true
            )
            override fun generate(request: InferenceRequest) = Result.success(
                InferenceResponse("Generated response", TokenUsage(10, 10), "stop")
            )
        }

        // Register provider in pool
        val descriptor = ExternalProviderDescriptor(
            name = "gemini_free",
            endpoint = "https://api.gemini.mock",
            freeQuotaPerDay = 50
        )
        providerPool.registerProvider(descriptor, mockExternalProvider)

        // Store isolated credential in SecretManager (GEN-579/580)
        secretManager.storeSecret("gemini_free", "ai-mock-key-xyz")

        // Connect pool to OperatingModeManager
        val modeManager = AgentOperatingModeManager(externalProviderPool = providerPool)

        // NORMAL mode routes locally
        val (normalRouting, _) = modeManager.resolveInferenceExecutionPath("device-fingerprint")
        assertEquals(InferenceRoutingTarget.LOCAL_ON_DEVICE, normalRouting)

        // THERMAL mode routes to External AI Provider Pool
        modeManager.transitionTo(OperatingMode.THERMAL, "Thermal ceiling exceeded")
        val (thermalRouting, delegate) = modeManager.resolveInferenceExecutionPath("device-fingerprint")
        assertEquals(InferenceRoutingTarget.EXTERNAL_AI_PROVIDER, thermalRouting)
        assertNull(delegate)
    }

    // 3. Provider Pool Circuit Breaker and Auth Failure Handling
    @Test
    fun testProviderCircuitBreakerAndCredentialIsolation() {
        val mockProvider = object : InferenceProvider {
            override fun capabilities() = ProviderCapabilities(ProviderType.EXTERNAL, 4096, 1024, false, true, true, true)
            override fun generate(request: InferenceRequest) = Result.failure<InferenceResponse>(RuntimeException("fail"))
        }

        val desc = ExternalProviderDescriptor("groq_free", "https://api.groq.mock", 100)
        providerPool.registerProvider(desc, mockProvider)

        // Without secret, provider cannot be retrieved
        assertNull(providerPool.getAvailableProvider())

        // Add isolated secret
        secretManager.storeSecret("groq_free", "groq-key-123")
        assertNotNull(providerPool.getAvailableProvider())

        // Simulate 401 Auth Failure
        providerPool.recordOutcome("groq_free", success = false, error = InferenceError.AuthFailed("Invalid API Key"))
        assertEquals(ProviderCircuitState.OPEN, desc.circuitState)
        // With permanent OPEN circuit, no longer available
        assertNull(providerPool.getAvailableProvider())
    }

    // 4. AsyncJobManager SLI Reporting to Phase XI
    @Test
    fun testAsyncJobManagerSliReporting() {
        val now = System.currentTimeMillis()
        val job = jobManager.submitJob("job-sli", "Write poem", "A poem about nature")
        jobManager.startNextJob()

        // Record successful LLM calls
        jobManager.recordLlmCall("job-sli", success = true)
        jobManager.recordLlmCall("job-sli", success = true)
        // Record 1 provider failure
        jobManager.recordLlmCall("job-sli", success = false, isProviderFailure = true)

        jobManager.completeJob("job-sli")

        val events = sliEventSink.getEvents(now - 1000L)
        val bgJobEvents = events.filterIsInstance<SliEvent.BackgroundJob>()
        val providerEvents = events.filterIsInstance<SliEvent.ProviderCall>()

        assertEquals(1, bgJobEvents.size)
        assertTrue(bgJobEvents[0].isSuccessful)

        assertEquals(3, providerEvents.size)
        assertEquals(1, providerEvents.count { it.isFailed })
    }

    // 5. Multi-Hypothesis Sketching, Rubric Evaluation, and Domain Classifier
    @Test
    fun testMultiAgentSketchingAndRoleDecomposer() {
        val classifier = ContentDomainClassifier()
        assertEquals(ContentDomain.VIDEO, classifier.classifyDomain("Please generate a 60s promotional video"))
        assertEquals(ContentDomain.CODE_SOFTWARE, classifier.classifyDomain("Write Kotlin code for a server"))

        val decomposer = DynamicRoleDecomposer()
        val videoRoles = decomposer.decomposeForDomain(ContentDomain.VIDEO)
        assertEquals(3, videoRoles.size)
        assertEquals("Writer", videoRoles[0].roleName)
        assertEquals("Director", videoRoles[1].roleName)
        assertEquals("Editor", videoRoles[2].roleName)

        val sketcher = MultiHypothesisSketcher()
        val sketchA = HypothesisSketch("A", "Linear Narrative", "Start to finish", 4, 4, 3) // 11
        val sketchB = HypothesisSketch("B", "Flashback Narrative", "Start at end", 5, 5, 4) // 14
        val winner = sketcher.evaluateSketches(listOf(sketchA, sketchB))
        assertEquals("B", winner.approachId)
        assertEquals(14, winner.totalScore)
    }

    // 6. Tool Approach Learner with Wilson Lower Bound
    @Test
    fun testToolApproachLearnerWilsonSelection() {
        val learner = ToolApproachLearner()

        // Tool A has 1 use, 1 success (raw rate 100%, but low sample count < 5)
        learner.recordToolFeedback("tool_a", isPositive = true)

        // Tool B has 10 uses, 9 successes (raw rate 90%, sample count >= 5)
        for (i in 1..9) learner.recordToolFeedback("tool_b", isPositive = true)
        learner.recordToolFeedback("tool_b", isPositive = false)

        // Tool B must be preferred over Tool A because Tool A has < 5 samples
        val best = learner.selectBestTool(listOf("tool_a", "tool_b"))
        assertEquals("tool_b", best)
    }
}
