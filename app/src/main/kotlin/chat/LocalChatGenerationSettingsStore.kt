package chat

import android.content.Context
import android.content.SharedPreferences

/** Persists local-chat sampling controls; the provider contains no prompts, responses, or secrets. */
internal class LocalChatGenerationSettingsStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): LocalChatGenerationOptions {
        val defaults = LocalChatGenerationOptions()
        return runCatching {
            LocalChatGenerationOptions(
                thinkingTokenBudget = defaults.thinkingTokenBudget,
                finalAnswerTokenBudget = defaults.finalAnswerTokenBudget,
                temperature = preferences.getFloat(KEY_TEMPERATURE, defaults.temperature),
                topP = preferences.getFloat(KEY_TOP_P, defaults.topP),
                topK = preferences.getInt(KEY_TOP_K, defaults.topK),
                minP = preferences.getFloat(KEY_MIN_P, defaults.minP),
                repeatPenalty = preferences.getFloat(KEY_REPEAT_PENALTY, defaults.repeatPenalty),
                presencePenalty = preferences.getFloat(KEY_PRESENCE_PENALTY, defaults.presencePenalty),
                seed = preferences.getInt(KEY_SEED, defaults.seed)
            )
        }.getOrElse { defaults }
    }

    fun save(options: LocalChatGenerationOptions): Boolean = preferences.edit()
        .putFloat(KEY_TEMPERATURE, options.temperature)
        .putFloat(KEY_TOP_P, options.topP)
        .putInt(KEY_TOP_K, options.topK)
        .putFloat(KEY_MIN_P, options.minP)
        .putFloat(KEY_REPEAT_PENALTY, options.repeatPenalty)
        .putFloat(KEY_PRESENCE_PENALTY, options.presencePenalty)
        .putInt(KEY_SEED, options.seed)
        .commit()

    private companion object {
        const val PREFERENCES_NAME = "local_chat_generation_settings"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "top_p"
        const val KEY_TOP_K = "top_k"
        const val KEY_MIN_P = "min_p"
        const val KEY_REPEAT_PENALTY = "repeat_penalty"
        const val KEY_PRESENCE_PENALTY = "presence_penalty"
        const val KEY_SEED = "seed"
    }
}
