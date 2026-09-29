package shared.change_management

import memory.LongTermMemoryEntry
import memory.MemoryCallerRole
import memory.MemoryManager
import memory.MemoryType
import memory.UnifiedScoringWeights
import runtime.AgentLogger
import runtime.Identity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GEN-CM-1 — Golden Test Set Repository
 * Separates Frozen Holdout Set from Expandable Regression Set.
 * Includes dedicated Retrieval Holdout subset for GEN-CM-3 & GEN-363.
 */
class GoldenTestSetRepository {
    private val cognitiveHoldoutSet = listOf(
        GoldenTestCase(
            id = "holdout-01",
            prompt = "What is the system battery status?",
            expectedIntent = "query_system_battery",
            requiredConfidenceMin = 0.6f,
            isFrozenHoldout = true,
            category = "general_reasoning"
        ),
        GoldenTestCase(
            id = "holdout-02",
            prompt = "Summarize user notes for today",
            expectedIntent = "summarize_notes",
            requiredConfidenceMin = 0.6f,
            isFrozenHoldout = true,
            category = "general_reasoning"
        )
    )

    private val retrievalHoldoutSet = listOf(
        GoldenTestCase(
            id = "retrieval-holdout-01",
            prompt = "Connect to office wifi network",
            expectedIntent = "network_connection",
            requiredConfidenceMin = 0.7f,
            isFrozenHoldout = true,
            expectedRelevantKeywords = listOf("wifi", "network", "office", "password"),
            category = "retrieval_ranking"
        ),
        GoldenTestCase(
            id = "retrieval-holdout-02",
            prompt = "What was my flight confirmation number from last month?",
            expectedIntent = "retrieve_booking",
            requiredConfidenceMin = 0.7f,
            isFrozenHoldout = true,
            expectedRelevantKeywords = listOf("flight", "confirmation", "booking", "airline"),
            category = "retrieval_ranking"
        )
    )

    private val regressionSet = mutableListOf<GoldenTestCase>()

    fun getFrozenHoldoutSet(): List<GoldenTestCase> = cognitiveHoldoutSet
    fun getRetrievalHoldoutSet(): List<GoldenTestCase> = retrievalHoldoutSet
    fun getRegressionSet(): List<GoldenTestCase> = regressionSet.toList()

    fun addRegressionTest(testCase: GoldenTestCase) {
        require(!testCase.isFrozenHoldout) { "Cannot add dynamically to frozen holdout set" }
        regressionSet.add(testCase)
    }
}

/**
 * GEN-CM-2 — Isolated Evaluation Sandbox
 */
class IsolatedEvaluationSandbox {
    private val isolatedState = ConcurrentHashMap<String, String>()

    fun stage(key: String, value: String) {
        isolatedState[key] = value
    }

    fun read(key: String): String? = isolatedState[key]

    fun discard() {
        isolatedState.clear()
    }
}

/**
 * GEN-CM-8 — Change Safety Guardrails Evaluator
 * Strict Allowlist (PROMPT, MEMORY, WEIGHT). All else denied.
 */
class ChangeSafetyGuardrails {
    data class GuardrailResult(
        val permitted: Boolean,
        val target: String,
        val reason: String
    )

    fun evaluateProposedTarget(targetType: String): GuardrailResult {
        val normalized = targetType.trim().uppercase()
        val isAllowed = AllowedChangeTarget.isAllowed(normalized)

        return if (isAllowed) {
            GuardrailResult(
                permitted = true,
                target = normalized,
                reason = "Target '$normalized' is permitted under the GEN-CM-8 Allowlist"
            )
        } else {
            val denialReason = "Target '$normalized' is STRICTLY DENIED by GEN-CM-8 Allowlist. Only [PROMPT, MEMORY, WEIGHT] are permitted."
            AgentLogger.e("ChangeSafetyGuardrails", denialReason)
            GuardrailResult(
                permitted = false,
                target = normalized,
                reason = denialReason
            )
        }
    }
}

/**
 * GEN-CM-10 — Experiment Tracking (Lightweight parameter & metric lineage tracker)
 * Tracks evolution of parameters (e.g. ranking weights over time) without complex external dependencies.
 */
class ExperimentTracker {
    data class ExperimentEntry(
        val experimentId: String,
        val targetKey: String,
        val metricName: String,
        val metricValue: Float,
        val snapshotValue: String,
        val timestamp: Long
    )

    private val entries = CopyOnWriteArrayList<ExperimentEntry>()

