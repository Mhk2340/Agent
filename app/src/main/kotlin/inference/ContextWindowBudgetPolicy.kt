package inference

/**
 * GEN-285 — Context Window Budget Policy
 * Single Local Model (Qwen3-4B) fixed context budget policy.
 *
 * PROVISIONAL - pending GEN-PERF-1:
 * - Default context window ceiling = 4096 tokens
 * - Safety margin tokens = 64 tokens (PROVISIONAL - pending GEN-PERF-1 to measure tokenizer drift / framing overhead)
 * - KV Cache at 4K context = ~590MB
 * - KV Cache calculation coefficient = ~144KB / token
 */
class ContextWindowBudgetPolicy(
    // PROVISIONAL - pending GEN-PERF-1
    val maxContextTokens: Int = 4096,
    // PROVISIONAL - pending GEN-PERF-1: safety_margin value is provisional until tokenizer drift profiling
    val safetyMarginTokens: Int = 64
) {
    /**
     * Usable context calculation as specified in Roadmap:
     * usable_context = max_context_tokens - requested_max_output_tokens - safety_margin
     */
    fun calculateUsableContextTokens(requestedMaxOutputTokens: Int): Int {
        val usable = maxContextTokens - requestedMaxOutputTokens - safetyMarginTokens
        return usable.coerceAtLeast(0)
    }

    /**
     * Verifies admission of context string / tokens before calling LLM.
     */
    fun checkAdmission(contextLengthTokens: Int, requestedMaxOutputTokens: Int): Boolean {
        val usable = calculateUsableContextTokens(requestedMaxOutputTokens)
        return contextLengthTokens <= usable
    }

    /**
     * KV Cache memory estimate: ~144 KB per token.
     * PROVISIONAL - pending GEN-PERF-1
     */
    fun estimateKvCacheBytes(tokenCount: Int): Long {
        // 144 KB = 144 * 1024 bytes per token
        return tokenCount.toLong() * 144L * 1024L
    }
}
