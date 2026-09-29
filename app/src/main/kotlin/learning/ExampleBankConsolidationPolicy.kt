package learning

import contracts.ExperienceRecord
import contracts.ProcedureRecord
import memory.LongTermMemoryEntry
import memory.MemoryCallerRole
import memory.MemoryManager
import memory.MemoryType
import runtime.AgentLogger
import runtime.Identity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GEN-362 — Example Bank Consolidation Policy
 *
 * MANDATORY ARCHITECTURAL REQUIREMENTS:
 * 1. Clear criteria for consolidation into Long-Term Memory:
 *    - Positive signal (+1.0f) AND pattern frequency (>= minPatternOccurrences, default 3)
 *      -> Promotes to PROCEDURAL Memory (Contract #6 ProcedureRecord) as a proven recipe.
 *    - Strong negative signal (-1.0f) with failure reason
 *      -> Promotes to FAILURE Memory with retryAfter guidance.
 *    - Weak / neutral signals (0.0f) remain strictly in the raw experience store without promotion.
 * 2. Connects to GEN-160 / MemoryManager Write API without duplicating write logic.
 * 3. Incorporates origin metadata ("real" | "self_practice").
 */
class ExampleBankConsolidationPolicy(
    private val memoryManager: MemoryManager,
    val minPatternOccurrences: Int = 3
) {
    // Raw Experience Store before consolidation
    private val rawExperienceStore = CopyOnWriteArrayList<ExperienceRecord>()
    // Pattern tracker: key -> count of successful observations
    private val patternCounts = ConcurrentHashMap<String, Int>()

    data class ConsolidationDecision(
        val promoted: Boolean,
        val targetMemoryType: MemoryType?,
        val consolidatedId: String?,
        val reason: String
    )

    fun recordExperience(experience: ExperienceRecord) {
        rawExperienceStore.add(experience)
    }

    /**
     * Evaluates a single experience record for promotion criteria into long-term memory.
     */
    fun evaluateAndConsolidate(
        experience: ExperienceRecord,
        origin: String = "real"
    ): ConsolidationDecision {
        // Condition A: Strong Negative -> Promote to FAILURE memory
        if (experience.signal_value <= -0.8f) {
            val failureSummary = experience.execution.failure_reason 
                ?: "Execution resulted in negative signal (${experience.signal_source})"

            val entry = memoryManager.saveLongTermMemory(
                memoryType = MemoryType.FAILURE,
                content = "[FAILURE_PATTERN] Task: ${experience.input_summary} | Reason: $failureSummary",
                embedding = null,
                importance = 0.85f,
                origin = origin,
                retryAfter = 60_000L,
                schemaVersion = 1,
                callerRole = MemoryCallerRole.SYSTEM_CORE
            )

            AgentLogger.i("ConsolidationPolicy", "Promoted negative experience ${experience.interaction_id} to FAILURE memory")
            return ConsolidationDecision(
                promoted = true,
                targetMemoryType = MemoryType.FAILURE,
                consolidatedId = entry.id,
                reason = "Promoted strong negative signal to Failure memory"
            )
        }

        // Condition B: Positive signal -> Track pattern frequency for Procedural promotion
        if (experience.signal_value >= 0.8f) {
            val patternKey = experience.input_summary.trim().lowercase()
            val count = patternCounts.compute(patternKey) { _, current -> (current ?: 0) + 1 } ?: 1

            if (count >= minPatternOccurrences) {
                // Criteria met: Promote to PROCEDURAL memory
                val procedureContent = "[PROCEDURAL_GUIDE] Pattern: '$patternKey' -> Strategy: ${experience.execution.strategy} | Output: ${experience.output_summary}"
                val entry = memoryManager.saveLongTermMemory(
                    memoryType = MemoryType.PROCEDURAL,
                    content = procedureContent,
                    embedding = null,
                    importance = 0.9f,
                    origin = origin,
                    schemaVersion = 1,
                    callerRole = MemoryCallerRole.SYSTEM_CORE
                )

                AgentLogger.i("ConsolidationPolicy", "Promoted repeated pattern '$patternKey' (count=$count) to PROCEDURAL memory")
                return ConsolidationDecision(
                    promoted = true,
                    targetMemoryType = MemoryType.PROCEDURAL,
                    consolidatedId = entry.id,
                    reason = "Pattern repeated $count times with positive signal -> Promoted to Procedural memory"
                )
            } else {
                return ConsolidationDecision(
                    promoted = false,
                    targetMemoryType = null,
                    consolidatedId = null,
                    reason = "Positive experience recorded, awaiting pattern threshold ($count/$minPatternOccurrences)"
                )
            }
        }

        // Condition C: Neutral or weak signal -> Keep in raw store only
        return ConsolidationDecision(
            promoted = false,
            targetMemoryType = null,
            consolidatedId = null,
            reason = "Weak or neutral signal (${experience.signal_value}) -> Retained in raw store only without promotion"
        )
    }

    /**
     * Experience Replay: Scans raw store periodically to consolidate emerging patterns.
     */
    fun runExperienceReplay(origin: String = "real"): List<ConsolidationDecision> {
        val decisions = mutableListOf<ConsolidationDecision>()
        for (exp in rawExperienceStore) {
            val decision = evaluateAndConsolidate(exp, origin)
            if (decision.promoted) {
                decisions.add(decision)
            }
        }
        return decisions
    }

    fun getRawExperienceCount(): Int = rawExperienceStore.size
}
