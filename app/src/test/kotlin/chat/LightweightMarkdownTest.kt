package chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightweightMarkdownTest {
    @Test
    fun splitsParagraphsAndRetainsInlineMarkdownForRenderer() {
        val blocks = LightweightMarkdown.parse("A **bold** point.\ncontinued.\n\nSecond paragraph.")

        assertEquals(
            listOf("A **bold** point.\ncontinued.", "Second paragraph."),
            blocks.filterIsInstance<LocalMarkdownBlock.Paragraph>().map(LocalMarkdownBlock.Paragraph::text)
        )
    }

    @Test
    fun recognizesOrderedAndUnorderedListMarkers() {
        val blocks = LightweightMarkdown.parse("- one\n+ two\n3) three")
        val items = blocks.filterIsInstance<LocalMarkdownBlock.ListItem>()

        assertEquals(3, items.size)
        assertEquals(listOf(false, false, true), items.map(LocalMarkdownBlock.ListItem::ordered))
        assertEquals(listOf(null, null, "3)"), items.map(LocalMarkdownBlock.ListItem::marker))
        assertEquals(listOf("one", "two", "three"), items.map(LocalMarkdownBlock.ListItem::text))
    }

    @Test
    fun recognizesFencedCodeWithOptionalLanguageAndUnclosedFence() {
        val blocks = LightweightMarkdown.parse("Before\n\n```kotlin\nval answer = 42\n```")
        val code = blocks.filterIsInstance<LocalMarkdownBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val answer = 42", code.text)

        val unclosed = LightweightMarkdown.parse("```\nprint('local')")
            .filterIsInstance<LocalMarkdownBlock.Code>().single()
        assertEquals(null, unclosed.language)
        assertEquals("print('local')", unclosed.text)
    }

    @Test
    fun emptyContentProducesNoBlocksAndArabicTextIsPreserved() {
        assertTrue(LightweightMarkdown.parse("").isEmpty())
        val arabic = LightweightMarkdown.parse("پاسخ محلی")
            .filterIsInstance<LocalMarkdownBlock.Paragraph>().single()
        assertEquals("پاسخ محلی", arabic.text)
    }
}
