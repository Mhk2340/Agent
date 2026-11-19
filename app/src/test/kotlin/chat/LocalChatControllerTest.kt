package chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import diagnostics.DiagnosticCode
import diagnostics.DiagnosticsLog
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LocalChatControllerTest {
    @Test
    fun streamsSnapshotsAndKeepsTheCompleteTranscriptWhileBoundingModelHistory() {
        val previous = conversation(
            listOf(
                user("u1", "old question one"), assistant("a1", "old answer one"),
                user("u2", "old question two"), assistant("a2", "old answer two")
            )
        )
        val store = MemoryHistoryStore(archive(previous))
        val inference = FakeInference().apply {
            countPrompt = { _, messages -> messages.size * 100 }
            blockAfterFirstSnapshot = true
        }
        val controller = controller(store, inference)
        try {
            controller.open()
            await { !controller.state().isHistoryLoading && controller.state().activeConversation != null }
            val snapshotArrived = inference.firstSnapshot

            assertTrue(controller.send("new request"))
            assertTrue(snapshotArrived.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertTrue(controller.flushStreamingText())
            assertEquals("draft", controller.state().activeConversation?.messages?.last()?.content)
            assertEquals(LocalChatGenerationPhase.GENERATING, controller.state().phase)
            inference.releaseFirstSnapshot.countDown()

            await { !controller.state().isGenerating && controller.state().activeConversation?.messages?.last()?.content == "final answer" }
            val visibleMessages = controller.state().activeConversation!!.messages
            assertEquals(listOf("old question one", "old answer one", "old question two", "old answer two", "new request", "final answer"),
                visibleMessages.map(LocalChatMessage::content))
            assertEquals(listOf("new request"), inference.generatedMessages.get().map(LocalChatPromptMessage::content))
            assertTrue(controller.state().olderContextTrimmed)
            assertEquals(6, store.archive.conversations.single().messages.size)
        } finally {
            controller.close()
        }
    }

    @Test
    fun stopPreservesThePartialAnswerAndEndsWithAnExplicitCancelledStatus() {
        val store = MemoryHistoryStore()
        val inference = FakeInference().apply {
            blockUntilStopped = true
            countPrompt = { _, messages -> messages.size * 5 }
        }
        val controller = controller(store, inference)
        try {
            controller.open()
            await { !controller.state().isHistoryLoading && controller.state().activeConversation != null }
            assertTrue(controller.send("stop this generation"))
            assertTrue(inference.firstSnapshot.await(WAIT_SECONDS, TimeUnit.SECONDS))
            controller.flushStreamingText()
            controller.stopGeneration()
            await { !controller.state().isGenerating && controller.state().error == LocalChatError.CANCELLED }

            val answer = controller.state().activeConversation!!.messages.last()
            assertEquals("draft", answer.content)
            assertEquals(LocalChatMessageStatus.CANCELLED, answer.status)
            assertTrue(inference.abortCalls.get() > 0)
        } finally {
            controller.close()
        }
    }

    @Test
    fun overflowFailsWithoutSendingAnOverBudgetPrompt() {
        val previous = conversation(listOf(user("u1", "older"), assistant("a1", "reply")))
        val store = MemoryHistoryStore(archive(previous))
        val inference = FakeInference().apply { countPrompt = { _, _ -> 300 } }
        val controller = controller(store, inference)
        try {
            controller.open()
            await { !controller.state().isHistoryLoading }
            assertTrue(controller.send("a latest message that cannot fit"))
            await { !controller.state().isGenerating && controller.state().error == LocalChatError.PROMPT_EXCEEDS_LIMIT }

            assertEquals(0, inference.generateCalls.get())
            assertEquals(300, controller.state().promptTokenCount)
            assertEquals(2, controller.state().activeConversation!!.messages.count { it.role == LocalChatRole.USER })
        } finally {
            controller.close()
        }
    }

    @Test
    fun regenerateSelectedResponseDropsOnlyThatResponseAndLaterTurns() {
        val prior = conversation(
            listOf(
                user("u1", "first question"), assistant("a1", "first answer"),
                user("u2", "second question"), assistant("a2", "second answer")
            )
        )
        val store = MemoryHistoryStore(archive(prior))
        val inference = FakeInference().apply { countPrompt = { _, messages -> messages.size * 10 } }
        val controller = controller(store, inference)
        try {
            controller.open()
            await { !controller.state().isHistoryLoading }
            assertTrue(controller.regenerateResponse("a1"))
            await { !controller.state().isGenerating && controller.state().activeConversation?.messages?.last()?.content == "final answer" }

            assertEquals(listOf("first question", "final answer"),
                controller.state().activeConversation!!.messages.map(LocalChatMessage::content))
        } finally {
            controller.close()
        }
    }

    @Test
    fun lockClosureClearsDecryptedStateAndAbortsInFlightWork() {
        val allowed = AtomicReference(true)
        val store = MemoryHistoryStore()
        val inference = FakeInference().apply {
            blockUntilStopped = true
            countPrompt = { _, messages -> messages.size * 10 }
        }
        val controller = controller(store, inference, isAllowed = { allowed.get() })
        try {
            controller.open()
            await { !controller.state().isHistoryLoading }
            assertTrue(controller.send("private prompt"))
            assertTrue(inference.firstSnapshot.await(WAIT_SECONDS, TimeUnit.SECONDS))
            allowed.set(false)
            controller.onAccessLost()
            assertEquals(LocalChatError.APP_LOCKED, controller.state().error)
            assertEquals(null, controller.state().activeConversation)
            await { !controller.state().isGenerating }
            assertTrue(inference.abortCalls.get() > 0)
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedEncryptedWriteFallsBackToMemoryOnlyAndStillSendsSuccessfully() {
        val persistentStore = FailingWriteHistoryStore()
        val inference = FakeInference().apply { countPrompt = { _, messages -> messages.size * 10 } }
        val controller = LocalChatController(
            historyStore = persistentStore,
            inference = inference,
            isAccessAllowed = { true },
            isModelInstalled = { true },
            clock = { 100L },
            newId = { "fallback-${ID.incrementAndGet()}" }
        )
        try {
            controller.open()
            await { !controller.state().isHistoryLoading }
            assertTrue(controller.state().isHistoryEphemeral)
            assertEquals(diagnostics.DiagnosticCode.CHAT_STORE_CRYPTO_FAILURE, controller.state().historyDiagnosticCode)

            assertTrue(controller.send("سلام"))
            await {
                !controller.state().isGenerating &&
                    controller.state().activeConversation?.messages?.lastOrNull()?.content == "final answer"
            }
            assertEquals("سلام", controller.state().activeConversation!!.messages.first().content)
            assertTrue(controller.state().isHistoryEphemeral)
            assertEquals(1, persistentStore.writeCalls.get())
            assertEquals(1, inference.generateCalls.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedGenerationMarksTheUserMessageForRetryAndDoesNotKeepAnEmptyAssistantBubble() {
        val diagnosticLog = DiagnosticsLog()
        val inference = FakeInference().apply {
            countPrompt = { _, messages -> messages.size * 10 }
            generationFailure = IllegalStateException("private prompt must not be logged")
        }
        val controller = LocalChatController(
            historyStore = MemoryHistoryStore(),
            inference = inference,
            isAccessAllowed = { true },
            isModelInstalled = { true },
            diagnostics = diagnosticLog
        )
        try {
            controller.open()
            await { !controller.state().isHistoryLoading }
            assertTrue(controller.send("retry me"))
            await { !controller.state().isGenerating && controller.state().error == LocalChatError.GENERATION_FAILED }

            val messages = controller.state().activeConversation!!.messages
            assertEquals(1, messages.size)
            assertEquals(LocalChatRole.USER, messages.single().role)
            assertEquals(LocalChatMessageStatus.FAILED, messages.single().status)
            val report = diagnosticLog.snapshot().joinToString("\n")
            assertTrue(report.contains(DiagnosticCode.CHAT_SEND_FAILED.wireCode))
            assertFalse(report.contains("private prompt must not be logged"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun draftSurvivesControllerReuseAndIsClearedWhenAppLockCloses() {
        val allowed = AtomicReference(true)
        val controller = controller(MemoryHistoryStore(), FakeInference(), isAllowed = { allowed.get() })
        try {
            controller.open()
            await { !controller.state().isHistoryLoading }
            controller.updateDraft("private unsent draft")
            assertEquals("private unsent draft", controller.draft())

            // The application-scoped controller is reused by a rotated/recreated Activity.
            controller.open()
            assertEquals("private unsent draft", controller.draft())

            allowed.set(false)
            controller.onAccessLost()
            assertEquals("", controller.draft())
        } finally {
            controller.close()
        }
    }

    @Test
    fun modelNotInstalledIsAReadableErrorAndDoesNotConsumeInput() {
        val store = MemoryHistoryStore()
        val inference = FakeInference()
        val controller = LocalChatController(
            historyStore = store,
            inference = inference,
            isAccessAllowed = { true },
            isModelInstalled = { false },
            newId = { "chat-id" }
        )
        try {
            controller.open()
            await { !controller.state().isHistoryLoading }
            assertEquals(LocalChatError.MODEL_NOT_INSTALLED, controller.state().error)
            assertFalse(controller.send("keep this editor text"))
            assertEquals(0, inference.generateCalls.get())
        } finally {
            controller.close()
        }
    }

    private fun controller(
        store: MemoryHistoryStore,
        inference: FakeInference,
        isAllowed: () -> Boolean = { true }
    ) = LocalChatController(
        historyStore = store,
        inference = inference,
        isAccessAllowed = isAllowed,
        isModelInstalled = { true },
        clock = { 100L },
        newId = {
            "message-${ID.incrementAndGet()}"
        }
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            if (System.nanoTime() >= deadline) throw AssertionError("Timed out waiting for local chat state")
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun archive(conversation: LocalChatConversation) = LocalChatArchive(
        activeConversationId = conversation.id,
        conversations = listOf(conversation)
    )

    private fun conversation(messages: List<LocalChatMessage>) = LocalChatConversation(
        id = "conversation-1",
        title = "Saved chat",
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        messages = messages
    )

    private fun user(id: String, text: String) = LocalChatMessage(id, LocalChatRole.USER, text, 1L)
    private fun assistant(id: String, text: String) = LocalChatMessage(id, LocalChatRole.ASSISTANT, text, 2L)

    private class FailingWriteHistoryStore : LocalChatHistoryStore {
        val writeCalls = AtomicInteger()

        override fun read(): Result<LocalChatArchive> = Result.success(LocalChatArchive())
        override fun write(archive: LocalChatArchive): Result<Unit> {
            writeCalls.incrementAndGet()
            return Result.failure(
                ChatHistoryStorageException(
                    diagnostics.DiagnosticCode.CHAT_STORE_CRYPTO_FAILURE,
                    IllegalStateException::class.java
                )
            )
        }
        override fun clear(): Result<Unit> = Result.success(Unit)
    }

    private class MemoryHistoryStore(initial: LocalChatArchive = LocalChatArchive()) : LocalChatHistoryStore {
        @Volatile
        var archive: LocalChatArchive = initial
            private set
        private val cleared = AtomicInteger()

        override fun read(): Result<LocalChatArchive> = Result.success(archive)
        override fun write(archive: LocalChatArchive): Result<Unit> {
            this.archive = archive
            return Result.success(Unit)
        }
        override fun clear(): Result<Unit> {
            archive = LocalChatArchive()
            cleared.incrementAndGet()
            return Result.success(Unit)
        }
    }

    private class FakeInference : LocalChatInference {
        @Volatile
        var countPrompt: (String, List<LocalChatPromptMessage>) -> Int? = { _, messages -> messages.size * 10 }
        @Volatile
        var blockUntilStopped = false
        @Volatile
        var blockAfterFirstSnapshot = false
        @Volatile
        var generationFailure: Throwable? = null
        @Volatile
        var generatedMessages = AtomicReference<List<LocalChatPromptMessage>>(emptyList())
        val firstSnapshot = CountDownLatch(1)
        val releaseFirstSnapshot = CountDownLatch(1)
        val abortCalls = AtomicInteger()
        val generateCalls = AtomicInteger()
        @Volatile
        var generationThread: String? = null

        override fun countFormattedPromptTokens(
            systemPrompt: String,
            messages: List<LocalChatPromptMessage>,
            isStillAllowed: () -> Boolean
        ): Result<Int> {
            val count = countPrompt(systemPrompt, messages)
            return if (count == null) Result.failure(IllegalStateException("count unavailable"))
            else Result.success(count)
        }

        override fun generate(
            systemPrompt: String,
            messages: List<LocalChatPromptMessage>,
            options: LocalChatGenerationOptions,
            isStillAllowed: () -> Boolean,
            onPromptTokenCount: (Int) -> Unit,
            onTextSnapshot: (String) -> Unit
        ): Result<LocalChatGenerationResult> {
            generationThread = Thread.currentThread().name
            generateCalls.incrementAndGet()
            generatedMessages.set(messages.toList())
            onPromptTokenCount(messages.size * 10)
            onTextSnapshot("draft")
            firstSnapshot.countDown()
            if (blockAfterFirstSnapshot) releaseFirstSnapshot.await(WAIT_SECONDS, TimeUnit.SECONDS)
            if (blockUntilStopped) {
                while (isStillAllowed()) Thread.sleep(POLL_MILLIS)
                return Result.failure(IllegalStateException("cancelled"))
            }
            generationFailure?.let { return Result.failure(it) }
            onTextSnapshot("final answer")
            return Result.success(
                LocalChatGenerationResult(
                    text = "final answer",
                    promptTokens = messages.size * 10,
                    outputTokens = 3,
                    finishReason = "stop",
                    timeToFirstTokenMs = 14L,
                    prefillTokensPerSecond = 6.0,
                    decodeTokensPerSecond = 2.0
                )
            )
        }

        override fun requestAbortGeneration() {
            abortCalls.incrementAndGet()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 5L
        const val POLL_MILLIS = 5L
        val ID = AtomicInteger()
    }
}
