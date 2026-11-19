package chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatPromptPolicyTest {
    @Test
    fun leavesAllTurnsWhenFormattedPromptFits() {
        val messages = turns("one", "two", "latest")
        val selected = LocalChatPromptPolicy.selectWithinBudget("system", messages) { _, retained ->
            retained.size * 30
        }

        assertTrue(selected is LocalChatPromptPolicy.Selection.Ready)
        selected as LocalChatPromptPolicy.Selection.Ready
        assertEquals(messages, selected.messages)
        assertEquals(150, selected.formattedTokenCount)
        assertEquals(0, selected.removedTurns)
    }

    @Test
    fun removesOnlyOldestCompleteExchangesUntilPromptFits() {
        val messages = turns("old one", "old two", "latest")
        val counts = mutableListOf<Int>()
        val selected = LocalChatPromptPolicy.selectWithinBudget("system", messages) { _, retained ->
            val count = retained.size * 60
            counts += count
            count
        }

        assertTrue(selected is LocalChatPromptPolicy.Selection.Ready)
        selected as LocalChatPromptPolicy.Selection.Ready
        assertEquals(listOf("old two", "answer old two", "latest"), selected.messages.map { it.content })
        assertEquals(1, selected.removedTurns)
        assertEquals(listOf(300, 180), counts)
        assertTrue(selected.formattedTokenCount <= LocalChatPromptPolicy.MAX_FORMATTED_PROMPT_TOKENS)
    }

    @Test
    fun promptStillFailsClearlyWhenLatestTurnAloneExceedsBudget() {
        val selected = LocalChatPromptPolicy.selectWithinBudget(
            "system",
            turns("earlier", "latest")
        ) { _, _ -> 300 }

        assertEquals(LocalChatPromptPolicy.Selection.LatestTurnTooLarge(300), selected)
    }

    @Test
    fun incompleteOlderTurnIsNotSilentlyDiscardedAsACompleteExchange() {
        val messages = listOf(
            prompt(LocalChatRole.USER, "unfinished"),
            prompt(LocalChatRole.USER, "latest")
        )
        val selected = LocalChatPromptPolicy.selectWithinBudget("system", messages) { _, _ -> 300 }

        assertEquals(LocalChatPromptPolicy.Selection.LatestTurnTooLarge(300), selected)
    }

    @Test
    fun systemPromptIsIncludedInTheMeasuredBudget() {
        val messages = turns("older", "latest")
        val counts = mutableListOf<String>()
        val selected = LocalChatPromptPolicy.selectWithinBudget("long system prompt", messages) { system, retained ->
            counts += system
            if (retained.size > 1) 300 else 240
        }

        assertTrue(selected is LocalChatPromptPolicy.Selection.Ready)
        selected as LocalChatPromptPolicy.Selection.Ready
        assertEquals(listOf("long system prompt", "long system prompt"), counts)
        assertEquals(listOf("latest"), selected.messages.map { it.content })
        assertEquals(1, selected.removedTurns)
    }

    @Test
    fun unavailableFormattedTokenCountFailsClosed() {
        val selected = LocalChatPromptPolicy.selectWithinBudget("system", turns("latest")) { _, _ -> null }

        assertEquals(LocalChatPromptPolicy.Selection.TokenCountUnavailable, selected)
    }

    private fun turns(vararg userMessages: String): List<LocalChatPromptMessage> = buildList {
        userMessages.forEachIndexed { index, content ->
            add(prompt(LocalChatRole.USER, content))
            if (index != userMessages.lastIndex) add(prompt(LocalChatRole.ASSISTANT, "answer $content"))
        }
    }

    private fun prompt(role: LocalChatRole, content: String) = LocalChatPromptMessage(role, content)
}