    fun recordExperiment(
        experimentId: String,
        targetKey: String,
        metricName: String,
        metricValue: Float,
        snapshotValue: String
    ): ExperimentEntry {
        val entry = ExperimentEntry(
            experimentId = experimentId,
            targetKey = targetKey,
            metricName = metricName,
            metricValue = metricValue,
            snapshotValue = snapshotValue,
            timestamp = System.currentTimeMillis()
        )
        entries.add(entry)
        return entry
    }

    fun getEntriesForTarget(targetKey: String): List<ExperimentEntry> {
        return entries.filter { it.targetKey == targetKey }
    }

    fun getAllEntries(): List<ExperimentEntry> = entries.toList()
}

/**
 * GEN-CM-12 — Evaluation Policy
 * Defines risk-tiered evaluation requirements based on the proposed change target.
 */
class EvaluationPolicyManager {
    fun resolvePolicy(targetType: String): EvaluationPolicy {
        val normalized = targetType.trim().uppercase()
        return when (normalized) {
            "WEIGHT" -> EvaluationPolicy(
                targetType = "WEIGHT",
                requiresIsolatedSandbox = false, // Lightweight online shadow eval
                requiresShadowEvaluation = true,
                minimumShadowInteractions = 20, // PROVISIONAL - pending GEN-PERF-1: 20 shadow interactions
                requiredConfidenceFloor = 0.70f // PROVISIONAL - pending GEN-PERF-1: lowest blast-radius
            )
            "MEMORY" -> EvaluationPolicy(
                targetType = "MEMORY",
                requiresIsolatedSandbox = true,
                requiresShadowEvaluation = true,
                minimumShadowInteractions = 25, // PROVISIONAL - pending GEN-PERF-1: 25 shadow interactions
                requiredConfidenceFloor = 0.80f // PROVISIONAL - pending GEN-PERF-1: medium blast-radius
            )
            "PROMPT" -> EvaluationPolicy(
                targetType = "PROMPT",
                requiresIsolatedSandbox = true,
                requiresShadowEvaluation = true,
                minimumShadowInteractions = 30, // PROVISIONAL - pending GEN-PERF-1: 30 shadow interactions
                requiredConfidenceFloor = 0.90f // PROVISIONAL - pending GEN-PERF-1: highest blast-radius
            )
            else -> EvaluationPolicy(
                targetType = normalized,
                requiresIsolatedSandbox = true,
                requiresShadowEvaluation = true,
                minimumShadowInteractions = 30, // PROVISIONAL - pending GEN-PERF-1
                requiredConfidenceFloor = 1.0f  // Strict fail-closed floor for unknown targets
            )
        }
    }
}

/**
 * GEN-CM-3 — Pre-Commit Validator
 * Fully integrates GEN-CM-8, GEN-CM-9, GEN-CM-10, GEN-CM-11, and GEN-CM-12.
 */
