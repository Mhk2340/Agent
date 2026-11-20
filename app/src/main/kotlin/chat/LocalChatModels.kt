package chat

import diagnostics.DiagnosticCode
import kotlinx.serialization.Serializable

@Serializable
enum class LocalChatRole {
    USER,
    ASSISTANT
}

@Serializable
enum class LocalChatMessageStatus {
    COMPLETE,
    GENERATING,
    CANCELLED,
    FAILED
}

@Serializable
enum class LocalChatFinishReason {
    EOS,
    OUTPUT_LIMIT,
    CANCELLED,
    ERROR,
    UNKNOWN;

    companion object {
        fun fromNative(reason: String): LocalChatFinishReason = when (reason.lowercase()) {
            "stop", "eos", "end_of_turn" -> EOS
            "max_tokens", "length" -> OUTPUT_LIMIT
            "cancelled", "canceled" -> CANCELLED
            "error" -> ERROR
            else -> UNKNOWN
        }
    }
}

@Serializable
data class LocalChatMetrics(
    val promptTokens: Int,
    val outputTokens: Int,
    val timeToFirstTokenMs: Long,
    val prefillTokensPerSecond: Double?,
    val decodeTokensPerSecond: Double?,
    val thinkingTokens: Int = 0,
    val finishReason: LocalChatFinishReason = LocalChatFinishReason.UNKNOWN
)

@Serializable
data class LocalChatMessage(
    val id: String,
    val role: LocalChatRole,
    val content: String,
    val createdAtEpochMs: Long,
    val status: LocalChatMessageStatus = LocalChatMessageStatus.COMPLETE,
    val metrics: LocalChatMetrics? = null,
    val olderContextTrimmed: Boolean = false,
    val thinkingContent: String = ""
) {
    override fun toString(): String =
        "LocalChatMessage(id=$id, role=$role, content=<redacted>, thinkingContent=<redacted>, status=$status, metrics=$metrics)"
}

@Serializable
data class LocalChatConversation(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val messages: List<LocalChatMessage> = emptyList()
) {
    override fun toString(): String =
        "LocalChatConversation(id=$id, title=<redacted>, messages=${messages.size})"
}

@Serializable
data class LocalChatArchive(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val activeConversationId: String? = null,
    val conversations: List<LocalChatConversation> = emptyList()
) {
    override fun toString(): String =
        "LocalChatArchive(schemaVersion=$schemaVersion, conversations=${conversations.size}, content=<redacted>)"

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

enum class LocalChatGenerationPhase {
    IDLE,
    PREPARING,
    LOADING_MODEL,
    PREFILLING,
    GENERATING
}

enum class LocalChatError {
    APP_LOCKED,
    MODEL_NOT_INSTALLED,
    HISTORY_UNAVAILABLE,
    PROMPT_EXCEEDS_LIMIT,
    LOW_MEMORY,
    GENERATION_FAILED,
    CANCELLED
}

data class LocalChatUiState(
    val activeConversation: LocalChatConversation? = null,
    val savedConversationCount: Int = 0,
    val isHistoryLoading: Boolean = false,
    val isGenerating: Boolean = false,
    val isCancelling: Boolean = false,
    val phase: LocalChatGenerationPhase = LocalChatGenerationPhase.IDLE,
    val promptTokenCount: Int? = null,
    val error: LocalChatError? = null,
    val olderContextTrimmed: Boolean = false,
    val isHistoryEphemeral: Boolean = false,
    val historyDiagnosticCode: DiagnosticCode? = null,
    val historyExceptionClass: Class<out Throwable>? = null
) {
    override fun toString(): String =
        "LocalChatUiState(conversation=<redacted>, savedConversationCount=$savedConversationCount, " +
            "isGenerating=$isGenerating, isCancelling=$isCancelling, phase=$phase, " +
            "promptTokenCount=$promptTokenCount, error=$error)"
}

internal data class LocalChatPromptMessage(
    val role: LocalChatRole,
    val content: String
) {
    override fun toString(): String = "LocalChatPromptMessage(role=$role, content=<redacted>)"
}

internal data class LocalChatGenerationOptions(
    /**
     * Reserved accounting allocation for a think span inside [maxOutputTokens]; it is not a
     * separate hard stop (native decoding cannot force-close a think block). The hard cap is
     * always [maxOutputTokens].
     */
    val thinkingTokenBudget: Int = DEFAULT_THINKING_TOKEN_BUDGET,
    val finalAnswerTokenBudget: Int = DEFAULT_FINAL_ANSWER_TOKEN_BUDGET,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val topP: Float = DEFAULT_TOP_P,
    val topK: Int = DEFAULT_TOP_K,
    val minP: Float = DEFAULT_MIN_P,
    val repeatPenalty: Float = DEFAULT_REPEAT_PENALTY,
    val presencePenalty: Float = DEFAULT_PRESENCE_PENALTY,
    /** -1 requests llama.cpp's LLAMA_DEFAULT_SEED behavior (a random seed per generation). */
    val seed: Int = DEFAULT_SEED
) {
    /** Includes the two possible special-token delimiters around a Qwen3 think block. */
    val maxOutputTokens: Int
        get() = thinkingTokenBudget + finalAnswerTokenBudget + THINK_MARKER_TOKEN_RESERVE

    init {
        require(thinkingTokenBudget in 0..MAX_THINKING_TOKEN_BUDGET)
        require(finalAnswerTokenBudget in MIN_FINAL_ANSWER_TOKENS..MAX_FINAL_ANSWER_TOKENS)
        require(maxOutputTokens in MIN_OUTPUT_TOKENS..MAX_OUTPUT_TOKENS)
        require(temperature.isFinite() && temperature in 0f..MAX_TEMPERATURE)
        require(topP.isFinite() && topP > 0f && topP <= 1f)
        require(topK in 0..MAX_TOP_K)
        require(minP.isFinite() && minP in 0f..1f)
        require(repeatPenalty.isFinite() && repeatPenalty in MIN_REPEAT_PENALTY..MAX_REPEAT_PENALTY)
        require(presencePenalty.isFinite() && presencePenalty in 0f..MAX_PRESENCE_PENALTY)
        require(seed >= DEFAULT_SEED)
    }

    companion object {
        const val DEFAULT_THINKING_TOKEN_BUDGET = 128
        const val DEFAULT_FINAL_ANSWER_TOKEN_BUDGET = 512
        const val THINK_MARKER_TOKEN_RESERVE = 2
        const val MIN_OUTPUT_TOKENS = 1
        const val MAX_OUTPUT_TOKENS = 1024
        const val MAX_THINKING_TOKEN_BUDGET = 256
        const val MIN_FINAL_ANSWER_TOKENS = 1
        const val MAX_FINAL_ANSWER_TOKENS = 1024
        const val DEFAULT_TEMPERATURE = 0.7f
        const val MAX_TEMPERATURE = 2f
        const val DEFAULT_TOP_P = 0.8f
        const val DEFAULT_TOP_K = 20
        const val MAX_TOP_K = 1000
        const val DEFAULT_MIN_P = 0f
        const val DEFAULT_REPEAT_PENALTY = 1f
        const val MIN_REPEAT_PENALTY = 0.5f
        const val MAX_REPEAT_PENALTY = 2f
        const val DEFAULT_PRESENCE_PENALTY = 1.5f
        const val MAX_PRESENCE_PENALTY = 2f
        const val DEFAULT_SEED = -1
    }
}
