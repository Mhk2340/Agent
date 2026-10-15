package learning

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import contracts.ExecutionDetails
import contracts.ExperienceRecord
import memory.EncryptedMemoryStorage
import memory.MemoryCallerRole
import memory.MemoryManager
import memory.MemoryType
import memory.UnifiedRetrievalScorer
import memory.UnifiedScoringWeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import persistence.AgentDatabase
import testutil.RoomTestEnvironment
import testutil.TestUnlockedAppLockAccess

/**
 * Phase X Learning System Test Suite:
 * - GEN-361: Feedback Signal Pipeline (Edit-Distance filtering, UI observation level, implicit/explicit signals)
 * - GEN-362: Example Bank Consolidation Policy (Procedural promotion upon pattern frequency, Failure promotion, Weak signal retention)
 * - GEN-363: Adaptive Ranking Weight Update (EMA tuning of GEN-MEM-3 weights, normalization, bounds, rollback, generation_id)
 */
@RunWith(RobolectricTestRunner::class)
class PhaseXLearningSystemTest {

    private lateinit var database: AgentDatabase
    private lateinit var pipeline: FeedbackSignalPipeline
    private lateinit var memoryManager: MemoryManager
    private lateinit var consolidationPolicy: ExampleBankConsolidationPolicy
    private lateinit var scorer: UnifiedRetrievalScorer
    private lateinit var updater: AdaptiveRankingWeightUpdater

