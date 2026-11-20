package chat

/** Keeps the full transcript for display/storage but bounds every replayed model prompt. */
internal object LocalChatPromptPolicy {
    const val MAX_FORMATTED_PROMPT_TOKENS = 256
    const val DEFAULT_SYSTEM_PROMPT =
        "You are ATOM, a helpful and concise assistant. Use the conversation context to answer the latest user message. Reply in the same language as the user. If the user writes in Persian, respond in fluent, natural Persian. Do not reveal internal reasoning; provide only the final answer. /no_think"

    sealed class Selection {
        data class Ready(
            val messages: List<LocalChatPromptMessage>,
            val formattedTokenCount: Int,
            val removedTurns: Int
        ) : Selection()

        data class LatestTurnTooLarge(val formattedTokenCount: Int) : Selection()
        object TokenCountUnavailable : Selection()
    }

    /** The token counter must apply the model chat template, including the system prompt. */
    fun selectWithinBudget(
        systemPrompt: String,
        messages: List<LocalChatPromptMessage>,
        countFormattedTokens: (String, List<LocalChatPromptMessage>) -> Int?
    ): Selection {
        if (messages.isEmpty() || messages.first().role != LocalChatRole.USER ||
            messages.last().role != LocalChatRole.USER
        ) {
            return Selection.LatestTurnTooLarge(formattedTokenCount = 0)
        }
        val retained = messages.toMutableList()
        var removedTurns = 0

        while (true) {
            val count = countFormattedTokens(systemPrompt, retained.toList())
                ?: return Selection.TokenCountUnavailable
            if (count <= 0) return Selection.TokenCountUnavailable
            if (count <= MAX_FORMATTED_PROMPT_TOKENS) {
                return Selection.Ready(retained.toList(), count, removedTurns)
            }

            val latestUserIndex = retained.lastIndex
            val oldestPriorUserIndex = retained.indices.firstOrNull { index ->
                index < latestUserIndex && retained[index].role == LocalChatRole.USER
            } ?: return Selection.LatestTurnTooLarge(count)
            val nextUserIndex = (oldestPriorUserIndex + 1 until retained.size).firstOrNull { index ->
                retained[index].role == LocalChatRole.USER
            } ?: return Selection.LatestTurnTooLarge(count)
            val hasAssistantReply = (oldestPriorUserIndex + 1 until nextUserIndex).any { index ->
                retained[index].role == LocalChatRole.ASSISTANT
            }
            if (!hasAssistantReply) return Selection.LatestTurnTooLarge(count)

            // Drop the oldest complete user/assistant exchange. Keep the current user message whole.
            retained.subList(oldestPriorUserIndex, nextUserIndex).clear()
            removedTurns++
        }
    }
}
