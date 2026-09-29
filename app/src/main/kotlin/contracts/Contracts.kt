package contracts

import kotlinx.serialization.Serializable
import safety.SkillContract
import safety.ToolRegistryRow

/**
 * ============================================================================
 * CONTRACT FREEZE v1.0 CHECKLIST IMPLEMENTATION
 * Single Source of Truth: MASTER-ROADMAP-v4-FINAL.md
 * Any modification requires explicit version-bump (v1.1 / v2.0).
 * ============================================================================
 */

// ----------------------------------------------------------------------------
// Contract #1 — InferenceProvider Interface (Phase VIII, GEN-271)
// ----------------------------------------------------------------------------

enum class ProviderType {
    LOCAL,
    EXTERNAL
}

data class ProviderCapabilities(
    val provider_type: ProviderType,
    val max_context_tokens: Int,
    val max_output_tokens: Int,
    val supports_streaming: Boolean,
    val supports_structured_output: Boolean, // outputSchema / Grammar
    val supports_seed: Boolean,               // for Test Layer determinism
    val supports_cancellation: Boolean
)

enum class StreamState {
    NOT_STARTED,
    HEADERS_RECEIVED,
    STREAMING,
    COMPLETED
}

data class GenerateParams(
    val max_tokens: Int,
    val temperature: Float,
    val top_p: Float,
    val mode: String = "default"
)

data class InferenceRequest(
    val prompt: String,
    val context: String,
    val params: GenerateParams,
    val outputSchema: String? = null,
    val onToken: ((String) -> Unit)? = null,
    val streamState: StreamState = StreamState.NOT_STARTED
)

data class TokenUsage(
    val prompt_tokens: Int,
    val completion_tokens: Int
)

data class InferenceResponse(
    val text: String,
    val usage: TokenUsage,
    val finish_reason: String // "stop" | "max_tokens" | "error"
)

sealed class InferenceError {
    data class ResourceExhausted(val detail: String) : InferenceError()    // OOM / Thermal
    data class QuotaExceeded(val retryAfterMs: Long) : InferenceError()     // Daily quota exhausted
    data class RateLimited(val retryAfterMs: Long) : InferenceError()       // 429
    data class Timeout(val durationMs: Long) : InferenceError()
    data class Malformed(val message: String) : InferenceError()            // Malformed JSON
    data class AuthFailed(val detail: String) : InferenceError()            // 401 / 403
    data class Unavailable(val detail: String) : InferenceError()           // 5xx or repeated timeout
}

interface InferenceProvider {
    fun capabilities(): ProviderCapabilities
    fun generate(request: InferenceRequest): Result<InferenceResponse>
}

// ----------------------------------------------------------------------------
// Contract #3 — Structured Cognitive Call JSON Schema (Phase VII, GEN-NEW-A)
// ----------------------------------------------------------------------------

@Serializable
data class StructuredCognitiveCall(
    val intent: String,
    val goal: String,
    val plan: List<String>,
    val needs_tool: Boolean,
    val tool_name: String? = null,
    val tool_params: Map<String, String> = emptyMap(),
    val confidence: Float // PROVISIONAL - pending GEN-PERF-1: threshold 0.6
)

// ----------------------------------------------------------------------------
// Contract #4 — Experience Record v1.1 (Phase X, GEN-361 + GEN-MA-11b)
// ----------------------------------------------------------------------------

@Serializable
data class ExecutionDetails(
    val strategy: String,
    val cost_ms: Long,
    val failure_reason: String? = null
)

@Serializable
data class ExperienceRecord(
    val interaction_id: String,
    val timestamp: Long,
    val input_summary: String,
    val output_summary: String,
    val signal_type: String,
    val signal_value: Float,
    val signal_source: String,
    val execution: ExecutionDetails // Added in v1.1
)

// ----------------------------------------------------------------------------
// Contract #6 — Procedure Record (Phase V, GEN-CI-1)
// ----------------------------------------------------------------------------

@Serializable
data class ProcedureRecord(
    val trigger_condition: String,
    val steps: List<String>,
    val known_pitfalls: List<String>,
    val success_rate: Float,
    val version: Int,
    val deprecated: Boolean = false
)
