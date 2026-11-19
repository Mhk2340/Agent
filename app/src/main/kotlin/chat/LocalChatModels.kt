package chat

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
data class LocalChatMetrics(
    val promptTokens: Int,
    val outputTokens: Int,
    val timeToFirstTokenMs: Long,
    val prefillTokensPerSecond: Double?,
    val decodeTokensPerSecond: Double?
)

@Serializable
data class LocalChatMessage(
    val id: String,
    val role: LocalChatRole,
    val content: String,
    val createdAtEpochMs: Long,
    val status: LocalChatMessageStatus = LocalChatMessageStatus.COMPLETE,
    val metrics: LocalChatMetrics? = null,
    val olderContextTrimmed: Boolean = false
) {
    override fun toString(): String =
        "LocalChatMessage(id=$id, role=$role, content=<redacted>, status=$status, metrics=$metrics)"
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
    val olderContextTrimmed: Boolean = false
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
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val topP: Float = DEFAULT_TOP_P
) {
    init {
        require(maxOutputTokens in MIN_OUTPUT_TOKENS..MAX_OUTPUT_TOKENS)
        require(temperature.isFinite() && temperature >= 0f)
        require(topP.isFinite() && topP > 0f && topP <= 1f)
    }

    companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS = 128
        const val MIN_OUTPUT_TOKENS = 1
        const val MAX_OUTPUT_TOKENS = 512
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_P = 0.9f
    }
}