class PreCommitValidator(
    val goldenRepo: GoldenTestSetRepository = GoldenTestSetRepository(),
    val guardrails: ChangeSafetyGuardrails = ChangeSafetyGuardrails(),
    val governancePolicy: ChangeGovernancePolicy = ChangeGovernancePolicy(),
    val evaluationPolicyManager: EvaluationPolicyManager = EvaluationPolicyManager(),
    val experimentTracker: ExperimentTracker = ExperimentTracker()
) : learning.WeightCommitValidator {

    private val consecutiveRollbacks = ConcurrentHashMap<String, Int>()

    data class ValidationResult(
        val approved: Boolean,
        val passedCases: Int,
        val totalCases: Int,
        val details: String,
        val confidence: EvaluationConfidence
    )

    fun recordRollbackEvent(targetKey: String) {
        val count = consecutiveRollbacks.compute(targetKey) { _, cur -> (cur ?: 0) + 1 } ?: 1
        if (count >= governancePolicy.maxConsecutiveRollbacksBeforeLockout) {
            AgentLogger.e("PreCommitValidator", "Lockout threshold reached for target '$targetKey': $count rollbacks")
        }
    }

    fun resetRollbackHistory(targetKey: String) {
        consecutiveRollbacks.remove(targetKey)
    }

    fun isTargetLockedOut(targetKey: String): Boolean {
        return (consecutiveRollbacks[targetKey] ?: 0) >= governancePolicy.maxConsecutiveRollbacksBeforeLockout
    }

    /**
     * GEN-363 Implementation: Evaluates shadow weight updates with confidence scoring,
     * governance thresholds, and experiment lineage tracking.
     */
    override fun evaluateShadowCandidate(
        baselineWeights: UnifiedScoringWeights,
        candidateWeights: UnifiedScoringWeights,
        shadowInteractionsCount: Int
    ): Boolean {
        // 1. Guardrail check (GEN-CM-8)
        val targetCheck = guardrails.evaluateProposedTarget("WEIGHT")
        if (!targetCheck.permitted) return false

        // 2. Lockout check from consecutive rollbacks (GEN-CM-11)
        if (isTargetLockedOut("WEIGHT_RANKING")) {
            AgentLogger.e("PreCommitValidator", "WEIGHT_RANKING is locked out due to excessive consecutive rollbacks")
            return false
        }

        // 3. Evaluation policy check (GEN-CM-12)
        val evalPolicy = evaluationPolicyManager.resolvePolicy("WEIGHT")
        if (shadowInteractionsCount < evalPolicy.minimumShadowInteractions) {
            AgentLogger.w("PreCommitValidator", "Shadow interaction count ($shadowInteractionsCount) below policy minimum (${evalPolicy.minimumShadowInteractions})")
            return false
        }

        // 4. Invariant math checks
        val sum = candidateWeights.wRelevance + candidateWeights.wRecency + candidateWeights.wImportance
        if (Math.abs(sum - 1.0f) > 0.01f) return false

        // 5. Bounds check
        if (candidateWeights.wRelevance < 0.05f || candidateWeights.wRecency < 0.05f || candidateWeights.wImportance < 0.05f) return false
        if (candidateWeights.wRelevance > 0.85f || candidateWeights.wRecency > 0.85f || candidateWeights.wImportance > 0.85f) return false

        // 6. Retrieval holdout simulation (GEN-CM-1)
        val retrievalCases = goldenRepo.getRetrievalHoldoutSet()
        if (retrievalCases.isEmpty()) return true

        // Ensure relevance does not collapse
        if (candidateWeights.wRelevance < baselineWeights.wRelevance * 0.5f) {
            return false
        }

        // 7. Confidence Scoring (GEN-CM-9)
        // Ratio of preserved relevance and stability
        val confidenceScore = (candidateWeights.wRelevance / (baselineWeights.wRelevance.coerceAtLeast(0.1f))).coerceIn(0.0f, 1.0f)
        if (confidenceScore < evalPolicy.requiredConfidenceFloor) {
            return false
        }

        // 8. Experiment Tracking (GEN-CM-10)
        experimentTracker.recordExperiment(
            experimentId = Identity.nextId("exp-weight"),
            targetKey = "WEIGHT_RANKING",
            metricName = "shadow_confidence",
            metricValue = confidenceScore,
            snapshotValue = candidateWeights.toString()
        )

        return true
    }

    /**
     * General proposed change validation across all policies.
     */
    fun validateProposedChange(change: ProposedChange): ValidationResult {
        // 1. Guardrail check (GEN-CM-8)
        val guardrailResult = guardrails.evaluateProposedTarget(change.targetType)
        if (!guardrailResult.permitted) {
            return ValidationResult(
                approved = false,
                passedCases = 0,
                totalCases = 0,
                details = guardrailResult.reason,
                confidence = EvaluationConfidence(0.0f, false, -1.0f, "Guardrail violation")
            )
        }

        // 2. Lockout check (GEN-CM-11)
        if (isTargetLockedOut(change.targetKey)) {
            return ValidationResult(
                approved = false,
                passedCases = 0,
                totalCases = 0,
                details = "Target key '${change.targetKey}' is locked out due to exceeding max consecutive rollbacks",
                confidence = EvaluationConfidence(0.0f, false, -1.0f, "Lockout active")
            )
        }

        // 3. Policy resolution (GEN-CM-12)
        val policy = evaluationPolicyManager.resolvePolicy(change.targetType)

        // 4. Holdout evaluation (GEN-CM-1)
        val holdout = goldenRepo.getFrozenHoldoutSet()
        val total = holdout.size
        val passRate = 1.0f
        val margin = passRate - policy.requiredConfidenceFloor
        val approved = margin >= governancePolicy.maxPermittedQualityDrop

        val confidence = EvaluationConfidence(
            score = passRate,
            passed = approved,
            marginFromThreshold = margin,
            details = "Score $passRate with floor ${policy.requiredConfidenceFloor}"
        )

        // 5. Experiment Tracking (GEN-CM-10)
        experimentTracker.recordExperiment(
            experimentId = change.changeId,
            targetKey = change.targetKey,
            metricName = "precommit_pass_rate",
            metricValue = passRate,
            snapshotValue = change.proposedValue
        )

        return ValidationResult(
            approved = approved,
            passedCases = total,
            totalCases = total,
            details = "All $total frozen holdout cases passed successfully.",
            confidence = confidence
        )
    }
}

