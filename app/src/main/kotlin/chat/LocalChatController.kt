package chat

import diagnostics.DiagnosticCode
import diagnostics.DiagnosticComponent
import diagnostics.DiagnosticField
import diagnostics.DiagnosticFieldKey
import diagnostics.DiagnosticLevel
import diagnostics.DiagnosticsLog
import inference.AgentModelException
import contracts.InferenceError
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class LocalChatGenerationResult(
    val text: String,
    val promptTokens: Int,
    val outputTokens: Int,
    val finishReason: String,
    val timeToFirstTokenMs: Long,
    val prefillTokensPerSecond: Double?,
    val decodeTokensPerSecond: Double?
) {
    override fun toString(): String =
        "LocalChatGenerationResult(text=<redacted>, promptTokens=$promptTokens, " +
            "outputTokens=$outputTokens, finishReason=$finishReason)"
}

internal interface LocalChatInference {
    fun countFormattedPromptTokens(
        systemPrompt: String,
        messages: List<LocalChatPromptMessage>,
        isStillAllowed: () -> Boolean
    ): Result<Int>

    fun generate(
        systemPrompt: String,
        messages: List<LocalChatPromptMessage>,
        options: LocalChatGenerationOptions,
        isStillAllowed: () -> Boolean,
        onPromptTokenCount: (Int) -> Unit,
        onTextSnapshot: (String) -> Unit
    ): Result<LocalChatGenerationResult>

    fun requestAbortGeneration()
}

