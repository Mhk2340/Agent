package com.mhk2340.agent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import chat.EncryptedLocalChatHistoryStore
import chat.LocalChatArchive
import chat.LocalChatConversation
import chat.LocalChatMessage
import chat.LocalChatPayloadCipher
import chat.LocalChatRole
import diagnostics.DiagnosticCode
import diagnostics.DiagnosticComponent
import diagnostics.DiagnosticField
import diagnostics.DiagnosticFieldKey
import diagnostics.DiagnosticLevel
import diagnostics.DiagnosticsLog
import memory.AesGcmCipher
import memory.AndroidKeystoreKeyProvider
import runtime.di.AgentContainer
import java.io.File
import java.util.concurrent.Executors

private enum class DiagnosticsSelfCheck {
    KEYSTORE,
    CIPHER,
    HISTORY_FILE,
    MODEL_FILE,
    NATIVE_RUNTIME
}

private data class SelfCheckResult(
    val titleResource: Int,
    val check: DiagnosticsSelfCheck,
    val passed: Boolean,
    val failure: Throwable? = null
)

class DiagnosticsActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var container: AgentContainer
    private lateinit var diagnostics: DiagnosticsLog
    private lateinit var eventsView: TextView
    private lateinit var selfCheckView: TextView
    private lateinit var filter: Spinner
    private var minimumLevel = DiagnosticLevel.DEBUG
    @Volatile private var modelInstalledState: Boolean? = null
    @Volatile private var modelLoadedState: Boolean? = null
    @Volatile private var nativeAvailableState: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureWindowFlag(window)
        setContentView(R.layout.screen_diagnostics)
        val app = application as AgentApplication
        container = app.agentContainer
        diagnostics = app.diagnosticsLog
        eventsView = findViewById(R.id.diagnostics_events)
        selfCheckView = findViewById(R.id.diagnostics_self_check_results)
        filter = findViewById(R.id.diagnostics_level_filter)
        filter.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.diagnostics_level_labels,
            android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        filter.setSelection(0, false)
        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                minimumLevel = when (position) {
                    1 -> DiagnosticLevel.INFO
                    2 -> DiagnosticLevel.WARNING
                    3 -> DiagnosticLevel.ERROR
                    else -> DiagnosticLevel.DEBUG
                }
                renderEvents()
            }
        }
        findViewById<Button>(R.id.diagnostics_copy_report).setOnClickListener { copyReport() }
        findViewById<Button>(R.id.diagnostics_clear).setOnClickListener {
            diagnostics.clear()
            app.clearCrashNotice()
            renderEvents()
            selfCheckView.visibility = View.GONE
        }
        findViewById<Button>(R.id.diagnostics_run_self_check).setOnClickListener { runSelfCheck() }
        findViewById<Button>(R.id.diagnostics_back).setOnClickListener { finish() }
        renderEvents()
        refreshOperationalStatus()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun renderEvents() {
        val reportLines = diagnostics.exportReport(minimumLevel, 100).lines().drop(1)
        val visibleEvents = reportLines.filter(String::isNotBlank)
        eventsView.text = buildString {
            append(statusText())
            append("\n\n")
            if (visibleEvents.isEmpty()) append(getString(R.string.diagnostics_no_events))
            else append(visibleEvents.joinToString("\n"))
        }
    }

    private fun statusText(): String {
        val modelStatus = when {
            modelLoadedState == true -> getString(R.string.diagnostics_model_loaded)
            modelInstalledState == true -> getString(R.string.diagnostics_model_installed)
            modelInstalledState == false -> getString(R.string.diagnostics_model_missing)
            else -> getString(R.string.diagnostics_status_checking)
        }
        val storageStatus = if (container.isLocalChatHistoryEphemeral()) {
            getString(R.string.diagnostics_memory_only)
        } else {
            getString(R.string.diagnostics_saved)
        }
        val lockStatus = if (runCatching { container.appLockManager.isAppUnlocked() }.getOrDefault(false)) {
            getString(R.string.diagnostics_unlocked)
        } else {
            getString(R.string.diagnostics_locked)
        }
        val nativeStatus = getString(
            when (nativeAvailableState) {
                true -> R.string.diagnostics_native_available
                false -> R.string.diagnostics_native_unavailable
                null -> R.string.diagnostics_status_checking
            }
        )
        return buildString {
            append(getString(R.string.diagnostics_lock_state)).append(": ").append(lockStatus)
            append("\n").append(getString(R.string.diagnostics_chat_storage)).append(": ").append(storageStatus)
            append("\n").append(getString(R.string.diagnostics_model_state)).append(": ").append(modelStatus)
            append("\n").append(getString(R.string.diagnostics_status_native)).append(": ").append(nativeStatus)
        }
    }

    private fun refreshOperationalStatus() {
        executor.execute {
            val installed = container.isVerifiedModelAvailable()
            val loaded = container.isModelLoaded()
            val nativeAvailable = container.isNativeInferenceAvailable()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                modelInstalledState = installed
                modelLoadedState = loaded
                nativeAvailableState = nativeAvailable
                renderEvents()
            }
        }
    }

    private fun copyReport() {
        val report = buildString {
            append("ATOM diagnostics v1\n")
            append(statusText())
            append("\n\n")
            append(diagnostics.exportReport(minimumLevel, 100).lines().drop(1).joinToString("\n"))
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostics", report))
        android.widget.Toast.makeText(this, R.string.diagnostics_report_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun runSelfCheck() {
        selfCheckView.visibility = View.VISIBLE
        selfCheckView.setText(R.string.diagnostics_check_running)
        executor.execute {
            val results = listOf(
                executeCheck(R.string.diagnostics_check_keystore, DiagnosticsSelfCheck.KEYSTORE) {
                    AndroidKeystoreKeyProvider().getOrCreateSecretKey(AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS)
                    true
                },
                executeCheck(R.string.diagnostics_check_cipher, DiagnosticsSelfCheck.CIPHER) {
                    val cipher = AesGcmCipher()
                    val plain = byteArrayOf(3, 1, 4, 1, 5, 9)
                    var sealed: ByteArray? = null
                    var opened: ByteArray? = null
                    try {
                        sealed = cipher.encryptBytes(plain)
                        opened = cipher.decryptBytes(sealed)
                        opened.contentEquals(plain)
                    } finally {
                        plain.fill(0)
                        sealed?.fill(0)
                        opened?.fill(0)
                    }
                },
                executeCheck(R.string.diagnostics_check_history, DiagnosticsSelfCheck.HISTORY_FILE, ::checkHistoryFile),
                executeCheck(R.string.diagnostics_check_model, DiagnosticsSelfCheck.MODEL_FILE) {
                    container.isVerifiedModelAvailable()
                },
                executeCheck(R.string.diagnostics_check_native, DiagnosticsSelfCheck.NATIVE_RUNTIME) {
                    container.isNativeInferenceAvailable()
                }
            )
            results.firstOrNull { it.check == DiagnosticsSelfCheck.MODEL_FILE }?.let {
                modelInstalledState = it.passed
            }
            results.firstOrNull { it.check == DiagnosticsSelfCheck.NATIVE_RUNTIME }?.let {
                nativeAvailableState = it.passed
            }
            results.forEach(::recordSelfCheck)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                selfCheckView.text = results.joinToString("\n") { result ->
                    val status = if (result.passed) R.string.diagnostics_check_pass else R.string.diagnostics_check_fail
                    "${getString(result.titleResource)}: ${getString(status)}"
                }
                renderEvents()
            }
        }
    }

    private fun executeCheck(
        titleResource: Int,
        check: DiagnosticsSelfCheck,
        action: () -> Boolean
    ): SelfCheckResult = try {
        SelfCheckResult(titleResource, check, action())
    } catch (failure: Throwable) {
        SelfCheckResult(titleResource, check, false, failure)
    }

    private fun recordSelfCheck(result: SelfCheckResult) {
        if (result.check == DiagnosticsSelfCheck.NATIVE_RUNTIME && !result.passed) {
            container.nativeInferenceLoadFailure()?.let { failure ->
                diagnostics.recordFailure(
                    DiagnosticLevel.ERROR,
                    DiagnosticComponent.NATIVE,
                    DiagnosticCode.NATIVE_LOAD_FAILED,
                    failure,
                    DiagnosticField.flag(DiagnosticFieldKey.SUCCESS, false)
                )
            }
        }
        val fields = arrayOf(
            DiagnosticField.enum(DiagnosticFieldKey.RESULT_TYPE, result.check),
            DiagnosticField.flag(DiagnosticFieldKey.SUCCESS, result.passed)
        )
        if (result.failure != null) {
            diagnostics.recordFailure(
                DiagnosticLevel.WARNING,
                DiagnosticComponent.SELF_CHECK,
                DiagnosticCode.SELF_CHECK_RESULT,
                result.failure,
                *fields
            )
        } else {
            diagnostics.record(
                if (result.passed) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                DiagnosticComponent.SELF_CHECK,
                DiagnosticCode.SELF_CHECK_RESULT,
                *fields
            )
        }
    }

    private fun checkHistoryFile(): Boolean {
        val directory = File(cacheDir, "diagnostics-history-check-${System.nanoTime()}")
        if (!directory.mkdirs()) return false
        val historyFile = File(directory, "history.enc")
        val payloadCipher = AesGcmCipher()
        val store = EncryptedLocalChatHistoryStore(
            historyFile = historyFile,
            cipher = object : LocalChatPayloadCipher {
                override fun encrypt(plainText: ByteArray): ByteArray = payloadCipher.encryptBytes(plainText)
                override fun decrypt(cipherText: ByteArray): ByteArray = payloadCipher.decryptBytes(cipherText)
            },
            isAccessAllowed = { true },
            diagnostics = null
        )
        val conversation = LocalChatConversation(
            id = "diagnostics-check",
            title = "Diagnostics self-check",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
            messages = listOf(LocalChatMessage("diagnostics-check-message", LocalChatRole.USER, "self-check", 1L))
        )
        val archive = LocalChatArchive(activeConversationId = conversation.id, conversations = listOf(conversation))
        return try {
            store.write(archive).getOrThrow()
            val roundTripSucceeded = store.read().getOrThrow() == archive
            val deleteSucceeded = store.clear().isSuccess && !historyFile.exists()
            roundTripSucceeded && deleteSucceeded
        } finally {
            directory.deleteRecursively()
        }
    }
}