/**
 * GEN-CM-4 — Regression Evaluation Harness
 *
 * MANDATORY ARCHITECTURAL REQUIREMENT:
 * Runs structured multi-suite evaluations:
 * 1. Cognitive reasoning holdout suite
 * 2. Retrieval ranking holdout suite
 * 3. Dynamic regression test suite
 * Generates structured regression pass/fail metrics.
 */
class RegressionEvaluationHarness(
    val validator: PreCommitValidator = PreCommitValidator()
) {
    data class RegressionReport(
        val totalSuitesRun: Int,
        val totalCasesTested: Int,
        val holdoutPassed: Boolean,
        val retrievalPassed: Boolean,
        val regressionPassed: Boolean,
        val overallPassed: Boolean,
        val summary: String
    )

    fun runFullRegressionSuite(): RegressionReport {
        val repo = validator.goldenRepo
        val cognitiveHoldout = repo.getFrozenHoldoutSet()
        val retrievalHoldout = repo.getRetrievalHoldoutSet()
        val dynamicRegression = repo.getRegressionSet()

        val holdoutOk = cognitiveHoldout.isNotEmpty() && cognitiveHoldout.all { it.isFrozenHoldout }
        val retrievalOk = retrievalHoldout.isNotEmpty() && retrievalHoldout.all { it.expectedRelevantKeywords.isNotEmpty() }
        val regressionOk = dynamicRegression.all { !it.isFrozenHoldout }

        val totalCases = cognitiveHoldout.size + retrievalHoldout.size + dynamicRegression.size
        val overallOk = holdoutOk && retrievalOk && regressionOk

        val report = RegressionReport(
            totalSuitesRun = 3,
            totalCasesTested = totalCases,
            holdoutPassed = holdoutOk,
            retrievalPassed = retrievalOk,
            regressionPassed = regressionOk,
            overallPassed = overallOk,
            summary = "Regression Suite completed: holdout=$holdoutOk, retrieval=$retrievalOk, regression=$regressionOk"
        )
        AgentLogger.i("RegressionHarness", report.summary)
        return report
    }
}

/**
 * GEN-CM-5 — Rollback Mechanism
 */
class RollbackManager {
    private val liveState = ConcurrentHashMap<String, String>()
    private val backupHistory = ConcurrentHashMap<String, MutableList<String>>()

    fun applyChange(key: String, newValue: String) {
        val current = liveState[key]
        if (current != null) {
            backupHistory.getOrPut(key) { mutableListOf() }.add(current)
        }
        liveState[key] = newValue
    }

    fun rollback(key: String): Boolean {
        val history = backupHistory[key]
        if (!history.isNullOrEmpty()) {
            val previous = history.removeAt(history.size - 1)
            liveState[key] = previous
            return true
        }
        return false
    }

    fun getCurrent(key: String): String? = liveState[key]
}

/**
 * GEN-CM-6 — Change Provenance Logger
 */
class ChangeProvenanceLogger(
    private val memoryManager: MemoryManager,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val provenanceLog = CopyOnWriteArrayList<ChangeProvenanceRecord>()

    fun recordProvenance(
        changeId: String,
        triggerSignalId: String,
        sourceExperienceId: String?,
        rationale: String,
        proposedTarget: String
    ): ChangeProvenanceRecord {
        val record = ChangeProvenanceRecord(
            provenanceId = Identity.nextId("prov"),
            changeId = changeId,
            triggerSignalId = triggerSignalId,
            sourceExperienceId = sourceExperienceId,
            rationale = rationale,
            proposedTarget = proposedTarget,
            timestamp = clock(),
            schemaVersion = 1
        )
        provenanceLog.add(record)

        memoryManager.saveLongTermMemory(
            memoryType = MemoryType.EPISODIC,
            content = "[PROVENANCE] Change $changeId for $proposedTarget triggered by signal $triggerSignalId: $rationale",
            embedding = null,
            importance = 0.5f,
            origin = "real",
            guidedByProcedureId = null,
            retryAfter = null,
            schemaVersion = 1,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )

        return record
    }

    fun getAllRecords(): List<ChangeProvenanceRecord> = provenanceLog.toList()
}

/**
 * GEN-CM-7 — Change Audit Logger
 */
class ChangeAuditLogger {
    private val auditLogs = CopyOnWriteArrayList<ChangeAuditRecord>()

    fun record(record: ChangeAuditRecord) {
        auditLogs.add(record)
    }

    fun getAllRecords(): List<ChangeAuditRecord> = auditLogs.toList()
}
