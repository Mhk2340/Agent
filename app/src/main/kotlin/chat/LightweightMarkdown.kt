package chat

internal sealed class LocalMarkdownBlock {
    data class Paragraph(val text: String) : LocalMarkdownBlock()
    data class ListItem(val text: String, val ordered: Boolean, val marker: String? = null) : LocalMarkdownBlock()
    data class Code(val text: String, val language: String?) : LocalMarkdownBlock()
}

/** Small dependency-free Markdown subset for chat transcripts. */
internal object LightweightMarkdown {
    fun parse(source: String): List<LocalMarkdownBlock> {
        if (source.isEmpty()) return emptyList()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<LocalMarkdownBlock>()
        val paragraph = mutableListOf<String>()
        var index = 0

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += LocalMarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
                paragraph.clear()
            }
        }

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()
            if (trimmed.startsWith(FENCE)) {
                flushParagraph()
                val language = trimmed.removePrefix(FENCE).trim().takeIf(String::isNotEmpty)
                index++
                val codeLines = mutableListOf<String>()
                while (index < lines.size && !lines[index].trimStart().startsWith(FENCE)) {
                    codeLines += lines[index]
                    index++
                }
                if (index < lines.size) index++
                blocks += LocalMarkdownBlock.Code(codeLines.joinToString("\n"), language)
                continue
            }

            val listMatch = LIST_ITEM.matchEntire(trimmed)
            if (listMatch != null) {
                flushParagraph()
                blocks += LocalMarkdownBlock.ListItem(
                    text = listMatch.groupValues[3],
                    ordered = listMatch.groupValues[2].isNotEmpty(),
                    marker = listMatch.groupValues[2].takeIf(String::isNotEmpty)
                )
                index++
                continue
            }

            if (line.isBlank()) {
                flushParagraph()
            } else {
                paragraph += line
            }
            index++
        }
        flushParagraph()
        return blocks
    }

    private val LIST_ITEM = Regex("^(?:([-+*])\\s+|(\\d+[.)])\\s+)(.*)$")
    private const val FENCE = "```"
}