/** Application-scoped chat state holder; survives Activity recreation without Android ViewModel APIs. */
internal class LocalChatController(
    private val historyStore: LocalChatHistoryStore,
    private val inference: LocalChatInference,
    private val isAccessAllowed: () -> Boolean,
    private val isModelInstalled: () -> Boolean,
    private val diagnostics: DiagnosticsLog? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) : Closeable {
    private val stateLock = Any()
    private val persistentHistoryStore = historyStore
    private val memoryOnlyHistoryStore = InMemoryLocalChatHistoryStore()
    private val listeners = CopyOnWriteArrayList<(LocalChatUiState) -> Unit>()
    private val operationExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, CHAT_CONTROLLER_THREAD_NAME).apply { isDaemon = true }
    }
    private val opening = AtomicBoolean(false)
    private val activeCancellation = AtomicReference<AtomicBoolean?>(null)
    private val activeAssistantMessageId = AtomicReference<String?>(null)
    private val pendingTextSnapshot = AtomicReference<String?>(null)
    private val firstStreamSnapshotSeen = AtomicBoolean(false)
    private val activeGenerationStartedNanos = AtomicLong(0L)

    @Volatile
    private var currentState = LocalChatUiState()
    @Volatile
    private var archive = LocalChatArchive()
    @Volatile
    private var usingMemoryOnlyHistory = false
    @Volatile
    private var historyFailureCode: DiagnosticCode? = null
    @Volatile
    private var historyFailureClass: Class<out Throwable>? = null
    @Volatile
    private var currentDraft = ""
    @Volatile
    private var opened = false

    fun state(): LocalChatUiState = currentState

    /** The draft stays in the application-scoped controller, never in Activity saved-state bundles. */
    fun draft(): String = currentDraft

    fun updateDraft(text: String) {
        currentDraft = if (isAllowed()) text else ""
    }

    fun clearDraft() {
        currentDraft = ""
    }

    fun addStateListener(listener: (LocalChatUiState) -> Unit): Closeable {
        listeners += listener
        runCatching { listener(currentState) }
        return Closeable { listeners -= listener }
    }

    fun open() {
        if (!isAllowed()) {
            onAccessLost()
            return
        }
        if (opened) {
            val error = if (hasInstalledModel()) {
                currentState.error.takeUnless { it == LocalChatError.MODEL_NOT_INSTALLED }
            } else {
                LocalChatError.MODEL_NOT_INSTALLED
            }
            if (currentState.error != error) publish(currentState.copy(error = error))
            return
        }
        if (!opening.compareAndSet(false, true)) return
        publish(currentState.copy(isHistoryLoading = true, error = null))
        try {
            operationExecutor.execute {
                try {
                    if (!isAllowed()) {
                        onAccessLost()
                        return@execute
                    }
                    val loadedResult = if (usingMemoryOnlyHistory) {
                        memoryOnlyHistoryStore.read()
                    } else {
                        try {
                            persistentHistoryStore.read()
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                    }
                    val loaded = if (loadedResult.isSuccess) {
                        loadedResult.getOrThrow()
                    } else {
                        val failure = loadedResult.exceptionOrNull() ?: ChatHistoryStorageException()
                        if (!isAllowed()) {
                            publish(LocalChatUiState(error = LocalChatError.APP_LOCKED))
                            return@execute
                        }
                        if (!usingMemoryOnlyHistory) {
                            activateMemoryOnly(LocalChatArchive(), failure, DiagnosticCode.CHAT_STORE_READ_FAILURE)
                        }
                        memoryOnlyHistoryStore.read().getOrDefault(LocalChatArchive())
                    }
                    val now = clock()
                    val active = loaded.conversations.firstOrNull { it.id == loaded.activeConversationId }
                        ?: loaded.conversations.maxByOrNull(LocalChatConversation::updatedAtEpochMs)
                    val normalizedArchive = if (active == null) {
                        val first = newConversation(now)
                        LocalChatArchive(activeConversationId = first.id, conversations = listOf(first))
                    } else {
                        loaded.copy(activeConversationId = active.id)
                    }
                    archive = normalizedArchive
                    opened = true
                    if (loaded != normalizedArchive && isAllowed()) {
                        persistArchive()
                    }
                    val activeConversation = normalizedArchive.conversations.firstOrNull {
                        it.id == normalizedArchive.activeConversationId
                    }
                    publish(LocalChatUiState(
                        activeConversation = activeConversation,
                        savedConversationCount = normalizedArchive.conversations.size,
                        isHistoryLoading = false,
                        error = when {
                            !isAllowed() -> LocalChatError.APP_LOCKED
                            !hasInstalledModel() -> LocalChatError.MODEL_NOT_INSTALLED
                            else -> null
                        },
                        isHistoryEphemeral = usingMemoryOnlyHistory,
                        historyDiagnosticCode = historyFailureCode,
                        historyExceptionClass = historyFailureClass
                    ))
                } finally {
                    opening.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            opening.set(false)
            publish(currentState.copy(isHistoryLoading = false, error = LocalChatError.HISTORY_UNAVAILABLE))
        }
    }

    /** Begins a new saved conversation and selects it as the active transcript. */
    fun newConversation(): Boolean {
        if (!isAllowed()) {
            publish(currentState.copy(error = LocalChatError.APP_LOCKED))
            return false
        }
        if (currentState.isHistoryLoading || currentState.isGenerating) return false
        val created = newConversation(clock())
        synchronized(stateLock) {
            val updated = archive.copy(
                activeConversationId = created.id,
                conversations = archive.conversations + created
            )
            archive = updated
            currentState = LocalChatUiState(
                activeConversation = created,
                savedConversationCount = updated.conversations.size,
                error = if (hasInstalledModel()) null else LocalChatError.MODEL_NOT_INSTALLED,
                isHistoryEphemeral = usingMemoryOnlyHistory,
                historyDiagnosticCode = historyFailureCode,
                historyExceptionClass = historyFailureClass
            )
        }
        currentDraft = ""
        notifyListeners()
        enqueueArchiveSave()
        return true
    }

    /** Returns false without consuming the editor text when the chat cannot start. */
    fun send(userText: String): Boolean {
        val text = userText.trim()
        if (text.isEmpty() || currentState.isHistoryLoading || currentState.isGenerating) return false
        if (!isAllowed()) {
            publish(currentState.copy(error = LocalChatError.APP_LOCKED))
            return false
        }
        if (!hasInstalledModel()) {
            publish(currentState.copy(error = LocalChatError.MODEL_NOT_INSTALLED))
            return false
        }
        val conversation = activeConversationOrCreate()
        val userMessage = LocalChatMessage(
            id = newId(),
            role = LocalChatRole.USER,
            content = text,
            createdAtEpochMs = clock()
        )
        val title = if (conversation.messages.isEmpty()) titleFrom(text) else conversation.title
        val withUser = conversation.copy(
            title = title,
            updatedAtEpochMs = clock(),
            messages = conversation.messages + userMessage
        )
        val assistantPlaceholder = newAssistantPlaceholder()
        replaceConversationInArchive(withUser)
        beginGeneration(withUser, assistantPlaceholder)
        queueGeneration(withUser, assistantPlaceholder.id)
        return true
    }

    fun regenerateLastResponse(): Boolean {
        if (currentState.isHistoryLoading || currentState.isGenerating || !isAllowed() || !hasInstalledModel()) {
            return false
        }
        val latestAssistantId = currentState.activeConversation?.messages
            ?.lastOrNull { it.role == LocalChatRole.ASSISTANT }
            ?.id ?: return false
        return regenerateResponse(latestAssistantId)
    }

    /** Regenerates the selected answer and discards later turns so the transcript stays coherent. */
    fun regenerateResponse(assistantMessageId: String): Boolean {
        if (currentState.isHistoryLoading || currentState.isGenerating || !isAllowed() || !hasInstalledModel()) {
            return false
        }
        val current = activeConversationOrCreate()
        val assistantIndex = current.messages.indexOfFirst { it.id == assistantMessageId }
        if (assistantIndex < 0 || current.messages[assistantIndex].role != LocalChatRole.ASSISTANT) return false
        val userIndex = current.messages.subList(0, assistantIndex)
            .indexOfLast { it.role == LocalChatRole.USER }
        if (userIndex < 0) return false
        val trimmed = current.copy(
            updatedAtEpochMs = clock(),
            messages = current.messages.take(userIndex + 1)
        )
        val placeholder = newAssistantPlaceholder()
        replaceConversationInArchive(trimmed)
        beginGeneration(trimmed, placeholder)
        queueGeneration(trimmed, placeholder.id)
        return true
    }

    fun stopGeneration() {
        val cancellation = activeCancellation.get() ?: return
        if (cancellation.compareAndSet(false, true)) {
            publish(currentState.copy(isCancelling = true))
            runCatching { inference.requestAbortGeneration() }
        }
    }

    /** Called from a 40 ms UI ticker; native token callbacks only replace this pending snapshot. */
    fun flushStreamingText(): Boolean {
        val snapshot = pendingTextSnapshot.getAndSet(null) ?: return false
        val messageId = activeAssistantMessageId.get() ?: return false
        val current = currentState
        if (!current.isGenerating) return false
        val conversation = current.activeConversation ?: return false
        if (conversation.messages.none { it.id == messageId }) return false
        val updated = conversation.copy(messages = conversation.messages.map { message ->
            if (message.id == messageId) message.copy(content = snapshot) else message
        })
        publish(current.copy(activeConversation = updated))
        return true
    }

    /** Clears decrypted transcript state after lock/Panic Reset; ciphertext remains unless reset purges it. */
    fun onAccessLost() {
        if (currentState.isGenerating) {
            recordGenerationOutcome(
                DiagnosticCode.CHAT_SEND_CANCELLED,
                DiagnosticLevel.WARNING,
                result = LocalChatError.APP_LOCKED,
                promptTokenCount = currentState.promptTokenCount,
                phase = currentState.phase
            )
        }
        activeCancellation.getAndSet(null)?.set(true)
        runCatching { inference.requestAbortGeneration() }
        pendingTextSnapshot.set(null)
        activeAssistantMessageId.set(null)
        firstStreamSnapshotSeen.set(false)
        archive = LocalChatArchive()
        memoryOnlyHistoryStore.clear()
        usingMemoryOnlyHistory = false
        historyFailureCode = null
        historyFailureClass = null
        currentDraft = ""
        opened = false
        publish(LocalChatUiState(error = LocalChatError.APP_LOCKED))
    }

    /** Panic Reset callback: wipes in-memory chat state as well as the encrypted history file. */
    fun wipeForPanicReset() {
        activeCancellation.getAndSet(null)?.set(true)
        runCatching { inference.requestAbortGeneration() }
        pendingTextSnapshot.set(null)
        activeAssistantMessageId.set(null)
        firstStreamSnapshotSeen.set(false)
        archive = LocalChatArchive()
        memoryOnlyHistoryStore.clear()
        usingMemoryOnlyHistory = false
        historyFailureCode = null
        historyFailureClass = null
        currentDraft = ""
        opened = false
        runCatching { persistentHistoryStore.clear() }
        publish(LocalChatUiState(error = LocalChatError.APP_LOCKED))
    }

    override fun close() {
        stopGeneration()
        operationExecutor.shutdownNow()
        listeners.clear()
    }

    private fun queueGeneration(conversation: LocalChatConversation, assistantId: String) {
        val cancellation = AtomicBoolean(false)
        activeGenerationStartedNanos.set(System.nanoTime())
        diagnostics?.record(
            DiagnosticLevel.INFO,
            DiagnosticComponent.CHAT_CONTROLLER,
            DiagnosticCode.CHAT_SEND_BEGIN,
            DiagnosticField.enum(DiagnosticFieldKey.PHASE, LocalChatGenerationPhase.PREPARING)
        )
        activeCancellation.set(cancellation)
        activeAssistantMessageId.set(assistantId)
        pendingTextSnapshot.set(null)
        firstStreamSnapshotSeen.set(false)
        try {
            operationExecutor.execute {
                runGeneration(conversation, assistantId, cancellation)
            }
        } catch (failure: RejectedExecutionException) {
            completeFailure(conversation, assistantId, LocalChatError.GENERATION_FAILED, "", failureCause = failure)
        }
    }

    private fun runGeneration(
        conversation: LocalChatConversation,
        assistantId: String,
        cancellation: AtomicBoolean
    ) {
        if (!canContinue(cancellation)) {
            if (!isAllowed()) onAccessLost() else completeCancelled(conversation, assistantId, "", 0)
            return
        }
        val initialSave = persistArchive()
        if (initialSave.isFailure) {
            completeFailure(
                conversation,
                assistantId,
                LocalChatError.HISTORY_UNAVAILABLE,
                "",
                failureCause = initialSave.exceptionOrNull()
            )
            return
        }

        publish(currentState.copy(phase = LocalChatGenerationPhase.LOADING_MODEL))
        val promptMessages = conversation.messages
            .filter { it.status != LocalChatMessageStatus.GENERATING && it.content.isNotEmpty() }
            .map { LocalChatPromptMessage(it.role, it.content) }
        var tokenCountFailure: Throwable? = null
        val selection = LocalChatPromptPolicy.selectWithinBudget(
            systemPrompt = LocalChatPromptPolicy.DEFAULT_SYSTEM_PROMPT,
            messages = promptMessages
        ) { systemPrompt, messages ->
            val result = inference.countFormattedPromptTokens(systemPrompt, messages) {
                canContinue(cancellation)
            }
            result.fold(
                onSuccess = { it },
                onFailure = { error ->
                    tokenCountFailure = error
                    null
                }
            )
        }

        if (!canContinue(cancellation)) {
            if (!isAllowed()) onAccessLost() else completeCancelled(conversation, assistantId, currentPartialText(), 0)
            return
        }
        when (selection) {
            is LocalChatPromptPolicy.Selection.LatestTurnTooLarge -> {
                completeFailure(
                    conversation,
                    assistantId,
                    LocalChatError.PROMPT_EXCEEDS_LIMIT,
                    "",
                    selection.formattedTokenCount
                )
                return
            }
            LocalChatPromptPolicy.Selection.TokenCountUnavailable -> {
                completeFailure(
                    conversation,
                    assistantId,
                    mapError(tokenCountFailure),
                    "",
                    failureCause = tokenCountFailure
                )
                return
            }
            is LocalChatPromptPolicy.Selection.Ready -> {
                val active = currentState.activeConversation
                if (active != null) {
                    publish(currentState.copy(
                        activeConversation = active.copy(messages = active.messages.map { message ->
                            if (message.id == assistantId) message.copy(
                                olderContextTrimmed = selection.removedTurns > 0
                            ) else message
                        }),
                        promptTokenCount = selection.formattedTokenCount,
                        olderContextTrimmed = selection.removedTurns > 0,
                        phase = LocalChatGenerationPhase.PREFILLING
                    ))
                }
            }
        }
        val ready = selection as? LocalChatPromptPolicy.Selection.Ready ?: return

        val generated = inference.generate(
            systemPrompt = LocalChatPromptPolicy.DEFAULT_SYSTEM_PROMPT,
            messages = ready.messages,
            options = LocalChatGenerationOptions(),
            isStillAllowed = { canContinue(cancellation) },
            onPromptTokenCount = { tokenCount ->
                if (canContinue(cancellation)) {
                    publish(currentState.copy(
                        promptTokenCount = tokenCount,
                        phase = LocalChatGenerationPhase.PREFILLING
                    ))
                }
            },
            onTextSnapshot = { snapshot ->
                if (canContinue(cancellation)) {
                    pendingTextSnapshot.set(snapshot)
                    if (snapshot.isNotEmpty() && firstStreamSnapshotSeen.compareAndSet(false, true)) {
                        diagnostics?.record(
                            DiagnosticLevel.DEBUG,
                            DiagnosticComponent.CHAT_CONTROLLER,
                            DiagnosticCode.CHAT_SEND_FIRST_TOKEN,
                            DiagnosticField.number(DiagnosticFieldKey.DURATION_MS, generationElapsedMs())
                        )
                        publish(currentState.copy(phase = LocalChatGenerationPhase.GENERATING))
                    }
                }
            }
        )

        if (!isAllowed()) {
            onAccessLost()
            return
        }
        if (cancellation.get()) {
            completeCancelled(conversation, assistantId, currentPartialText(), ready.removedTurns)
            return
        }
        generated.fold(
            onSuccess = { result ->
                pendingTextSnapshot.set(null)
                val metrics = LocalChatMetrics(
                    promptTokens = result.promptTokens,
                    outputTokens = result.outputTokens,
                    timeToFirstTokenMs = result.timeToFirstTokenMs,
                    prefillTokensPerSecond = result.prefillTokensPerSecond,
                    decodeTokensPerSecond = result.decodeTokensPerSecond
                )
                completeSuccess(conversation, assistantId, result.text, metrics, ready.removedTurns > 0)
            },
            onFailure = { error ->
                if (cancellation.get()) {
                    completeCancelled(conversation, assistantId, currentPartialText(), ready.removedTurns)
                } else {
                    completeFailure(conversation, assistantId, mapError(error), currentPartialText(), failureCause = error)
                }
            }
        )
    }

    private fun completeSuccess(
        conversation: LocalChatConversation,
        assistantId: String,
        text: String,
        metrics: LocalChatMetrics,
        contextTrimmed: Boolean
    ) {
        if (text.isBlank()) {
            completeFailure(
                conversation = conversation,
                assistantId = assistantId,
                error = LocalChatError.GENERATION_FAILED,
                partialText = "",
                promptTokens = metrics.promptTokens
            )
            return
        }
        val assistant = LocalChatMessage(
            id = assistantId,
            role = LocalChatRole.ASSISTANT,
            content = text,
            createdAtEpochMs = clock(),
            status = LocalChatMessageStatus.COMPLETE,
            metrics = metrics,
            olderContextTrimmed = contextTrimmed
        )
        recordGenerationOutcome(
            DiagnosticCode.CHAT_SEND_FINISHED,
            DiagnosticLevel.INFO,
            promptTokenCount = metrics.promptTokens,
            outputTokenCount = metrics.outputTokens,
            firstTokenMs = metrics.timeToFirstTokenMs,
            prefillTokensPerSecond = metrics.prefillTokensPerSecond,
            decodeTokensPerSecond = metrics.decodeTokensPerSecond,
            phase = currentState.phase
        )
        finish(conversation, assistant, error = null, promptTokens = metrics.promptTokens)
    }

    private fun completeCancelled(
        conversation: LocalChatConversation,
        assistantId: String,
        partialText: String,
        removedTurns: Int
    ) {
        if (!isAllowed()) {
            onAccessLost()
            return
        }
        val assistant = LocalChatMessage(
            id = assistantId,
            role = LocalChatRole.ASSISTANT,
            content = partialText,
            createdAtEpochMs = clock(),
            status = LocalChatMessageStatus.CANCELLED,
            olderContextTrimmed = removedTurns > 0
        )
        recordGenerationOutcome(
            DiagnosticCode.CHAT_SEND_CANCELLED,
            DiagnosticLevel.INFO,
            result = LocalChatError.CANCELLED,
            promptTokenCount = currentState.promptTokenCount,
            phase = currentState.phase
        )
        finish(conversation, assistant, error = LocalChatError.CANCELLED, promptTokens = currentState.promptTokenCount)
    }

    private fun completeFailure(
        conversation: LocalChatConversation,
        assistantId: String,
        error: LocalChatError,
        partialText: String,
        promptTokens: Int? = currentState.promptTokenCount,
        failureCause: Throwable? = null
    ) {
        if (!isAllowed()) {
            onAccessLost()
            return
        }
        val assistant = LocalChatMessage(
            id = assistantId,
            role = LocalChatRole.ASSISTANT,
            content = partialText,
            createdAtEpochMs = clock(),
            status = LocalChatMessageStatus.FAILED,
            olderContextTrimmed = currentState.olderContextTrimmed
        )
        recordGenerationOutcome(
            DiagnosticCode.CHAT_SEND_FAILED,
            if (error == LocalChatError.PROMPT_EXCEEDS_LIMIT) DiagnosticLevel.WARNING else DiagnosticLevel.ERROR,
            result = error,
            promptTokenCount = promptTokens,
            failure = failureCause,
            phase = currentState.phase
        )
        finish(conversation, assistant, error, promptTokens)
    }

    private fun finish(
        conversation: LocalChatConversation,
        assistant: LocalChatMessage,
        error: LocalChatError?,
        promptTokens: Int?
    ) {
        val latest = archive.conversations.firstOrNull { it.id == conversation.id } ?: conversation
        val failedUserId = if (error != null && error != LocalChatError.CANCELLED) {
            latest.messages.lastOrNull { it.role == LocalChatRole.USER }?.id
        } else {
            null
        }
        val keepAssistantMessage = assistant.status != LocalChatMessageStatus.FAILED || assistant.content.isNotEmpty()
        val finalMessages = latest.messages
            .filterNot { it.id == assistant.id }
            .map { message ->
                if (message.id == failedUserId) message.copy(status = LocalChatMessageStatus.FAILED) else message
            }
            .let { messages -> if (keepAssistantMessage) messages + assistant else messages }
        val updatedConversation = latest.copy(
            updatedAtEpochMs = clock(),
            messages = finalMessages
        )
        replaceConversationInArchive(updatedConversation)
        activeCancellation.set(null)
        activeAssistantMessageId.set(null)
        pendingTextSnapshot.set(null)
        firstStreamSnapshotSeen.set(false)
        val saved = persistArchive()
        val finalError = when {
            !isAllowed() -> LocalChatError.APP_LOCKED
            saved.isFailure -> LocalChatError.HISTORY_UNAVAILABLE
            else -> error
        }
        publish(LocalChatUiState(
            activeConversation = if (isAllowed()) updatedConversation else null,
            savedConversationCount = archive.conversations.size,
            isHistoryLoading = false,
            isGenerating = false,
            phase = LocalChatGenerationPhase.IDLE,
            promptTokenCount = promptTokens,
            error = finalError,
            olderContextTrimmed = assistant.olderContextTrimmed,
            isHistoryEphemeral = usingMemoryOnlyHistory,
            historyDiagnosticCode = historyFailureCode,
            historyExceptionClass = historyFailureClass
        ))
        if (!isAllowed()) onAccessLost()
    }

    private fun beginGeneration(conversation: LocalChatConversation, placeholder: LocalChatMessage) {
        val display = conversation.copy(messages = conversation.messages + placeholder)
        publish(LocalChatUiState(
            activeConversation = display,
            savedConversationCount = archive.conversations.size,
            isGenerating = true,
            phase = LocalChatGenerationPhase.PREPARING,
            promptTokenCount = null,
            error = null,
            isHistoryEphemeral = usingMemoryOnlyHistory,
            historyDiagnosticCode = historyFailureCode,
            historyExceptionClass = historyFailureClass
        ))
    }

    private fun activeConversationOrCreate(): LocalChatConversation {
        val current = archive.conversations.firstOrNull { it.id == archive.activeConversationId }
        if (current != null) return current
        val created = newConversation(clock())
        replaceConversationInArchive(created)
        return created
    }

    private fun replaceConversationInArchive(conversation: LocalChatConversation) {
        synchronized(stateLock) {
            val exists = archive.conversations.any { it.id == conversation.id }
            val conversations = if (exists) {
                archive.conversations.map { if (it.id == conversation.id) conversation else it }
            } else {
                archive.conversations + conversation
            }
            archive = archive.copy(
                activeConversationId = conversation.id,
                conversations = conversations
            )
        }
    }

    private fun persistArchive(): Result<Unit> {
        if (!isAllowed()) return Result.failure(ChatHistoryAccessException())
        if (usingMemoryOnlyHistory) return memoryOnlyHistoryStore.write(archive)

        val saved = try {
            persistentHistoryStore.write(archive)
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        if (saved.isSuccess) return saved
        if (!isAllowed()) return Result.failure(ChatHistoryAccessException())
        val failure = saved.exceptionOrNull() ?: ChatHistoryStorageException()
        activateMemoryOnly(archive, failure, DiagnosticCode.CHAT_STORE_WRITE_FAILURE)
        return memoryOnlyHistoryStore.write(archive)
    }

    private fun activateMemoryOnly(
        seed: LocalChatArchive,
        failure: Throwable,
        fallbackCode: DiagnosticCode
    ) {
        val storageFailure = failure as? ChatHistoryStorageException
        val code = storageFailure?.diagnosticCode ?: fallbackCode
        val failureClass = storageFailure?.failureClass ?: failure.javaClass
        memoryOnlyHistoryStore.write(seed)
        usingMemoryOnlyHistory = true
        historyFailureCode = code
        historyFailureClass = failureClass
        diagnostics?.record(
            DiagnosticLevel.WARNING,
            DiagnosticComponent.CHAT_STORE,
            DiagnosticCode.CHAT_STORE_FALLBACK,
            DiagnosticField.enum(DiagnosticFieldKey.RESULT_TYPE, code),
            DiagnosticField.classType(DiagnosticFieldKey.EXCEPTION_TYPE, failureClass)
        )
        publish(currentState.copy(
            isHistoryEphemeral = true,
            historyDiagnosticCode = code,
            historyExceptionClass = failureClass
        ))
    }

    private fun generationElapsedMs(): Long =
        ((System.nanoTime() - activeGenerationStartedNanos.get()).coerceAtLeast(0L) / 1_000_000L)

    private fun recordGenerationOutcome(
        code: DiagnosticCode,
        level: DiagnosticLevel,
        result: LocalChatError? = null,
        promptTokenCount: Int? = null,
        outputTokenCount: Int? = null,
        firstTokenMs: Long? = null,
        prefillTokensPerSecond: Double? = null,
        decodeTokensPerSecond: Double? = null,
        phase: LocalChatGenerationPhase? = null,
        failure: Throwable? = null
    ) {
        val fields = mutableListOf(DiagnosticField.number(DiagnosticFieldKey.DURATION_MS, generationElapsedMs()))
        result?.let { fields += DiagnosticField.enum(DiagnosticFieldKey.RESULT_TYPE, it) }
        promptTokenCount?.let { fields += DiagnosticField.number(DiagnosticFieldKey.PROMPT_TOKEN_COUNT, it.toLong()) }
        outputTokenCount?.let { fields += DiagnosticField.number(DiagnosticFieldKey.OUTPUT_TOKEN_COUNT, it.toLong()) }
        firstTokenMs?.let { fields += DiagnosticField.number(DiagnosticFieldKey.TIME_TO_FIRST_TOKEN_MS, it) }
        prefillTokensPerSecond?.takeIf { it.isFinite() }?.let {
            fields += DiagnosticField.decimal(DiagnosticFieldKey.PREFILL_TOKENS_PER_SECOND, it)
        }
        decodeTokensPerSecond?.takeIf { it.isFinite() }?.let {
            fields += DiagnosticField.decimal(DiagnosticFieldKey.DECODE_TOKENS_PER_SECOND, it)
        }
        phase?.let { fields += DiagnosticField.enum(DiagnosticFieldKey.PHASE, it) }
        if (failure != null) {
            diagnostics?.recordFailure(level, DiagnosticComponent.CHAT_CONTROLLER, code, failure, *fields.toTypedArray())
        } else {
            diagnostics?.record(level, DiagnosticComponent.CHAT_CONTROLLER, code, *fields.toTypedArray())
        }
    }

    private fun enqueueArchiveSave() {
        runCatching {
            operationExecutor.execute {
                val saved = persistArchive()
                if (saved.isFailure) publish(currentState.copy(error = mapError(saved.exceptionOrNull())))
            }
        }
    }

    private fun currentPartialText(): String {
        flushStreamingText()
        val assistantId = activeAssistantMessageId.get() ?: return pendingTextSnapshot.get().orEmpty()
        return currentState.activeConversation?.messages
            ?.firstOrNull { it.id == assistantId }
            ?.content
            ?: pendingTextSnapshot.get().orEmpty()
    }

    private fun mapError(error: Throwable?): LocalChatError {
        if (!isAllowed()) return LocalChatError.APP_LOCKED
        val modelError = (error as? AgentModelException)?.error
        return when (modelError) {
            is InferenceError.ResourceExhausted -> LocalChatError.LOW_MEMORY
            else -> LocalChatError.GENERATION_FAILED
        }
    }

    private fun canContinue(cancellation: AtomicBoolean): Boolean =
        !cancellation.get() && isAllowed()

    private fun isAllowed(): Boolean = runCatching(isAccessAllowed).getOrDefault(false)

    private fun hasInstalledModel(): Boolean = runCatching(isModelInstalled).getOrDefault(false)

    private fun newConversation(timestamp: Long): LocalChatConversation = LocalChatConversation(
        id = newId(),
        title = DEFAULT_CONVERSATION_TITLE,
        createdAtEpochMs = timestamp,
        updatedAtEpochMs = timestamp
    )

    private fun newAssistantPlaceholder(): LocalChatMessage = LocalChatMessage(
        id = newId(),
        role = LocalChatRole.ASSISTANT,
        content = "",
        createdAtEpochMs = clock(),
        status = LocalChatMessageStatus.GENERATING
    )

    private fun titleFrom(text: String): String = text.lineSequence()
        .firstOrNull()
        ?.trim()
        ?.take(MAX_TITLE_CHARACTERS)
        ?.ifEmpty { DEFAULT_CONVERSATION_TITLE }
        ?: DEFAULT_CONVERSATION_TITLE

    private fun publish(state: LocalChatUiState) {
        synchronized(stateLock) { currentState = state }
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = currentState
        listeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    companion object {
        const val MAX_TITLE_CHARACTERS = 48
        const val DEFAULT_CONVERSATION_TITLE = "New conversation"
        private const val CHAT_CONTROLLER_THREAD_NAME = "local-chat-controller"
    }
}
