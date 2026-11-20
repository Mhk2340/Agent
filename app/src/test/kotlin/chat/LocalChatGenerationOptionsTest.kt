package chat

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertThrows

class LocalChatGenerationOptionsTest {
    @Test
    fun qwenNonThinkingDefaultsReserveSeparateThinkAndFinalAnswerBudgets() {
        val options = LocalChatGenerationOptions()

        assertEquals(128, options.thinkingTokenBudget)
        assertEquals(512, options.finalAnswerTokenBudget)
        assertEquals(642, options.maxOutputTokens)
        assertEquals(0.7f, options.temperature)
        assertEquals(0.8f, options.topP)
        assertEquals(20, options.topK)
        assertEquals(0f, options.minP)
        assertEquals(1f, options.repeatPenalty)
        assertEquals(1.5f, options.presencePenalty)
        assertEquals(-1, options.seed)
    }

    @Test
    fun userSamplingOverridesAreRetainedAndValidated() {
        val options = LocalChatGenerationOptions(
            temperature = 0.55f,
            topP = 0.92f,
            topK = 40,
            minP = 0.05f,
            repeatPenalty = 1.1f,
            presencePenalty = 0.8f,
            seed = 42
        )

        assertEquals(0.55f, options.temperature)
        assertEquals(0.92f, options.topP)
        assertEquals(40, options.topK)
        assertEquals(0.05f, options.minP)
        assertEquals(1.1f, options.repeatPenalty)
        assertEquals(0.8f, options.presencePenalty)
        assertEquals(42, options.seed)
    }

    @Test
    fun invalidSamplingAndBudgetValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalChatGenerationOptions(topP = 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalChatGenerationOptions(topK = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalChatGenerationOptions(minP = 1.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalChatGenerationOptions(seed = -2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalChatGenerationOptions(thinkingTokenBudget = 257)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalChatGenerationOptions(finalAnswerTokenBudget = 0)
        }
    }
}
