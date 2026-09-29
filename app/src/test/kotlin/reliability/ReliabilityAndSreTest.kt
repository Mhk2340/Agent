package reliability

import contracts.GenerateParams
import contracts.InferenceError
import contracts.InferenceProvider
import contracts.InferenceRequest
import contracts.InferenceResponse
import contracts.ProviderCapabilities
import contracts.ProviderType
import contracts.TokenUsage
import contracts.StreamState
import inference.HardwareDelegate
import inference.HardwareDelegateSelector
import learning.AdaptiveRankingWeightUpdater
import memory.UnifiedRetrievalScorer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReliabilityAndSreTest {

    private lateinit var sink: SliEventSink
    private lateinit var modeManager: AgentOperatingModeManager
    private lateinit var healthAggregator: HealthAggregator
    private lateinit var errorBudgetController: SystemErrorBudgetController
    private lateinit var recoveryPlanner: RecoveryPlanner

    @Before
    fun setup() {
        sink = InMemorySliEventSink()
        modeManager = AgentOperatingModeManager()
        healthAggregator = HealthAggregator(sink, modeManager)
        errorBudgetController = SystemErrorBudgetController(healthAggregator)
        recoveryPlanner = RecoveryPlanner(modeManager)
        FaultInjectionController.reset()
    }

    @After
    fun teardown() {
        FaultInjectionController.reset()
    }

    @Test
    fun testOperatingModeManagerTransitionsAndConstraints() {
        // 1. Initial mode is NORMAL
        assertEquals(OperatingMode.NORMAL, modeManager.getCurrentMode())
        val normalConstraints = modeManager.getConstraints()
        assertEquals(InferenceRoutingTarget.LOCAL_ON_DEVICE, normalConstraints.preferredInferenceRouting)
        assertTrue(normalConstraints.allowLevel2Actions)
        assertTrue(normalConstraints.allowLevel3Actions)
        assertFalse(normalConstraints.isMemoryReadOnly)
        assertTrue(normalConstraints.allowExternalNetwork)
        assertEquals(1.0f, normalConstraints.contextTokenLimitRatio, 0.001f)

        // 2. THERMAL mode prefers EXTERNAL_AI_PROVIDER and throttles L3 skills
        modeManager.transitionTo(OperatingMode.THERMAL, "High temperature detected")
        assertEquals(OperatingMode.THERMAL, modeManager.getCurrentMode())
        val thermalConstraints = modeManager.getConstraints()
        assertEquals(InferenceRoutingTarget.EXTERNAL_AI_PROVIDER, thermalConstraints.preferredInferenceRouting)
        assertFalse(thermalConstraints.allowLevel3Actions)
        assertTrue(thermalConstraints.allowLevel2Actions)

        // In absence of external provider pool, falls back to CPU
        val (routing, delegate) = modeManager.resolveInferenceExecutionPath("device-gpu-capable")
        assertEquals(InferenceRoutingTarget.LOCAL_ON_DEVICE, routing)
        assertEquals(HardwareDelegate.CPU, delegate)

        // 3. LOW_MEMORY mode halves context budget and forces CPU
        modeManager.transitionTo(OperatingMode.LOW_MEMORY, "Low memory watermark reached")
        assertEquals(OperatingMode.LOW_MEMORY, modeManager.getCurrentMode())
        val lowMemConstraints = modeManager.getConstraints()
        assertEquals(0.5f, lowMemConstraints.contextTokenLimitRatio, 0.001f)
        assertEquals(HardwareDelegate.CPU, lowMemConstraints.hardwareDelegateOverride)

        // 4. SECURITY_LOCKDOWN mode sets memory to read-only, isolates network, and disables Level 2 and Level 3 actions
        modeManager.transitionTo(OperatingMode.SECURITY_LOCKDOWN, "Integrity breach suspected")
        assertEquals(OperatingMode.SECURITY_LOCKDOWN, modeManager.getCurrentMode())
        val lockdownConstraints = modeManager.getConstraints()
        assertTrue(lockdownConstraints.isMemoryReadOnly)
        assertFalse(lockdownConstraints.allowLevel2Actions)
        assertFalse(lockdownConstraints.allowLevel3Actions)
        assertFalse(lockdownConstraints.allowExternalNetwork)
    }

    @Test
    fun testThermalModePrefersExternalAiProviderOverLocalModel() {
        val mockExternalProvider = object : InferenceProvider {
            override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
                provider_type = ProviderType.EXTERNAL,
                max_context_tokens = 8192,
                max_output_tokens = 2048,
                supports_streaming = false,
                supports_structured_output = true,
                supports_seed = true,
                supports_cancellation = true
            )

            override fun generate(request: InferenceRequest): Result<InferenceResponse> {
                return Result.success(
                    InferenceResponse(
                        text = "external response",
                        usage = TokenUsage(10, 10),
                        finish_reason = "stop"
                    )
                )
            }
        }

        val poolWithProvider = object : ExternalAiProviderPool {
            override fun getAvailableProvider(): InferenceProvider = mockExternalProvider
        }

        val thermalModeManager = AgentOperatingModeManager(
            externalProviderPool = poolWithProvider
        )

        // When NORMAL, prefers LOCAL_ON_DEVICE
        val normalPath = thermalModeManager.resolveInferenceExecutionPath("test-fingerprint")
        assertEquals(InferenceRoutingTarget.LOCAL_ON_DEVICE, normalPath.first)

        // When THERMAL, must strictly prefer EXTERNAL_AI_PROVIDER
        thermalModeManager.transitionTo(OperatingMode.THERMAL, "Overheating threshold reached")
        val thermalPath = thermalModeManager.resolveInferenceExecutionPath("test-fingerprint")
        assertEquals(InferenceRoutingTarget.EXTERNAL_AI_PROVIDER, thermalPath.first)
        // External execution does not require on-device hardware delegate
        assertEquals(null, thermalPath.second)

        // When external provider pool has no available provider, falls back to CPU
        val emptyPool = object : ExternalAiProviderPool {
            override fun getAvailableProvider(): InferenceProvider? = null
        }
        val fallbackManager = AgentOperatingModeManager(externalProviderPool = emptyPool)
        fallbackManager.transitionTo(OperatingMode.THERMAL, "Thermal spike with no external provider")
        val fallbackPath = fallbackManager.resolveInferenceExecutionPath("test-fingerprint")
        assertEquals(InferenceRoutingTarget.LOCAL_ON_DEVICE, fallbackPath.first)
        assertEquals(HardwareDelegate.CPU, fallbackPath.second)
    }

    @Test
    fun testSecurityLockdownProvisionalEnforcer() {
        val enforcer = DefaultSecurityLockdownEnforcer()
        val customModeManager = AgentOperatingModeManager(securityLockdownEnforcer = enforcer)

        // Initial state is unlocked
        assertFalse(enforcer.isMemoryReadOnly())
        assertTrue(enforcer.areMutatingToolsAllowed())
        assertTrue(enforcer.isExternalNetworkAllowed())

        // Transition to SECURITY_LOCKDOWN triggers enforcer
        customModeManager.transitionTo(OperatingMode.SECURITY_LOCKDOWN, "Safety alert")
        assertTrue(enforcer.isMemoryReadOnly())
        assertFalse(enforcer.areMutatingToolsAllowed())
        assertFalse(enforcer.isExternalNetworkAllowed())

        val constraints = customModeManager.getConstraints()
        assertTrue(constraints.isMemoryReadOnly)
        assertFalse(constraints.allowLevel2Actions)
        assertFalse(constraints.allowLevel3Actions)
        assertFalse(constraints.allowExternalNetwork)

        // Transition back to NORMAL resets enforcer
        customModeManager.transitionTo(OperatingMode.NORMAL, "System verified")
        assertFalse(enforcer.isMemoryReadOnly())
        assertTrue(enforcer.areMutatingToolsAllowed())
        assertTrue(enforcer.isExternalNetworkAllowed())
    }

    @Test
    fun testTier1SloEvaluationP95AndCrashFreeRate() {
        val now = System.currentTimeMillis()

        // Populate 60 turn latency events (>= 50 min samples) with acceptable latency
        for (i in 1..60) {
            sink.recordEvent(SliEvent.TurnLatency(now - (i * 10_000L), latencyMs = 15_000L))
            sink.recordEvent(SliEvent.TurnCompletion(now - (i * 10_000L), isCrashFree = true))
        }

        val sloResults = healthAggregator.evaluateSloStatus(now)
        val latencySlo = sloResults.first { it.sliName == "TurnLatencyP95" }
        val crashSlo = sloResults.first { it.sliName == "CrashFreeTurnRate" }

        assertTrue(latencySlo.isMet)
        assertEquals(60, latencySlo.sampleCount)
        assertEquals(15000.0, latencySlo.observedValue, 0.001)

        assertTrue(crashSlo.isMet)
        assertEquals(1.0, crashSlo.observedValue, 0.001)
    }

    @Test
    fun testTier2SloEvaluationWilsonLowerBound() {
        val now = System.currentTimeMillis()

        // 12 background jobs (>= 10 min samples), 11 successful
        for (i in 1..11) {
            sink.recordEvent(SliEvent.BackgroundJob(now - (i * 3600_000L), isSuccessful = true))
        }
        sink.recordEvent(SliEvent.BackgroundJob(now - 1000L, isSuccessful = false))

        val sloResults = healthAggregator.evaluateSloStatus(now)
        val jobSlo = sloResults.first { it.sliName == "BackgroundJobCompletionRate" }

        assertEquals(12, jobSlo.sampleCount)
        val expectedWilson = SloDefinitions.calculateWilsonLowerBound(11, 12)
        assertEquals(expectedWilson, jobSlo.observedValue, 0.001)
    }

    @Test
    fun testTier3PureCountsAndFastBurnAlert() {
        val now = System.currentTimeMillis()

        // Tier 3: 3 rollbacks in past week (exceeds < 2/week)
        sink.recordEvent(SliEvent.RollbackCount(now - 10_000L))
        sink.recordEvent(SliEvent.RollbackCount(now - 20_000L))
        sink.recordEvent(SliEvent.RollbackCount(now - 30_000L))

        // Tier 3: 4 fallbacks in past day (exceeds < 3/day)
        sink.recordEvent(SliEvent.FallbackCount(now - 5_000L))
        sink.recordEvent(SliEvent.FallbackCount(now - 6_000L))
        sink.recordEvent(SliEvent.FallbackCount(now - 7_000L))
        sink.recordEvent(SliEvent.FallbackCount(now - 8_000L))

        val dashboard = healthAggregator.buildDashboard(now)
        assertEquals(SystemHealthStatus.CRITICAL, dashboard.overallStatus)
        assertTrue(dashboard.isErrorBudgetExhausted)
        assertEquals(3, dashboard.tier3RollbackCountPastWeek)
        assertEquals(4, dashboard.tier3FallbackCountPastDay)
        assertTrue(dashboard.activeAnomalies.isNotEmpty())
    }

    @Test
    fun testErrorBudgetExhaustionHaltsAdaptiveWeightUpdater() {
        val now = System.currentTimeMillis()

        // Cause budget exhaustion via rate limiter reject breach (>= 1/day)
        sink.recordEvent(SliEvent.RateLimiterRejectCount(now - 1000L))
        sink.recordEvent(SliEvent.RateLimiterRejectCount(now - 500L))

        val dashboard = healthAggregator.buildDashboard(now)
        assertTrue(dashboard.isErrorBudgetExhausted)

        val budgetPermitted = errorBudgetController.checkAndEnforceBudget(dashboard)
        assertFalse(budgetPermitted)
        assertFalse(errorBudgetController.isChangeMechanismPermitted())

        // Verify GEN-363 refuses update when error budget is exhausted
        val scorer = UnifiedRetrievalScorer()
        val updater = AdaptiveRankingWeightUpdater(
            scorer = scorer,
            errorBudgetController = errorBudgetController
        )

        val result = updater.proposeAndEvaluateUpdate(
            signalValue = 1.0f,
            boostTarget = learning.WeightComponent.RELEVANCE,
            reason = "Positive feedback"
        )

        assertFalse(result.committed)
        assertTrue(result.rejectionReason?.contains("Error Budget Policy (GEN-SRE-4)") == true)
    }

    @Test
    fun testDiagnosisAndSelfHealingGeneratesBlamelessIncidentNote() {
        val now = System.currentTimeMillis()

        val diagnosis = DiagnosisReport(
            anomalyKey = "THERMAL_THROTTLING",
            severity = DiagnosisSeverity.HIGH,
            description = "Device thermal state exceeds safe operating threshold",
            recommendedRemedy = RemedyAction.ENTER_THERMAL_MODE
        )

        val note = recoveryPlanner.executeRemedy(diagnosis, now)

        assertNotNull(note.incidentId)
        assertEquals("THERMAL_THROTTLING", note.triggerSource)
        assertEquals(OperatingMode.THERMAL, modeManager.getCurrentMode())
        assertTrue(note.mitigationActionTaken.contains("Switched to THERMAL mode"))
        assertEquals(1, recoveryPlanner.getIncidentHistory().size)
    }

    @Test
    fun testFaultInjectionGuardedByController() {
        val harness = FaultInjectionHarness()

        // 1. With flag false, fault injection throws IllegalStateException
        FaultInjectionController.isFaultInjectionEnabled = false
        val exception = assertThrows(IllegalStateException::class.java) {
            harness.injectFault(FaultType.THERMAL_SPIKE, modeManager, sink)
        }
        assertTrue(exception.message!!.contains("Fault injection is DISABLED"))

        // 2. With flag true, fault injection succeeds in test harness
        FaultInjectionController.isFaultInjectionEnabled = true
        harness.injectFault(FaultType.THERMAL_SPIKE, modeManager, sink)
        assertEquals(OperatingMode.THERMAL, modeManager.getCurrentMode())
    }
}