    @Before
    fun setup() {
        val cipher = RoomTestEnvironment.newCipher()
        database = RoomTestEnvironment.inMemoryDatabase(
            ApplicationProvider.getApplicationContext<Context>(),
            cipher
        )
        val storage = EncryptedMemoryStorage(database, cipher)
        pipeline = FeedbackSignalPipeline(editRatioThreshold = 0.15f)
        memoryManager = MemoryManager(storage = storage, appLockAccess = TestUnlockedAppLockAccess)
        consolidationPolicy = ExampleBankConsolidationPolicy(memoryManager, minPatternOccurrences = 3)
        scorer = UnifiedRetrievalScorer()
        updater = AdaptiveRankingWeightUpdater(scorer, learningRate = 0.05f)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // 1. GEN-361 Edit Distance Ratio & Provisional Threshold Test
    @Test
    fun testEditDistanceFiltering() {
        val original = "The battery level is currently at 85 percent and charging."
        
        // Scenario A: Minor stylistic edit (e.g. changing 2 words -> ratio < 0.15)
        val minorEdit = "The battery level is currently at 85 percent and charged."
        val ratioMinor = pipeline.evaluateEditDistance(original, minorEdit)
        assertTrue("Minor edit distance ratio must be < 0.15", ratioMinor < 0.15f)

        val signalMinor = pipeline.extractSignal(
            originalResponse = original,
            editedResponse = minorEdit,
            hasObservedEditorField = true
        )
        assertEquals("Minor edit must produce neutral signal (0.0f)", 0.0f, signalMinor.signalValue, 0.001f)

        // Scenario B: Major edit (rewriting the sentence -> ratio >= 0.15)
        val majorEdit = "Battery status check completely failed, device offline."
        val ratioMajor = pipeline.evaluateEditDistance(original, majorEdit)
        assertTrue("Major edit distance ratio must be >= 0.15", ratioMajor >= 0.15f)

        val signalMajor = pipeline.extractSignal(
            originalResponse = original,
            editedResponse = majorEdit,
            hasObservedEditorField = true
        )
        assertEquals("Major edit must produce negative signal (-1.0f)", -1.0f, signalMajor.signalValue, 0.001f)
    }

    // 2. GEN-361 UI Observation Level Guard: Headless/undefined editor field defaults to Neutral
    @Test
    fun testHeadlessObservationLevelDefaultsToNeutral() {
        val original = "Open calendar at 10 AM"
        val edited = "Open notes at 10 AM"

        // In the absence of a confirmed editable UI field (hasObservedEditorField = false)
        val signal = pipeline.extractSignal(
            originalResponse = original,
            editedResponse = edited,
            hasObservedEditorField = false
        )

        assertEquals("Observed edit without verified UI editor field must default to neutral", 
            0.0f, signal.signalValue, 0.001f)
    }

    // 3. GEN-361 UI-Independent Signals: Repeated Requests and Explicit Ratings
    @Test
    fun testUiIndependentSignals() {
        // User repeated request -> Strong negative
        val repeatedSignal = pipeline.extractSignal(
            originalResponse = "I could not find the weather",
            isRequestRepeated = true
        )
        assertEquals(SignalType.IMPLICIT, repeatedSignal.signalType)
        assertEquals(-1.0f, repeatedSignal.signalValue, 0.001f)

        // User explicit thumbs up -> Positive
        val explicitUp = pipeline.extractSignal(
            originalResponse = "Answer",
            explicitRating = 1.0f
        )
        assertEquals(SignalType.EXPLICIT, explicitUp.signalType)
        assertEquals(1.0f, explicitUp.signalValue, 0.001f)

        // User explicit thumbs down -> Negative
        val explicitDown = pipeline.extractSignal(
            originalResponse = "Answer",
            explicitRating = 0.0f
        )
        assertEquals(SignalType.EXPLICIT, explicitDown.signalType)
        assertEquals(-1.0f, explicitDown.signalValue, 0.001f)
    }

    // 4. GEN-362 Example Bank Consolidation Policy: Procedural Promotion upon Frequency
    @Test
    fun testProceduralConsolidationUponPatternFrequency() {
        val exp1 = ExperienceRecord(
            interaction_id = "exp-1",
            timestamp = 1000L,
            input_summary = "Turn on wifi",
            output_summary = "Wifi enabled",
            signal_type = "implicit",
            signal_value = 1.0f,
            signal_source = "user_accepted",
            execution = ExecutionDetails("adb_intent", 100L)
        )
        val exp2 = exp1.copy(interaction_id = "exp-2")
        val exp3 = exp1.copy(interaction_id = "exp-3")

        // First occurrence: not yet promoted (count = 1 < 3)
        val dec1 = consolidationPolicy.evaluateAndConsolidate(exp1)
        assertFalse(dec1.promoted)

        // Second occurrence: not yet promoted (count = 2 < 3)
        val dec2 = consolidationPolicy.evaluateAndConsolidate(exp2)
        assertFalse(dec2.promoted)

        // Third occurrence: promoted to PROCEDURAL memory
        val dec3 = consolidationPolicy.evaluateAndConsolidate(exp3)
        assertTrue(dec3.promoted)
        assertEquals(MemoryType.PROCEDURAL, dec3.targetMemoryType)
        assertNotNull(dec3.consolidatedId)

        // Verify stored entry in shared memory
        val saved = memoryManager.getById(dec3.consolidatedId!!, MemoryCallerRole.SYSTEM_CORE)
        assertNotNull(saved)
        assertEquals(MemoryType.PROCEDURAL, saved!!.memoryType)
        assertTrue(saved.content.contains("PROCEDURAL_GUIDE"))
    }

    // 5. GEN-362 Consolidation: Strong Negative Promotes to Failure, Neutral Retained
    @Test
    fun testNegativePromotesToFailureAndNeutralRetained() {
        val negativeExp = ExperienceRecord(
            interaction_id = "exp-fail-1",
            timestamp = 1000L,
            input_summary = "Call external weather API",
            output_summary = "Timeout",
            signal_type = "implicit",
            signal_value = -1.0f,
            signal_source = "user_repeated_request",
            execution = ExecutionDetails("network_api", 5000L, failure_reason = "SocketTimeoutException")
        )

        val failDec = consolidationPolicy.evaluateAndConsolidate(negativeExp)
        assertTrue("Strong negative must be promoted to FAILURE memory", failDec.promoted)
        assertEquals(MemoryType.FAILURE, failDec.targetMemoryType)

        val neutralExp = negativeExp.copy(
            interaction_id = "exp-neutral-1",
            signal_value = 0.0f
        )
        val neutralDec = consolidationPolicy.evaluateAndConsolidate(neutralExp)
        assertFalse("Neutral signal must NOT be promoted to long-term memory", neutralDec.promoted)
    }

    // 6. GEN-363 Adaptive Ranking Weight Update: Tuning GEN-MEM-3 weights dynamically
    @Test
    fun testAdaptiveRankingWeightUpdateAndNormalization() {
        val initialWeights = scorer.getCurrentWeights()
        assertEquals(0.5f, initialWeights.wRelevance, 0.001f)
        assertEquals(0.25f, initialWeights.wRecency, 0.001f)
        assertEquals(0.25f, initialWeights.wImportance, 0.001f)

        // Boost recency with positive signal
        val updated = updater.updateWeights(
            signalValue = 1.0f,
            boostTarget = WeightComponent.RECENCY,
            reason = "Positive feedback on recent events query"
        )

        assertEquals(2L, updater.getCurrentGenerationId())
        assertTrue("Recency weight should have increased", updated.wRecency > 0.25f)
        // Verify weights sum to 1.0f
        val sum = updated.wRelevance + updated.wRecency + updated.wImportance
        assertEquals(1.0f, sum, 0.001f)

        // Verify live scorer immediately uses updated weights
        assertEquals(updated, scorer.getCurrentWeights())

        // Test Rollback capability
        val rolledBack = updater.rollbackToPreviousGeneration()
        assertNotNull(rolledBack)
        assertEquals(1L, updater.getCurrentGenerationId())
        assertEquals(0.25f, scorer.getCurrentWeights().wRecency, 0.001f)
    }

    // 7. GEN-363 Shadow Evaluation Rejection: Regression in Shadow Mode causes Rollback without Commit
    @Test
    fun testShadowEvaluationRejectionPreventsCommitAndPreservesBaseline() {
        val failingValidator = object : WeightCommitValidator {
            override fun evaluateShadowCandidate(
                baselineWeights: UnifiedScoringWeights,
                candidateWeights: UnifiedScoringWeights,
                shadowInteractionsCount: Int
            ): Boolean {
                // Simulate regression detected during shadow evaluation
                return false
            }
        }

        val guardedUpdater = AdaptiveRankingWeightUpdater(
            scorer = scorer,
            learningRate = 0.05f,
            validator = failingValidator
        )

        val baselineWeights = scorer.getCurrentWeights()
        val initialGen = guardedUpdater.getCurrentGenerationId()

        // Attempt update with candidate that fails shadow evaluation
        val result = guardedUpdater.proposeAndEvaluateUpdate(
            signalValue = 1.0f,
            boostTarget = WeightComponent.RELEVANCE,
            reason = "Regressive candidate",
            shadowInteractionsCount = 20
        )

        assertFalse("Update must NOT be committed when shadow evaluation fails", result.committed)
        assertNotNull(result.rejectionReason)
        assertEquals("Generation ID must remain unchanged upon rejection", initialGen, guardedUpdater.getCurrentGenerationId())
        assertEquals("Scorer weights must remain unchanged at baseline", baselineWeights, scorer.getCurrentWeights())
    }

    // 8. GEN-363 Shadow Evaluation Success: Better/Equal Candidate Commits with New Generation ID
    @Test
    fun testShadowEvaluationSuccessCommitsNewWeightGeneration() {
        val approvingValidator = object : WeightCommitValidator {
            override fun evaluateShadowCandidate(
                baselineWeights: UnifiedScoringWeights,
                candidateWeights: UnifiedScoringWeights,
                shadowInteractionsCount: Int
            ): Boolean {
                // Shadow evaluation confirms candidate is better/equal
                return true
            }
        }

        val guardedUpdater = AdaptiveRankingWeightUpdater(
            scorer = scorer,
            learningRate = 0.05f,
            validator = approvingValidator
        )

        val initialGen = guardedUpdater.getCurrentGenerationId()

        val result = guardedUpdater.proposeAndEvaluateUpdate(
            signalValue = 1.0f,
            boostTarget = WeightComponent.RELEVANCE,
            reason = "Approved shadow evaluation candidate",
            shadowInteractionsCount = 25
        )

        assertTrue("Update must be committed when shadow evaluation succeeds", result.committed)
        assertEquals(initialGen + 1, guardedUpdater.getCurrentGenerationId())
        assertEquals(result.effectiveWeights, scorer.getCurrentWeights())
        assertTrue("Relevance weight must have increased", result.effectiveWeights.wRelevance > 0.5f)
    }

    // 9. GEN-363 <-> GEN-CM-3 Real Integration Test: Adaptive updater uses real PreCommitValidator
    @Test
    fun testAdaptiveRankingWeightUpdaterRealPreCommitValidatorIntegration() {
        val realValidator = shared.change_management.PreCommitValidator()
        val fullyIntegratedUpdater = AdaptiveRankingWeightUpdater(
            scorer = scorer,
            learningRate = 0.04f,
            validator = realValidator
        )

        val initialGen = fullyIntegratedUpdater.getCurrentGenerationId()

        // Valid moderate update passes real PreCommitValidator
        val approvedResult = fullyIntegratedUpdater.proposeAndEvaluateUpdate(
            signalValue = 1.0f,
            boostTarget = WeightComponent.RECENCY,
            reason = "Real GEN-CM-3 validation test"
        )
        assertTrue("Real PreCommitValidator must approve valid candidate", approvedResult.committed)
        assertEquals(initialGen + 1, fullyIntegratedUpdater.getCurrentGenerationId())

        // Extreme degradation test (drastically cutting relevance by 90%)
        val regressiveCandidate = UnifiedScoringWeights(
            wRelevance = 0.05f, // Baseline is 0.5f -> 0.05f drops below 50% threshold
            wRecency = 0.45f,
            wImportance = 0.50f
        )
        val rejectedByRealValidator = realValidator.evaluateShadowCandidate(
            baselineWeights = UnifiedScoringWeights(0.5f, 0.25f, 0.25f),
            candidateWeights = regressiveCandidate
        )
        assertFalse("Real PreCommitValidator must reject severe relevance collapse", rejectedByRealValidator)
    }
}
