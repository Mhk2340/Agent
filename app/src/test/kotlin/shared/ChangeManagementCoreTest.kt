package shared

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import memory.AesGcmCipher
import memory.EncryptedMemoryStorage
import memory.MemoryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import persistence.AgentDatabase
import shared.change_management.ChangeProvenanceLogger
import testutil.RoomTestEnvironment
import testutil.TestUnlockedAppLockAccess
import shared.change_management.AllowedChangeTarget
import shared.change_management.ChangeAuditLogger
import shared.change_management.ChangeAuditRecord
import shared.change_management.GoldenTestCase
import shared.change_management.GoldenTestSetRepository
import shared.change_management.IsolatedEvaluationSandbox
import shared.change_management.RollbackManager

@RunWith(RobolectricTestRunner::class)
class ChangeManagementCoreTest {

    private lateinit var database: AgentDatabase
    private lateinit var cipher: AesGcmCipher

    @Before
    fun setup() {
        cipher = RoomTestEnvironment.newCipher()
        database = RoomTestEnvironment.inMemoryDatabase(
            ApplicationProvider.getApplicationContext<Context>(),
            cipher
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // GEN-CM-1 Golden Test Set & Frozen Holdout separation
    @Test
    fun testGoldenTestSetSeparation() {
        val repo = GoldenTestSetRepository()
        val holdout = repo.getFrozenHoldoutSet()
        assertEquals(2, holdout.size)
        assertTrue(holdout.all { it.isFrozenHoldout })

        // Adding regression test works
        val regTest = GoldenTestCase("reg-01", "Query memory", "retrieve_memory", 0.7f, isFrozenHoldout = false)
        repo.addRegressionTest(regTest)
        assertEquals(1, repo.getRegressionSet().size)

        // Adding to frozen holdout directly fails
        try {
            repo.addRegressionTest(GoldenTestCase("illegal", "...", "...", 0.5f, isFrozenHoldout = true))
            assertTrue("Adding holdout dynamically must fail", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Cannot add dynamically to frozen holdout set"))
        }
    }

    // GEN-CM-2 Isolated Evaluation Sandbox
    @Test
    fun testIsolatedEvaluationSandbox() {
        val sandbox = IsolatedEvaluationSandbox()
        sandbox.stage("ranking_weight_recency", "0.45")
        assertEquals("0.45", sandbox.read("ranking_weight_recency"))

        sandbox.discard()
        assertEquals(null, sandbox.read("ranking_weight_recency"))
    }

    // GEN-CM-5 Rollback Mechanism
    @Test
    fun testRollbackMechanism() {
        val rollbackManager = RollbackManager()
        val key = "procedure_battery_opt"

        rollbackManager.applyChange(key, "v1_content")
        rollbackManager.applyChange(key, "v2_experimental_content")
        assertEquals("v2_experimental_content", rollbackManager.getCurrent(key))

        val rolledBack = rollbackManager.rollback(key)
        assertTrue(rolledBack)
        assertEquals("v1_content", rollbackManager.getCurrent(key))

        // Second rollback when no further history
        val furtherRollback = rollbackManager.rollback(key)
        assertFalse(furtherRollback)
    }

    // GEN-CM-7 Change Audit Log
    @Test
    fun testChangeAuditLog() {
        val logger = ChangeAuditLogger(database, cipher)
        val auditRecord = ChangeAuditRecord(
            changeId = "chg-101",
            targetType = "WEIGHT",
            targetKey = "weight_similarity",
            status = "COMMITTED",
            reason = "Passed holdout evaluation score > 0.85",
            timestamp = 1726000000L,
            schemaVersion = 1
        )

        logger.record(auditRecord)
        val logs = logger.getAllRecords()
        assertEquals(1, logs.size)
        assertEquals("chg-101", logs[0].changeId)
        assertEquals("COMMITTED", logs[0].status)
        assertFalse(database.changeAuditDao().getAll().single().reasonCiphertext.contains(auditRecord.reason))
    }

    // GEN-CM-8 Change Safety Guardrails Allowlist Test
    @Test
    fun testChangeSafetyGuardrailsAllowlist() {
        val guardrails = shared.change_management.ChangeSafetyGuardrails()

        // Permitted targets (PROMPT, MEMORY, WEIGHT)
        assertTrue(guardrails.evaluateProposedTarget("PROMPT").permitted)
        assertTrue(guardrails.evaluateProposedTarget("MEMORY").permitted)
        assertTrue(guardrails.evaluateProposedTarget("WEIGHT").permitted)

        // Denied targets (Code, Skill Definition, Safety policies, Registries)
        val deniedTargets = listOf(
            "CODE",
            "SKILL_DEFINITION",
            "TOOL_REGISTRY",
            "RISK_REGISTRY",
            "RATE_LIMIT_CONFIG",
            "PROVIDER_CREDENTIALS",
            "BUILD_CONFIG",
            "SAFETY_POLICY"
        )

        for (target in deniedTargets) {
            val eval = guardrails.evaluateProposedTarget(target)
            assertFalse("Target '$target' must be strictly denied by GEN-CM-8 Allowlist", eval.permitted)
            assertTrue(eval.reason.contains("STRICTLY DENIED"))
        }
    }

    // GEN-CM-6 Change Provenance Log Append-Only Test with Shared DB Architecture
    @Test
    fun testChangeProvenanceLoggerAppendOnly() {
        val storage = EncryptedMemoryStorage(database, cipher)
        val memoryManager = MemoryManager(storage = storage, appLockAccess = TestUnlockedAppLockAccess)
        val provenanceLogger = ChangeProvenanceLogger(memoryManager = memoryManager, cipher = cipher)

        val record = provenanceLogger.recordProvenance(
            changeId = "chg-weight-01",
            triggerSignalId = "sig-exp-99",
            sourceExperienceId = "exp-99",
            rationale = "User rewarded recent event query accuracy",
            proposedTarget = "WEIGHT"
        )

        org.junit.Assert.assertNotNull(record.provenanceId)
        assertEquals("WEIGHT", record.proposedTarget)
        assertEquals(1, record.schemaVersion)

        // Verify provenance record is persisted into shared LongTermMemory store
        assertEquals(1, memoryManager.getTotalCount(memory.MemoryCallerRole.SYSTEM_CORE))
        val allProv = provenanceLogger.getAllRecords()
        assertEquals(1, allProv.size)
        assertEquals("chg-weight-01", allProv[0].changeId)
        assertFalse(database.changeProvenanceDao().getAll().single().rationaleCiphertext.contains(record.rationale))
    }

    // GEN-CM-1 Dedicated Retrieval Evaluation Subset Test
    @Test
    fun testGoldenTestSetRetrievalEvaluationSubset() {
        val repo = GoldenTestSetRepository()
        val retrievalHoldout = repo.getRetrievalHoldoutSet()

        assertTrue("Retrieval holdout subset must be populated for GEN-CM-3", retrievalHoldout.isNotEmpty())
        assertEquals(2, retrievalHoldout.size)
        assertTrue(retrievalHoldout.all { it.isFrozenHoldout })
        assertTrue(retrievalHoldout.all { it.category == "retrieval_ranking" })
        assertTrue(retrievalHoldout.all { it.expectedRelevantKeywords.isNotEmpty() })
    }

    // 8. GEN-CM-3 PreCommitValidator Full Pipeline Validation Test
    @Test
    fun testPreCommitValidatorPipeline() {
        val validator = shared.change_management.PreCommitValidator()

        // Valid change on allowed target
        val validChange = shared.change_management.ProposedChange(
            changeId = "chg-prompt-1",
            targetType = "PROMPT",
            targetKey = "system_instructions",
            previousValue = "v1",
            proposedValue = "v2",
            provenanceSignalId = "sig-01",
            timestamp = 1000L
        )
        val validResult = validator.validateProposedChange(validChange)
        assertTrue("Valid prompt change must be approved", validResult.approved)
        assertEquals(2, validResult.passedCases)

        // Invalid change on forbidden target (CODE)
        val forbiddenChange = validChange.copy(targetType = "CODE")
        val forbiddenResult = validator.validateProposedChange(forbiddenChange)
        assertFalse("Forbidden target CODE must be rejected by PreCommitValidator", forbiddenResult.approved)
        assertTrue(forbiddenResult.details.contains("STRICTLY DENIED"))
    }

    // 9. GEN-CM-4 Regression Evaluation Harness Test
    @Test
    fun testRegressionEvaluationHarness() {
        val harness = shared.change_management.RegressionEvaluationHarness()
        val report = harness.runFullRegressionSuite()

        assertEquals(3, report.totalSuitesRun)
        assertTrue("Total cases tested must be > 0", report.totalCasesTested > 0)
        assertTrue("Holdout suite must pass", report.holdoutPassed)
        assertTrue("Retrieval suite must pass", report.retrievalPassed)
        assertTrue("Overall regression suite must pass", report.overallPassed)
    }

    // 10. GEN-CM-9 Confidence Scoring & GEN-CM-11 Governance Lockout Test
    @Test
    fun testConfidenceScoringAndGovernanceLockout() {
        val validator = shared.change_management.PreCommitValidator()
        val targetKey = "WEIGHT_RANKING"

        assertFalse("Target should not be locked out initially", validator.isTargetLockedOut(targetKey))

        // Record rollbacks up to threshold (3)
        validator.recordRollbackEvent(targetKey)
        validator.recordRollbackEvent(targetKey)
        assertFalse(validator.isTargetLockedOut(targetKey))

        validator.recordRollbackEvent(targetKey)
        assertTrue("Target must be locked out after 3 consecutive rollbacks", validator.isTargetLockedOut(targetKey))

        // When locked out, proposed changes for that target are blocked
        val proposedChange = shared.change_management.ProposedChange(
            changeId = "chg-weight-fail",
            targetType = "WEIGHT",
            targetKey = targetKey,
            previousValue = "0.5",
            proposedValue = "0.6",
            provenanceSignalId = "sig-1",
            timestamp = 1000L
        )
        val result = validator.validateProposedChange(proposedChange)
        assertFalse("Locked out target change must be rejected", result.approved)
        assertTrue(result.details.contains("locked out"))
    }

    // 11. GEN-CM-10 Experiment Tracker & GEN-CM-12 Evaluation Policy Resolution Test
    @Test
    fun testExperimentTrackerAndEvaluationPolicy() {
        val tracker = shared.change_management.ExperimentTracker()
        tracker.recordExperiment(
            experimentId = "exp-001",
            targetKey = "RANKING_WEIGHTS",
            metricName = "relevance_score",
            metricValue = 0.88f,
            snapshotValue = "{w_rel: 0.5}"
        )

        val entries = tracker.getEntriesForTarget("RANKING_WEIGHTS")
        assertEquals(1, entries.size)
        assertEquals(0.88f, entries[0].metricValue, 0.001f)

        // Evaluation Policy resolution
        val policyManager = shared.change_management.EvaluationPolicyManager()
        val weightPolicy = policyManager.resolvePolicy("WEIGHT")
        assertFalse("Weight evaluation does not require heavy isolated sandbox", weightPolicy.requiresIsolatedSandbox)
        assertTrue("Weight evaluation requires shadow evaluation", weightPolicy.requiresShadowEvaluation)
        assertEquals(20, weightPolicy.minimumShadowInteractions)

        val promptPolicy = policyManager.resolvePolicy("PROMPT")
        assertTrue("Prompt evaluation strictly requires isolated sandbox", promptPolicy.requiresIsolatedSandbox)
        assertEquals(0.90f, promptPolicy.requiredConfidenceFloor, 0.001f)
    }
}
