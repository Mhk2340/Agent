package com.mhk2340.agent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import chat.LightweightMarkdown
import chat.LocalChatConversation
import chat.LocalChatController
import diagnostics.DiagnosticCode
import chat.LocalChatError
import chat.LocalChatGenerationPhase
import chat.LocalChatMessage
import chat.LocalChatMessageStatus
import chat.LocalChatRole
import chat.LocalMarkdownBlock
import java.io.Closeable

/** XML/View chat screen backed by the application-scoped controller so rotation keeps generation state. */
class LocalChatActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var controller: LocalChatController
    private lateinit var scrollView: ScrollView
    private lateinit var messageList: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var jumpButton: Button
    private lateinit var statusView: TextView
    private lateinit var errorView: TextView
    private lateinit var contextNoteView: TextView
    private lateinit var historyWarningView: TextView
    private lateinit var welcomeTitle: TextView
    private lateinit var welcomeDetail: TextView
    private var stateSubscription: Closeable? = null
    private var renderedConversationId: String? = null
    private var renderedMessageIds: List<String> = emptyList()
    private val renderedMessages = linkedMapOf<String, RenderedMessage>()
    private var autoScrollEnabled = true
    private var currentState = chat.LocalChatUiState()

    private val streamTicker = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            controller.flushStreamingText()
            mainHandler.postDelayed(this, STREAM_REFRESH_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureWindowFlag(window)
        setContentView(R.layout.screen_local_chat)
        scrollView = findViewById(R.id.local_chat_scroll)
        messageList = findViewById(R.id.local_chat_messages)
        input = findViewById(R.id.local_chat_input)
        sendButton = findViewById(R.id.local_chat_send_button)
        jumpButton = findViewById(R.id.local_chat_jump_button)
        statusView = findViewById(R.id.local_chat_status)
        errorView = findViewById(R.id.local_chat_error)
        contextNoteView = findViewById(R.id.local_chat_context_note)
        historyWarningView = findViewById(R.id.local_chat_history_warning)
        welcomeTitle = findViewById(R.id.local_chat_welcome)
        welcomeDetail = findViewById(R.id.local_chat_welcome_detail)
        input.isSaveEnabled = false

        val container = (application as AgentApplication).agentContainer
        controller = container.localChatController
        if (!isChatAccessAllowed(container)) {
            controller.onAccessLost()
            finish()
            return
        }

        findViewById<View>(R.id.local_chat_back_button).setOnClickListener {
            hideKeyboard()
            finish()
        }
        findViewById<View>(R.id.local_chat_new_button).setOnClickListener {
            if (controller.newConversation()) {
                controller.clearDraft()
                input.text.clear()
                autoScrollEnabled = true
            }
        }
        input.setText(controller.draft())
        jumpButton.setOnClickListener {
            autoScrollEnabled = true
            jumpToBottom()
        }
        sendButton.setOnClickListener { onSendOrStop() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onSendOrStop()
                true
            } else {
                false
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                controller.updateDraft(s?.toString().orEmpty())
                updateSendButton(currentState)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        scrollView.setOnScrollChangeListener { view: View, _, scrollY, _, _ ->
            val child = scrollView.getChildAt(0) ?: return@setOnScrollChangeListener
            val atBottom = scrollY + view.height >= child.height - dp(32)
            if (atBottom) {
                autoScrollEnabled = true
                jumpButton.visibility = View.GONE
            } else if (child.height > view.height) {
                autoScrollEnabled = false
                jumpButton.visibility = View.VISIBLE
            }
        }

        stateSubscription = controller.addStateListener { state ->
            mainHandler.post {
                if (!isFinishing && !isDestroyed) render(state)
            }
        }
        controller.open()
        mainHandler.post(streamTicker)
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) {
            val container = (application as AgentApplication).agentContainer
            if (!isChatAccessAllowed(container)) {
                controller.onAccessLost()
                finish()
            } else {
                controller.open()
            }
        }
    }

    override fun onStop() {
        if (::controller.isInitialized && !isChangingConfigurations) {
            controller.stopGeneration()
            val container = (application as AgentApplication).agentContainer
            if (!isChatAccessAllowed(container)) controller.onAccessLost()
        }
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(streamTicker)
        stateSubscription?.close()
        stateSubscription = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::controller.isInitialized) controller.stopGeneration()
        hideKeyboard()
        super.onBackPressed()
    }

    private fun onSendOrStop() {
        if (currentState.isGenerating) {
            controller.stopGeneration()
            return
        }
        val text = input.text.toString()
        if (controller.send(text)) {
            controller.clearDraft()
            input.text.clear()
            autoScrollEnabled = true
            jumpToBottom()
        }
    }

    private fun render(state: chat.LocalChatUiState) {
        currentState = state
        renderMessages(state.activeConversation)
        contextNoteView.visibility = if (state.olderContextTrimmed) View.VISIBLE else View.GONE
        val storageClass = state.historyExceptionClass?.simpleName
            ?.let { getString(R.string.diagnostics_failure_class, it) }.orEmpty()
        historyWarningView.text = getString(
            R.string.local_chat_history_ephemeral,
            state.historyDiagnosticCode?.wireCode ?: DiagnosticCode.CHAT_STORE_FALLBACK.wireCode,
            storageClass
        )
        historyWarningView.visibility = if (state.isHistoryEphemeral) View.VISIBLE else View.GONE
        renderStatus(state)
        renderError(state.error, state.promptTokenCount, state.historyDiagnosticCode, state.historyExceptionClass)
        updateSendButton(state)
        if (autoScrollEnabled) {
            scrollView.post { jumpToBottom() }
        } else if (messageList.height > scrollView.height) {
            jumpButton.visibility = View.VISIBLE
        }
    }

    private fun renderMessages(conversation: LocalChatConversation?) {
        val messages = conversation?.messages.orEmpty()
        val ids = messages.map(LocalChatMessage::id)
        val reset = conversation?.id != renderedConversationId || ids != renderedMessageIds
        val noMessages = messages.isEmpty()
        welcomeTitle.visibility = if (noMessages) View.VISIBLE else View.GONE
        welcomeDetail.visibility = if (noMessages) View.VISIBLE else View.GONE

        if (reset) {
            while (messageList.childCount > WELCOME_VIEW_COUNT) {
                messageList.removeViewAt(messageList.childCount - 1)
            }
            renderedMessages.clear()
            messages.forEach { message ->
                val view = createMessageView(message)
                messageList.addView(view.root)
                renderedMessages[message.id] = view
            }
            renderedConversationId = conversation?.id
            renderedMessageIds = ids
        } else {
            messages.forEachIndexed { index, message ->
                val rendered = renderedMessages[message.id] ?: return@forEachIndexed
                if (rendered.message != message) {
                    val replacement = createMessageView(message)
                    val childIndex = WELCOME_VIEW_COUNT + index
                    messageList.removeViewAt(childIndex)
                    messageList.addView(replacement.root, childIndex)
                    renderedMessages[message.id] = replacement
                }
            }
        }
    }

    private fun createMessageView(message: LocalChatMessage): RenderedMessage {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = if (message.role == LocalChatRole.USER) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        }

        if (message.role == LocalChatRole.USER) {
            val bubble = TextView(this).apply {
                text = message.content
                textSize = BODY_TEXT_SP
                setTextColor(getColor(R.color.agent_text))
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                gravity = Gravity.START
                maxWidth = (resources.displayMetrics.widthPixels * USER_BUBBLE_MAX_WIDTH_FRACTION).toInt()
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = roundedBackground(getColor(R.color.agent_surface), dp(18).toFloat())
            }
            root.addView(bubble, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.END })
            if (message.status == LocalChatMessageStatus.FAILED) root.addView(failedRetryLabel())
            return RenderedMessage(message, root)
        }

        val contentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        renderMarkdownInto(contentHost, message.content, allowCopyActions = message.status == LocalChatMessageStatus.COMPLETE)
        root.addView(contentHost)

        if (message.status == LocalChatMessageStatus.COMPLETE) {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            }
            actions.addView(smallActionButton(R.string.local_chat_copy) {
                copyToClipboard(message.content)
            })
            actions.addView(smallActionButton(R.string.local_chat_regenerate) {
                if (controller.regenerateResponse(message.id)) {
                    autoScrollEnabled = true
                    jumpToBottom()
                }
            })
            root.addView(actions)
        } else if (message.status == LocalChatMessageStatus.FAILED) {
            root.addView(failedRetryLabel())
        }
        return RenderedMessage(message, root)
    }

    private fun renderMarkdownInto(host: LinearLayout, source: String, allowCopyActions: Boolean) {
        host.removeAllViews()
        LightweightMarkdown.parse(source).forEach { block ->
            when (block) {
                is LocalMarkdownBlock.Paragraph -> {
                    val text = TextView(this).apply {
                        this.text = inlineBold(block.text)
                        textSize = BODY_TEXT_SP
                        setTextColor(getColor(R.color.agent_text))
                        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                        setLineSpacing(dp(2).toFloat(), 1f)
                        setPadding(0, dp(2), 0, dp(5))
                        gravity = Gravity.START
                        setTextIsSelectable(true)
                    }
                    host.addView(text)
                }
                is LocalMarkdownBlock.ListItem -> {
                    val prefix = block.marker?.let { "$it " } ?: if (block.ordered) "1. " else "• "
                    val text = TextView(this).apply {
                        this.text = SpannableStringBuilder(prefix).append(inlineBold(block.text))
                        textSize = BODY_TEXT_SP
                        setTextColor(getColor(R.color.agent_text))
                        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                        gravity = Gravity.START
                        setPadding(dp(8), dp(2), 0, dp(2))
                        setTextIsSelectable(true)
                    }
                    host.addView(text)
                }
                is LocalMarkdownBlock.Code -> host.addView(codeBlockView(block, allowCopyActions))
            }
        }
    }

    private fun codeBlockView(block: LocalMarkdownBlock.Code, allowCopyActions: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(getColor(R.color.agent_surface), dp(8).toFloat())
        setPadding(dp(10), dp(6), dp(10), dp(8))
        val header = LinearLayout(this@LocalChatActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        val language = TextView(this@LocalChatActivity).apply {
            text = block.language.orEmpty()
            textSize = 11f
            setTextColor(getColor(R.color.agent_secondary_text))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(language)
        if (allowCopyActions) {
            header.addView(smallActionButton(R.string.local_chat_copy_code) {
                copyToClipboard(block.text)
            })
        }
        addView(header)
        val code = TextView(this@LocalChatActivity).apply {
            text = block.text
            typeface = Typeface.MONOSPACE
            textSize = CODE_TEXT_SP
            setTextColor(getColor(R.color.agent_text))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, 0)
            gravity = Gravity.START
        }
        addView(code)
    }

    private fun inlineBold(source: String): CharSequence {
        val matches = BOLD_PATTERN.findAll(source).toList()
        if (matches.isEmpty()) return source
        val output = SpannableStringBuilder()
        var cursor = 0
        matches.forEach { match ->
            output.append(source.substring(cursor, match.range.first))
            val boldStart = output.length
            output.append(match.groupValues[1])
            output.setSpan(
                StyleSpan(Typeface.BOLD),
                boldStart,
                output.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            cursor = match.range.last + 1
        }
        output.append(source.substring(cursor))
        return output
    }

    private fun renderStatus(state: chat.LocalChatUiState) {
        val statusText = when {
            state.isHistoryLoading -> getString(R.string.local_chat_history_loading)
            state.isCancelling -> getString(R.string.local_chat_error_cancelled)
            state.error == LocalChatError.CANCELLED -> getString(R.string.local_chat_error_cancelled)
            state.isGenerating && state.phase == LocalChatGenerationPhase.LOADING_MODEL ->
                getString(R.string.local_chat_loading_model)
            state.isGenerating && state.phase in setOf(
                LocalChatGenerationPhase.PREPARING,
                LocalChatGenerationPhase.PREFILLING
            ) -> getString(R.string.local_chat_processing, state.promptTokenCount ?: 0)
            state.isGenerating -> getString(R.string.local_chat_generating, state.promptTokenCount ?: 0)
            state.promptTokenCount != null -> getString(R.string.local_chat_prompt_count, state.promptTokenCount)
            else -> ""
        }
        statusView.text = statusText
        statusView.visibility = if (statusText.isBlank()) View.GONE else View.VISIBLE
    }

    private fun renderError(
        error: LocalChatError?,
        promptTokenCount: Int?,
        storageCode: DiagnosticCode?,
        storageException: Class<out Throwable>?
    ) {
        val exceptionSuffix = storageException?.simpleName
            ?.let { getString(R.string.diagnostics_failure_class, it) }.orEmpty()
        val message = when (error) {
            null, LocalChatError.CANCELLED -> ""
            LocalChatError.APP_LOCKED -> getString(R.string.local_chat_error_locked)
            LocalChatError.MODEL_NOT_INSTALLED -> getString(R.string.local_chat_error_model_missing)
            LocalChatError.HISTORY_UNAVAILABLE -> getString(
                R.string.local_chat_error_history_reason,
                storageCode?.wireCode ?: DiagnosticCode.CHAT_STORE_WRITE_FAILURE.wireCode,
                exceptionSuffix
            )
            LocalChatError.PROMPT_EXCEEDS_LIMIT -> getString(
                R.string.local_chat_error_prompt_limit,
                promptTokenCount ?: 0
            )
            LocalChatError.LOW_MEMORY -> getString(R.string.local_chat_error_low_memory)
            LocalChatError.GENERATION_FAILED -> getString(R.string.local_chat_error_generation)
        }
        errorView.text = message
        errorView.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun updateSendButton(state: chat.LocalChatUiState) {
        if (!::sendButton.isInitialized) return
        if (state.isGenerating) {
            sendButton.setText(if (state.isCancelling) R.string.local_chat_error_cancelled else R.string.local_chat_stop)
            sendButton.isEnabled = !state.isCancelling
        } else {
            sendButton.setText(R.string.local_chat_send)
            sendButton.isEnabled = !state.isHistoryLoading && input.text.isNotBlank()
        }
    }

    private fun failedRetryLabel(): TextView = TextView(this).apply {
        setText(R.string.local_chat_send_failed)
        textSize = ACTION_TEXT_SP
        setTextColor(getColor(R.color.agent_warning))
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        setPadding(0, dp(4), 0, 0)
    }

    private fun smallActionButton(label: Int, action: () -> Unit): Button = Button(this).apply {
        setText(label)
        textSize = ACTION_TEXT_SP
        minHeight = dp(36)
        minimumHeight = dp(36)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { action() }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.local_chat_copied, Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun jumpToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun isChatAccessAllowed(container: runtime.di.AgentContainer): Boolean = runCatching {
        container.appLockManager.isAppUnlocked() &&
            container.lockdownCondition() == security.LockdownCondition.NONE
    }.getOrDefault(false)

    private data class RenderedMessage(val message: LocalChatMessage, val root: LinearLayout)

    private companion object {
        const val STREAM_REFRESH_MILLIS = 40L
        const val WELCOME_VIEW_COUNT = 2
        const val BODY_TEXT_SP = 16f
        const val CODE_TEXT_SP = 13f
        const val ACTION_TEXT_SP = 12f
        const val USER_BUBBLE_MAX_WIDTH_FRACTION = 0.82f
        val BOLD_PATTERN = Regex("\\*\\*(.+?)\\*\\*")
    }
}
