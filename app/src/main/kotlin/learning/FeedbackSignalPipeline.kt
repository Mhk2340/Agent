package learning

import contracts.ExecutionDetails
import contracts.ExperienceRecord
import kotlin.math.min

/**
 * GEN-361 — Feedback Signal Pipeline
 *
 * MANDATORY ARCHITECTURAL REQUIREMENTS:
 * 1. Standard Experience Record production (Contract #4 v1.1).
 * 2. Edit-Distance Signal Filtering:
 *    edit_ratio = EditDistance(original, edited) / length(original)
 *    If edit_ratio < 0.15 (PROVISIONAL - pending GEN-PERF-1):
 *        signal_value = neutral (0.0f) — likely minor stylistic correction
 *    Else:
 *        signal_value = negative (-1.0f)
 * 3. Explicit UI Observation Level Guard:
 *    Since no real UI text editor is built into the current headless sandbox,
 *    edit signals without an explicit observed editor field default to NEUTRAL (0.0f).
 *    Learning relies on UI-independent signals:
 *    - Request repetition / reformulation (Strong Negative: -1.0f)
 *    - Explicit rating / thumbs (Positive: 1.0f, Negative: -1.0f)
 *    - Unaltered usage (Implicit Positive: 1.0f)
 */
enum class SignalType {
    IMPLICIT,
    EXPLICIT
}

enum class SignalSource {
    USER_ACCEPTED,
    USER_EDITED,
    USER_REPEATED_REQUEST,
    EXPLICIT_RATING
}

class FeedbackSignalPipeline(
    // PROVISIONAL - pending GEN-PERF-1: edit ratio threshold 0.15
    val editRatioThreshold: Float = 0.15f
) {
    data class ExtractedSignal(
        val signalType: SignalType,
        val signalValue: Float, // +1.0f positive, 0.0f neutral, -1.0f negative
        val signalSource: SignalSource,
        val rationale: String
    )

    /**
     * Calculates the Levenshtein distance between two character sequences.
     */
    fun calculateLevenshteinDistance(s1: CharSequence, s2: CharSequence): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    /**
     * Evaluates edit distance ratio:
     * edit_ratio = EditDistance(original, edited) / length(original)
     */
    fun evaluateEditDistance(original: String, edited: String): Float {
        if (original.isEmpty()) return if (edited.isEmpty()) 0.0f else 1.0f
        val distance = calculateLevenshteinDistance(original, edited)
        return distance.toFloat() / original.length.toFloat()
    }

    /**
     * Extracts quality feedback signal from user action following an interaction.
     *
     * In accordance with Roadmap constraints:
     * If [hasObservedEditorField] is false (e.g. headless environment with no editable UI),
     * edit signals default to NEUTRAL rather than guessing UI state.
     */
    fun extractSignal(
        originalResponse: String,
        editedResponse: String? = null,
        hasObservedEditorField: Boolean = false,
        isRequestRepeated: Boolean = false,
        explicitRating: Float? = null // e.g. 1.0 for thumbs up, 0.0 for thumbs down
    ): ExtractedSignal {
        // 1. Explicit Rating takes highest priority if present
        if (explicitRating != null) {
            val signalVal = when {
                explicitRating >= 0.7f -> 1.0f
                explicitRating <= 0.3f -> -1.0f
                else -> 0.0f
            }
            return ExtractedSignal(
                signalType = SignalType.EXPLICIT,
                signalValue = signalVal,
                signalSource = SignalSource.EXPLICIT_RATING,
                rationale = "Explicit user rating provided: $explicitRating"
            )
        }

        // 2. Strong Negative: Request repeated / reformulated
        if (isRequestRepeated) {
            return ExtractedSignal(
                signalType = SignalType.IMPLICIT,
                signalValue = -1.0f,
                signalSource = SignalSource.USER_REPEATED_REQUEST,
                rationale = "User repeated or reformulated request due to dissatisfaction"
            )
        }

        // 3. Edit Signal Handling
        if (editedResponse != null) {
            if (!hasObservedEditorField) {
                // Warning from roadmap: Headless / undefined UI edit observation level
                return ExtractedSignal(
                    signalType = SignalType.IMPLICIT,
                    signalValue = 0.0f,
                    signalSource = SignalSource.USER_EDITED,
                    rationale = "Observed edit without dedicated UI editor field: defaulting to neutral per roadmap"
                )
            }

            val ratio = evaluateEditDistance(originalResponse, editedResponse)
            return if (ratio < editRatioThreshold) {
                ExtractedSignal(
                    signalType = SignalType.IMPLICIT,
                    signalValue = 0.0f,
                    signalSource = SignalSource.USER_EDITED,
                    rationale = "Minor stylistic edit below threshold: ratio=$ratio < threshold=$editRatioThreshold"
                )
            } else {
                ExtractedSignal(
                    signalType = SignalType.IMPLICIT,
                    signalValue = -1.0f,
                    signalSource = SignalSource.USER_EDITED,
                    rationale = "Substantial edit: ratio=$ratio >= threshold=$editRatioThreshold"
                )
            }
        }

        // 4. Default: User accepted / unaltered usage
        return ExtractedSignal(
            signalType = SignalType.IMPLICIT,
            signalValue = 1.0f,
            signalSource = SignalSource.USER_ACCEPTED,
            rationale = "User accepted response without modification"
        )
    }

    /**
     * Converts interaction data into a Contract #4 ExperienceRecord v1.1.
     */
    fun createExperienceRecord(
        interactionId: String,
        userQuery: String,
        agentResponse: String,
        signal: ExtractedSignal,
        executionStrategy: String = "standard_pipeline",
        costMs: Long = 100L,
        failureReason: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): ExperienceRecord {
        return ExperienceRecord(
            interaction_id = interactionId,
            timestamp = timestamp,
            input_summary = userQuery,
            output_summary = agentResponse,
            signal_type = signal.signalType.name.lowercase(),
            signal_value = signal.signalValue,
            signal_source = signal.signalSource.name.lowercase(),
            execution = ExecutionDetails(
                strategy = executionStrategy,
                cost_ms = costMs,
                failure_reason = failureReason
            )
        )
    }
}
