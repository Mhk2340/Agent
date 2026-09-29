package learning

import memory.UnifiedRetrievalScorer
import memory.UnifiedScoringWeights
import runtime.AgentLogger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Interface representing the Shadow Evaluation & Pre-Commit Validator (GEN-CM-3).
 * Implemented by shared.change_management.PreCommitValidator in Common Infrastructure (Step 10).
 */
interface WeightCommitValidator {
    /**
     * Evaluates candidate weights against baseline weights across shadow evaluation criteria.
     * Returns true if candidate is better or equal, false if regression is detected.
     */
    fun evaluateShadowCandidate(
        baselineWeights: UnifiedScoringWeights,
        candidateWeights: UnifiedScoringWeights,
        shadowInteractionsCount: Int = 20
    ): Boolean
}

/**
 * GEN-363 — Adaptive Ranking Weight Update
 *
 * CRITICAL SAFETY REQUIREMENT (Shadow Evaluation before Commit):
 * No weight update is committed directly into production.
 * Every proposed candidate must first pass through Shadow Evaluation (PreCommitValidator / GEN-CM-3)
 * across 20-30 interactions:
 * - If candidate is equal or better -> Commit with incremented weight_generation_id
 * - If candidate shows regression -> Rollback / Abort without applying changes
 *
 * Prevents runaway negative feedback loops (bad weight -> worse retrieval -> negative signal -> worse weight).
 */
class AdaptiveRankingWeightUpdater(
    private val scorer: UnifiedRetrievalScorer,
    val learningRate: Float = 0.02f,
    // Fully integrated with real GEN-CM-3 PreCommitValidator
    private val validator: WeightCommitValidator = shared.change_management.PreCommitValidator(),
    private val errorBudgetController: reliability.ErrorBudgetController? = null
) {
    private var currentGenerationId: Long = 1L
    private val weightSnapshots = CopyOnWriteArrayList<WeightSnapshot>()

    data class WeightSnapshot(
        val generationId: Long,
        val weights: UnifiedScoringWeights,
        val triggerSignal: String,
        val timestamp: Long
    )

    data class UpdateEvaluationResult(
        val committed: Boolean,
        val effectiveWeights: UnifiedScoringWeights,
        val generationId: Long,
        val rejectionReason: String? = null
    )

    init {
        // Record initial baseline generation #1
        weightSnapshots.add(
            WeightSnapshot(
                generationId = currentGenerationId,
                weights = scorer.getCurrentWeights(),
                triggerSignal = "initial_baseline",
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /**
     * Executes adaptive weight calculation followed by MANDATORY SHADOW EVALUATION before commit.
     */
    @Synchronized
    fun proposeAndEvaluateUpdate(
        signalValue: Float, // +1.0f or -1.0f
        boostTarget: WeightComponent,
        reason: String,
        shadowInteractionsCount: Int = 20
    ): UpdateEvaluationResult {
        val current = scorer.getCurrentWeights()

        // GEN-SRE-4: Check Error Budget before proceeding with any automated mutation
        if (errorBudgetController != null && !errorBudgetController.isChangeMechanismPermitted()) {
            AgentLogger.w(
                "AdaptiveWeightUpdater",
                "Update rejected: Error Budget is EXHAUSTED (GEN-SRE-4). Automatic weight mutations are halted."
            )
            return UpdateEvaluationResult(
                committed = false,
                effectiveWeights = current,
                generationId = currentGenerationId,
                rejectionReason = "Halted by Error Budget Policy (GEN-SRE-4): Error budget exhausted"
            )
        }

        val delta = learningRate * signalValue

        var newRelevance = current.wRelevance
        var newRecency = current.wRecency
        var newImportance = current.wImportance

        when (boostTarget) {
            WeightComponent.RELEVANCE -> newRelevance = (newRelevance + delta).coerceIn(0.05f, 0.90f)
            WeightComponent.RECENCY -> newRecency = (newRecency + delta).coerceIn(0.05f, 0.90f)
            WeightComponent.IMPORTANCE -> newImportance = (newImportance + delta).coerceIn(0.05f, 0.90f)
        }

        // Normalize candidate weights so sum = 1.0f
        val sum = newRelevance + newRecency + newImportance
        val normRelevance = (newRelevance / sum).coerceIn(0.05f, 0.90f)
        val normRecency = (newRecency / sum).coerceIn(0.05f, 0.90f)
        val normImportance = (1.0f - normRelevance - normRecency).coerceIn(0.05f, 0.90f)

        val candidateWeights = current.copy(
            wRelevance = normRelevance,
            wRecency = normRecency,
            wImportance = normImportance
        )

        // 1. Mandatory Shadow Evaluation via GEN-CM-3 PreCommitValidator
        val isApproved = validator.evaluateShadowCandidate(
            baselineWeights = current,
            candidateWeights = candidateWeights,
            shadowInteractionsCount = shadowInteractionsCount
        )

        if (!isApproved) {
            AgentLogger.w(
                "AdaptiveWeightUpdater",
                "Shadow Evaluation FAILED: Candidate weights rejected by Pre-Commit Validator ($reason). Rollback to baseline generation #$currentGenerationId"
            )
            return UpdateEvaluationResult(
                committed = false,
                effectiveWeights = current,
                generationId = currentGenerationId,
                rejectionReason = "Candidate failed shadow evaluation / pre-commit validation regression check"
            )
        }

        // 2. Commit candidate weights only after successful shadow evaluation
        currentGenerationId++
        val snapshot = WeightSnapshot(
            generationId = currentGenerationId,
            weights = candidateWeights,
            triggerSignal = reason,
            timestamp = System.currentTimeMillis()
        )
        weightSnapshots.add(snapshot)

        // Apply to live scorer
        scorer.updateWeights(candidateWeights)
        AgentLogger.i(
            "AdaptiveWeightUpdater",
            "Shadow Evaluation PASSED: Committed weights to generation #$currentGenerationId ($candidateWeights)"
        )

        return UpdateEvaluationResult(
            committed = true,
            effectiveWeights = candidateWeights,
            generationId = currentGenerationId,
            rejectionReason = null
        )
    }

    /**
     * Backward-compatible delegation to proposeAndEvaluateUpdate.
     */
    fun updateWeights(
        signalValue: Float,
        boostTarget: WeightComponent,
        reason: String
    ): UnifiedScoringWeights {
        return proposeAndEvaluateUpdate(signalValue, boostTarget, reason).effectiveWeights
    }

    /**
     * Rolls back weights to the previous snapshot.
     */
    @Synchronized
    fun rollbackToPreviousGeneration(): UnifiedScoringWeights? {
        if (weightSnapshots.size <= 1) return null
        weightSnapshots.removeAt(weightSnapshots.size - 1)
        val previousSnapshot = weightSnapshots.last()
        currentGenerationId = previousSnapshot.generationId
        scorer.updateWeights(previousSnapshot.weights)
        AgentLogger.w("AdaptiveWeightUpdater", "Rolled back to generation #$currentGenerationId: ${previousSnapshot.weights}")
        return previousSnapshot.weights
    }

    fun getCurrentGenerationId(): Long = currentGenerationId

    fun getSnapshots(): List<WeightSnapshot> = weightSnapshots.toList()
}

enum class WeightComponent {
    RELEVANCE,
    RECENCY,
    IMPORTANCE
}
