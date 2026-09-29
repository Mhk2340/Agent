package shared.change_management

import kotlinx.serialization.Serializable

/**
 * GEN-CM-8 — Change Safety Guardrails (Strict Allowlist Rule)
 *
 * MANDATORY ARCHITECTURAL INVARIANT:
 * Uses an explicit Allowlist (deny-by-default).
 * ONLY THREE TARGETS ARE PERMITTED:
 * - PROMPT (Prompt template modifications)
 * - MEMORY (Memory procedure modifications)
 * - WEIGHT (Ranking weight modifications)
 *
 * ALL OTHER TARGETS ARE STRICTLY DENIED, including:
 * CODE, SKILL_DEFINITION, TOOL_REGISTRY, RISK_REGISTRY, RATE_LIMIT_CONFIG,
 * PROVIDER_CREDENTIALS, BUILD_CONFIG, SAFETY_POLICY.
 *
 * Independent Safety Guard (GEN-NEW-GUARD), Skill Risk Registry (GEN-NEW-RISK),
 * and Rate Limiter (GEN-NEW-RATE) are strictly immutable and out of reach of Self-Evolution.
 */
enum class AllowedChangeTarget {
    PROMPT,
    MEMORY,
    WEIGHT;

    companion object {
        fun isAllowed(target: String): Boolean {
            return entries.any { it.name.equals(target.trim(), ignoreCase = true) }
        }
    }
}

/**
 * GEN-CM-1 — Golden Test Case
 * Supports both prompt cognitive cases and retrieval evaluation cases (required by GEN-CM-3).
 */
@Serializable
data class GoldenTestCase(
    val id: String,
    val prompt: String,
    val expectedIntent: String,
    val requiredConfidenceMin: Float = 0.6f, // PROVISIONAL - pending GEN-PERF-1
    val isFrozenHoldout: Boolean = true,
    val expectedRelevantKeywords: List<String> = emptyList(), // For Retrieval Golden Evaluation
    val category: String = "general_reasoning" // "general_reasoning" | "retrieval_ranking"
)

@Serializable
data class ProposedChange(
    val changeId: String,
    val targetType: String, // Checked against AllowedChangeTarget
    val targetKey: String,
    val previousValue: String,
    val proposedValue: String,
    val provenanceSignalId: String,
    val timestamp: Long
)

/**
 * GEN-CM-6 — Change Provenance Log Record
 * Append-only record tracking the origin signal and source that triggered a change.
 * Follows unified database architecture & append-only schema constraints:
 * schemaVersion per row, immutable, additive-only columns.
 */
@Serializable
data class ChangeProvenanceRecord(
    val provenanceId: String,
    val changeId: String,
    val triggerSignalId: String,
    val sourceExperienceId: String?,
    val rationale: String,
    val proposedTarget: String,
    val timestamp: Long,
    val schemaVersion: Int = 1 // Per-row schema version
)

/**
 * GEN-CM-7 — Change Audit Record
 * Follows unified database architecture & append-only schema constraints.
 */
@Serializable
data class ChangeAuditRecord(
    val changeId: String,
    val targetType: String,
    val targetKey: String,
    val status: String, // "COMMITTED" | "ROLLED_BACK" | "REJECTED_BY_GUARD"
    val reason: String,
    val timestamp: Long,
    val schemaVersion: Int = 1 // Per-row schema version
)

/**
 * GEN-CM-9 — Confidence Scoring
 */
data class EvaluationConfidence(
    val score: Float, // 0.0f .. 1.0f
    val passed: Boolean,
    val marginFromThreshold: Float,
    val details: String
)

/**
 * GEN-CM-11 — Change Governance Policy Thresholds
 *
 * PROVISIONAL - pending GEN-PERF-1:
 * - minGoldenSetPassRate = 1.0f (Strict invariant: Zero-regression on frozen holdout cases)
 * - maxPermittedQualityDrop = 0.0f (PROVISIONAL - pending empirical drift measurements)
 * - maxConsecutiveRollbacksBeforeLockout = 3 (PROVISIONAL - anti-thrashing safety ceiling)
 */
data class ChangeGovernancePolicy(
    val minGoldenSetPassRate: Float = 1.0f, // Deterministic holdout invariant: 100% pass required
    val maxPermittedQualityDrop: Float = 0.0f, // PROVISIONAL - pending GEN-PERF-1: Zero drop tolerance
    val maxConsecutiveRollbacksBeforeLockout: Int = 3 // PROVISIONAL - pending GEN-PERF-1: 3 consecutive rollbacks trigger lockout
)

/**
 * GEN-CM-12 — Evaluation Policy
 *
 * PROVISIONAL - pending GEN-PERF-1:
 * Confidence floors per target (WEIGHT=0.70, MEMORY=0.80, PROMPT=0.90) reflect the ascending
 * blast-radius risk hierarchy of allowed mutations until real hardware benchmarks calibrate them.
 */
data class EvaluationPolicy(
    val targetType: String,
    val requiresIsolatedSandbox: Boolean,
    val requiresShadowEvaluation: Boolean,
    val minimumShadowInteractions: Int, // PROVISIONAL - pending GEN-PERF-1
    val requiredConfidenceFloor: Float // PROVISIONAL - pending GEN-PERF-1
)
