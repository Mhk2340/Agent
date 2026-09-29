package multiagent

import reliability.SloDefinitions
import java.util.concurrent.ConcurrentHashMap

/**
 * GEN-MA-1 — Content Domain Classifier
 */
enum class ContentDomain {
    VIDEO,
    AUDIO_MUSIC,
    CODE_SOFTWARE,
    TEXT_ARTICLE,
    GENERAL_CREATIVE
}

class ContentDomainClassifier {
    fun classifyDomain(userPrompt: String): ContentDomain {
        val lower = userPrompt.lowercase()
        return when {
            lower.contains("video") || lower.contains("ویدیو") || lower.contains("فیلم") -> ContentDomain.VIDEO
            lower.contains("music") || lower.contains("song") || lower.contains("آهنگ") || lower.contains("موسیقی") -> ContentDomain.AUDIO_MUSIC
            lower.contains("code") || lower.contains("program") || lower.contains("کد") || lower.contains("برنامه") -> ContentDomain.CODE_SOFTWARE
            lower.contains("article") || lower.contains("blog") || lower.contains("مقاله") || lower.contains("متن") -> ContentDomain.TEXT_ARTICLE
            else -> ContentDomain.GENERAL_CREATIVE
        }
    }
}

/**
 * GEN-MA-2 — Dynamic Role Decomposer
 * Concept -> Production -> Refinement
 */
data class AgentRole(
    val roleName: String,
    val stage: String, // "Concept", "Production", "Refinement"
    val systemPrompt: String
)

class DynamicRoleDecomposer {
    fun decomposeForDomain(domain: ContentDomain): List<AgentRole> {
        return when (domain) {
            ContentDomain.VIDEO -> listOf(
                AgentRole("Writer", "Concept", "You are the Lead Writer. Create the core outline and script."),
                AgentRole("Director", "Production", "You are the Director. Detail camera, pacing, and visual storyboards."),
                AgentRole("Editor", "Refinement", "You are the Post-Production Editor. Polish transitions, timing, and sound.")
            )
            ContentDomain.CODE_SOFTWARE -> listOf(
                AgentRole("Architect", "Concept", "You are the Software Architect. Define interfaces, schemas, and constraints."),
                AgentRole("Developer", "Production", "You are the Core Developer. Implement the logic and methods."),
                AgentRole("Tester", "Refinement", "You are the QA Engineer. Verify edge cases, safety invariants, and tests.")
            )
            else -> listOf(
                AgentRole("Planner", "Concept", "You are the Lead Planner. Establish conceptual foundation and outline."),
                AgentRole("Creator", "Production", "You are the Creator. Produce primary content body."),
                AgentRole("Reviewer", "Refinement", "You are the Reviewer. Refine, proofread, and verify quality.")
            )
        }
    }
}

/**
 * GEN-MA-2b — Multi-Hypothesis Sketching
 * Evaluates 2-3 approaches based on:
 * 1. User prompt alignment
 * 2. Available tool feasibility
 * 3. Execution simplicity / lower risk
 */
data class HypothesisSketch(
    val approachId: String,
    val title: String,
    val summary: String,
    val alignmentScore: Int, // 1..5
    val toolFeasibilityScore: Int, // 1..5
    val simplicityScore: Int, // 1..5
    val totalScore: Int = alignmentScore + toolFeasibilityScore + simplicityScore
)

class MultiHypothesisSketcher {
    fun evaluateSketches(sketches: List<HypothesisSketch>): HypothesisSketch {
        require(sketches.isNotEmpty()) { "Sketches list cannot be empty" }
        // Select highest total score. Tie-breaking rule: pick first in order of generation
        return sketches.maxByOrNull { it.totalScore } ?: sketches.first()
    }
}

/**
 * GEN-MA-4 — Role Context Switch
 * Enforces the 3-part context:
 * 1. Original User Prompt (Immutable Anchor to prevent telephone game drift)
 * 2. Previous Role Outputs from Shared Workspace
 * 3. Current Role System Prompt
 */
object RoleContextAssembler {
    fun assembleRolePrompt(
        originalUserPrompt: String,
        role: AgentRole,
        previousArtifacts: List<WorkspaceArtifact>
    ): String {
        return buildString {
            append("### ORIGINAL USER PROMPT (FIXED ANCHOR) ###\n")
            append(originalUserPrompt).append("\n\n")

            append("### SYSTEM INSTRUCTIONS FOR ROLE: ${role.roleName} (${role.stage}) ###\n")
            append(role.systemPrompt).append("\n\n")

            if (previousArtifacts.isNotEmpty()) {
                append("### WORKSPACE ARTIFACTS FROM PREVIOUS STAGES ###\n")
                for (art in previousArtifacts) {
                    append("--- Stage output from [${art.roleName}] ---\n")
                    append(art.content).append("\n\n")
                }
            }
        }
    }
}

/**
 * GEN-MA-9 — Result Assembly & Acceptance Verifier (Generator != Verifier)
 */
data class VerificationResult(
    val answers: String, // "yes" | "no" | "partial"
    val missing: List<String>
)

class ResultAssemblyVerifier {
    fun verifyAlignment(originalPrompt: String, assembledResult: String): VerificationResult {
        return if (assembledResult.length >= 10 && !assembledResult.contains("REJECTED")) {
            VerificationResult("yes", emptyList())
        } else {
            VerificationResult("partial", listOf("Content insufficient or contains error flags"))
        }
    }
}

/**
 * GEN-MA-10 & GEN-MA-11 — User Rating & Multi-Armed Bandit Tool Learning
 * Uses Wilson Lower Bound at 95% confidence for low sample counts (min samples N = 5).
 */
data class ToolUsageStats(
    val toolName: String,
    var positiveRatings: Int = 0,
    var totalUses: Int = 0
) {
    fun calculateWilsonScore(): Double {
        if (totalUses == 0) return 0.0
        return SloDefinitions.calculateWilsonLowerBound(positiveRatings, totalUses)
    }
}

class ToolApproachLearner {
    private val toolStats = ConcurrentHashMap<String, ToolUsageStats>()

    fun recordToolFeedback(toolName: String, isPositive: Boolean) {
        val stats = toolStats.getOrPut(toolName) { ToolUsageStats(toolName) }
        stats.totalUses++
        if (isPositive) {
            stats.positiveRatings++
        }
    }

    /**
     * Prioritizes tools based on Wilson Lower Bound when sample count >= 5.
     */
    fun selectBestTool(candidates: List<String>): String {
        require(candidates.isNotEmpty()) { "Candidate list cannot be empty" }
        return candidates.maxByOrNull { toolName ->
            val stats = toolStats[toolName]
            if (stats == null || stats.totalUses < 5) {
                // If fewer than 5 samples, assign neutral prior
                0.5
            } else {
                stats.calculateWilsonScore()
            }
        } ?: candidates.first()
    }

    fun getStats(toolName: String): ToolUsageStats? = toolStats[toolName]
}
