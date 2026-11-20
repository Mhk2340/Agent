package chat

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalChatThinkParserTest {
    @Test
    fun extractsThinkBlockWithoutLeavingMarkersInTheFinalAnswer() {
        val parts = LocalChatThinkParser.split("Answer before.\n<think>private reasoning</think>\nFinal answer.")

        assertEquals("Answer before.\n\nFinal answer.", parts.answer)
        assertEquals("private reasoning", parts.thinking)
    }

    @Test
    fun keepsAnUnclosedThinkBlockOutOfTheAnswer() {
        val parts = LocalChatThinkParser.split("<think>unfinished private reasoning")

        assertEquals("", parts.answer)
        assertEquals("unfinished private reasoning", parts.thinking)
    }

    @Test
    fun withholdsPartialOpeningMarkerDuringStreaming() {
        val parts = LocalChatThinkParser.split("A user-visible prefix <thi")

        assertEquals("A user-visible prefix ", parts.answer)
        assertEquals("", parts.thinking)
    }

    @Test
    fun withholdsPartialClosingMarkerInsideThinkingDuringStreaming() {
        val parts = LocalChatThinkParser.split("<think>private text</thi")

        assertEquals("", parts.answer)
        assertEquals("private text", parts.thinking)
    }

    @Test
    fun joinsMultipleThinkBlocksWithoutExposingEither() {
        val parts = LocalChatThinkParser.split("first<think>one</think>\nsecond<think>two</think>\nthird")

        assertEquals("first\nsecond\nthird", parts.answer)
        assertEquals("onetwo", parts.thinking)
    }
}
