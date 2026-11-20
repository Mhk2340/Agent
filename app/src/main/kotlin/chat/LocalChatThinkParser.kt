package chat

/** The assistant's user-facing answer and any Qwen3 <think> content kept out of that answer. */
internal data class LocalChatThinkParts(
    val answer: String,
    val thinking: String
)

/**
 * Splits Qwen3 reasoning markers from the answer. This is intentionally run on every cumulative
 * stream snapshot so an opening tag (or a partial tag at the end of a snapshot) is never shown.
 * An unclosed think block is treated as thinking-only rather than leaked into the answer.
 */
internal object LocalChatThinkParser {
    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"

    fun split(source: String): LocalChatThinkParts {
        if (source.isEmpty()) return LocalChatThinkParts(answer = "", thinking = "")

        val answer = StringBuilder()
        val thinking = StringBuilder()
        var cursor = 0
        var inThinking = false

        while (cursor < source.length) {
            if (inThinking) {
                val close = findMarker(source, THINK_CLOSE, cursor)
                if (close < 0) {
                    val safeEnd = source.length - trailingPartialMarkerLength(source, cursor, THINK_CLOSE)
                    thinking.append(source, cursor, safeEnd)
                    break
                }
                thinking.append(source, cursor, close)
                cursor = close + THINK_CLOSE.length
                inThinking = false
            } else {
                val open = findMarker(source, THINK_OPEN, cursor)
                val strayClose = findMarker(source, THINK_CLOSE, cursor)
                if (strayClose >= 0 && (open < 0 || strayClose < open)) {
                    answer.append(source, cursor, strayClose)
                    cursor = strayClose + THINK_CLOSE.length
                    continue
                }
                if (open < 0) {
                    val partialOpen = trailingPartialMarkerLength(source, cursor, THINK_OPEN)
                    val partialClose = trailingPartialMarkerLength(source, cursor, THINK_CLOSE)
                    val safeEnd = source.length - maxOf(partialOpen, partialClose)
                    answer.append(source, cursor, safeEnd)
                    break
                }
                answer.append(source, cursor, open)
                cursor = open + THINK_OPEN.length
                inThinking = true
            }
        }

        return LocalChatThinkParts(
            answer = answer.toString().trimStart(),
            thinking = thinking.toString().trim()
        )
    }

    private fun findMarker(source: String, marker: String, start: Int): Int {
        val lastStart = source.length - marker.length
        if (start > lastStart) return -1
        for (index in start..lastStart) {
            if (source.regionMatches(index, marker, 0, marker.length, ignoreCase = true)) return index
        }
        return -1
    }

    /** Returns the longest suffix which could be the beginning of this marker. */
    private fun trailingPartialMarkerLength(source: String, start: Int, marker: String): Int {
        val maximum = minOf(marker.length - 1, source.length - start)
        for (length in maximum downTo 1) {
            if (source.regionMatches(source.length - length, marker, 0, length, ignoreCase = true)) {
                return length
            }
        }
        return 0
    }
}
