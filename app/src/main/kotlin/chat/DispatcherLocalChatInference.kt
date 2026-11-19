package chat

import inference.DedicatedCpuInferenceDispatcher
import inference.NativeChatMessage

internal class DispatcherLocalChatInference(
    private val dispatcher: DedicatedCpuInferenceDispatcher
) : LocalChatInference {
    override fun countFormattedPromptTokens(
        systemPrompt: String,
        messages: List<LocalChatPromptMessage>,
        isStillAllowed: () -> Boolean
    ): Result<Int> = dispatcher.countConversationTokens(
        systemPrompt = systemPrompt,
        messages = messages.toNativeMessages(),
        isStillAllowed = isStillAllowed
    )

    override fun generate(
        systemPrompt: String,
        messages: List<LocalChatPromptMessage>,
        options: LocalChatGenerationOptions,
        isStillAllowed: () -> Boolean,
        onPromptTokenCount: (Int) -> Unit,
        onTextSnapshot: (String) -> Unit
    ): Result<LocalChatGenerationResult> = dispatcher.generateConversation(
        systemPrompt = systemPrompt,
        messages = messages.toNativeMessages(),
        maxTokens = options.maxOutputTokens,
        temperature = options.temperature,
        topP = options.topP,
        isStillAllowed = isStillAllowed,
        onPromptTokenCount = onPromptTokenCount,
        onTextSnapshot = onTextSnapshot
    ).map { measured ->
        val usage = measured.response.usage
        val measurements = measured.measurements
        LocalChatGenerationResult(
            text = measured.response.text,
            promptTokens = usage.prompt_tokens,
            outputTokens = usage.completion_tokens,
            finishReason = measured.response.finish_reason,
            timeToFirstTokenMs = measurements.firstTokenTimeMs,
            prefillTokensPerSecond = measurements.prefillTokensPerSecond,
            decodeTokensPerSecond = measurements.decodeTokensPerSecond
        )
    }

    override fun requestAbortGeneration() {
        dispatcher.requestAbortGeneration()
    }

    private fun List<LocalChatPromptMessage>.toNativeMessages(): List<NativeChatMessage> = map { message ->
        NativeChatMessage(
            role = when (message.role) {
                LocalChatRole.USER -> "user"
                LocalChatRole.ASSISTANT -> "assistant"
            },
            content = message.content
        )
    }
}
