package com.mhk2340.agent

import android.app.Activity
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import inference.LocalModelSelfTestCancellation
import inference.LocalModelSelfTestOutcome
import inference.LocalModelSelfTestRunner
import inference.ModelAssetStore
import runtime.RuntimeStateManager
import security.AppLockStatus
import security.LockdownCondition
import security.LockdownReleaseResult
import security.PanicResetRequestResult
import security.SecurityUiMessage
import security.SecurityUiState
import security.SecurityViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Applies screenshot protection only to non-debug builds. */
internal fun applySecureWindowFlag(window: Window, isDebugBuild: Boolean = BuildConfig.DEBUG) {
    if (!isDebugBuild) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** Native View/XML security entry point. Release screens are protected from screenshots/recents. */
class MainActivity : Activity() {
    private lateinit var viewModel: SecurityViewModel
    private lateinit var screenContainer: FrameLayout
    private lateinit var securityWorker: ExecutorService
    private lateinit var modelImportWorker: ExecutorService
    private lateinit var localModelSelfTestWorker: ExecutorService
    private val actionInFlight = AtomicBoolean(false)
    private val modelImportInFlight = AtomicBoolean(false)
    private val localModelSelfTestInFlight = AtomicBoolean(false)
    private val localModelSelfTestHandler = Handler(Looper.getMainLooper())
    private var activeLocalModelSelfTestCancellation: LocalModelSelfTestCancellation? = null
    private var localModelSelfTestDisplayResult: LocalModelSelfTestOutcome? = null
    private var localModelSelfTestTimeoutRunnable: Runnable? = null
    private var localModelSelfTestEligibilityRunnable: Runnable? = null
    private var modelImportStatusOverride: Int? = null
    private var lastRenderedState: SecurityUiState? = null

    private companion object {
        // PROVISIONAL - pending GEN-PERF-1
        const val MODEL_IMPORT_REQUEST_CODE = 0x4d49
        // PROVISIONAL - pending GEN-PERF-1
        const val LOCAL_MODEL_SELF_TEST_TIMEOUT_MS = 900_000L
        // PROVISIONAL - pending GEN-PERF-1
        const val LOCAL_MODEL_SELF_TEST_ELIGIBILITY_POLL_MS = 250L
    }

    private val timeoutOptionsMs = longArrayOf(
        0L,
        60_000L,
        5L * 60L * 1000L,
        10L * 60L * 1000L,
        30L * 60L * 1000L,
        60L * 60L * 1000L,
        2L * 60L * 60L * 1000L,
        24L * 60L * 60L * 1000L
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureWindowFlag(window)
        setContentView(R.layout.activity_main)

        val app = application as AgentApplication
        val container = app.agentContainer
        viewModel = SecurityViewModel(container.appLockManager, container)
        screenContainer = findViewById(R.id.security_screen_container)
        findViewById<TextView>(R.id.runtime_status).text = getString(
            R.string.runtime_status,
            RuntimeStateManager.currentState.value.toString()
        )
        securityWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "security-ui-worker").apply { isDaemon = true }
        }
        modelImportWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "model-import-worker").apply { isDaemon = true }
        }
        localModelSelfTestWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "local-model-self-test-worker").apply { isDaemon = true }
        }
        dispatch { it.state() }
    }

    override fun onResume() {
        super.onResume()
        if (::securityWorker.isInitialized && ::viewModel.isInitialized) {
            dispatch { it.state() }
        }
    }

    override fun onStop() {
        cancelLocalModelSelfTest()
        clearSensitiveInputs()
        super.onStop()
    }

    override fun onDestroy() {
        cancelLocalModelSelfTest()
        removeLocalModelSelfTestWatchdogs()
        clearSensitiveInputs()
        if (::securityWorker.isInitialized) securityWorker.shutdown()
        if (::modelImportWorker.isInitialized) modelImportWorker.shutdownNow()
        if (::localModelSelfTestWorker.isInitialized) localModelSelfTestWorker.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (actionInFlight.get()) {
            super.onBackPressed()
            return
        }
        when (lastRenderedState) {
            is SecurityUiState.Settings,
            is SecurityUiState.PanicResetConfirmation,
            is SecurityUiState.Recovery -> dispatch { it.goHome() }
            is SecurityUiState.ResetOutcome,
            is SecurityUiState.ReleaseOutcome -> dispatch { it.dismissOutcome() }
            is SecurityUiState.Home -> {
                viewModel.lockNow()
                finish()
            }
            else -> super.onBackPressed()
        }
    }

    private fun dispatch(action: (SecurityViewModel) -> SecurityUiState): Boolean {
        if (!::viewModel.isInitialized || !actionInFlight.compareAndSet(false, true)) return false
        return try {
            securityWorker.execute {
                val nextState = try {
                    action(viewModel)
                } catch (_: Exception) {
                    runCatching { viewModel.state() }.getOrNull()
                }
                runOnUiThread {
                    actionInFlight.set(false)
                    if (!isFinishing && !isDestroyed) {
                        if (nextState != null) render(nextState)
                        else Toast.makeText(this, R.string.release_result_unexpected, Toast.LENGTH_LONG).show()
                    }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            actionInFlight.set(false)
            false
        }
    }

    private fun render(state: SecurityUiState) {
        if (state !is SecurityUiState.Settings && localModelSelfTestInFlight.get()) {
            cancelLocalModelSelfTest()
        }
        lastRenderedState = state
        val screen = when (state) {
            is SecurityUiState.PinSetup -> inflateScreen(R.layout.screen_pin_setup)
            is SecurityUiState.PinEntry -> inflateScreen(R.layout.screen_pin_entry)
            SecurityUiState.AppLockIntegrityFailure -> inflateScreen(R.layout.screen_integrity_failure)
            is SecurityUiState.Home -> inflateScreen(R.layout.screen_home)
            is SecurityUiState.Settings -> inflateScreen(R.layout.screen_settings)
            is SecurityUiState.PanicResetConfirmation -> inflateScreen(R.layout.screen_panic_reset)
            is SecurityUiState.Recovery -> inflateScreen(R.layout.screen_recovery)
            is SecurityUiState.ResetOutcome -> inflateScreen(R.layout.screen_reset_outcome)
            is SecurityUiState.ReleaseOutcome -> inflateScreen(R.layout.screen_release_outcome)
        }

        when (state) {
            is SecurityUiState.PinSetup -> renderPinSetup(screen, state)
            is SecurityUiState.PinEntry -> renderPinEntry(screen, state)
            SecurityUiState.AppLockIntegrityFailure -> renderIntegrityFailure(screen)
            is SecurityUiState.Home -> renderHome(screen, state)
            is SecurityUiState.Settings -> renderSettings(screen, state)
            is SecurityUiState.PanicResetConfirmation -> renderPanicReset(screen, state)
            is SecurityUiState.Recovery -> renderRecovery(screen, state)
            is SecurityUiState.ResetOutcome -> renderResetOutcome(screen, state.result)
            is SecurityUiState.ReleaseOutcome -> renderReleaseOutcome(screen, state.result)
        }
    }

    private fun inflateScreen(layoutId: Int): View {
        screenContainer.removeAllViews()
        val screen = layoutInflater.inflate(layoutId, screenContainer, false)
        screenContainer.addView(screen)
        return screen
    }

    private fun renderPinSetup(screen: View, state: SecurityUiState.PinSetup) {
        showMessage(screen.findViewById(R.id.screen_message), state.message)
        screen.findViewById<View>(R.id.primary_button).setOnClickListener {
            val pin = screen.findViewById<EditText>(R.id.pin_input).takePinCharsAndClear()
            val confirmation = screen.findViewById<EditText>(R.id.pin_confirmation).takePinCharsAndClear()
            if (!dispatch { it.configurePin(pin, confirmation) }) {
                pin.fill('\u0000')
                confirmation.fill('\u0000')
            }
        }
    }

    private fun renderPinEntry(screen: View, state: SecurityUiState.PinEntry) {
        showMessage(screen.findViewById(R.id.screen_message), state.message)
        screen.findViewById<View>(R.id.primary_button).setOnClickListener {
            val pin = screen.findViewById<EditText>(R.id.pin_input).takePinCharsAndClear()
            if (!dispatch { it.unlock(pin) }) pin.fill('\u0000')
        }
        val biometricButton = screen.findViewById<View>(R.id.biometric_button)
        biometricButton.visibility = if (
            state.status != AppLockStatus.LOCKED_OUT &&
            (application as AgentApplication).agentContainer.appLockManager.isBiometricEnabled()
        ) View.VISIBLE else View.GONE
        biometricButton.setOnClickListener { showBiometricPrompt() }
    }

    private fun renderIntegrityFailure(screen: View) {
        screen.findViewById<View>(R.id.retry_button).setOnClickListener {
            dispatch { it.state() }
        }
    }

    private fun renderHome(screen: View, state: SecurityUiState.Home) {
        val hasLockdown = state.lockdown != LockdownCondition.NONE
        screen.findViewById<TextView>(R.id.home_lockdown_status).setText(
            if (hasLockdown) R.string.status_lockdown else R.string.status_ready
        )
        val conditionView = screen.findViewById<TextView>(R.id.home_lockdown_condition)
        if (hasLockdown) {
            conditionView.visibility = View.VISIBLE
            conditionView.text = getString(lockdownLabel(state.lockdown))
        } else {
            conditionView.visibility = View.GONE
        }
        screen.findViewById<View>(R.id.settings_button).setOnClickListener {
            dispatch { it.openSettings() }
        }
        screen.findViewById<View>(R.id.recovery_button).apply {
            visibility = if (hasLockdown) View.VISIBLE else View.GONE
            setOnClickListener { dispatch { it.openRecovery() } }
        }
        screen.findViewById<View>(R.id.lock_now_button).setOnClickListener {
            dispatch { it.lockNow() }
        }
    }

    private fun renderSettings(screen: View, state: SecurityUiState.Settings) {
        showMessage(screen.findViewById(R.id.screen_message), state.message)
        val container = (application as AgentApplication).agentContainer
        if (modelImportStatusOverride == R.string.model_import_auth_required && container.appLockManager.isAppUnlocked()) {
            modelImportStatusOverride = null
        }
        val modelStore = container.localModelAssetStore
        val modelStatus = modelImportStatusOverride ?: when {
            modelImportInFlight.get() -> R.string.model_import_status_importing
            modelStore.isInstalled() -> R.string.model_import_status_ready
            else -> R.string.model_import_status_missing
        }
        screen.findViewById<TextView>(R.id.model_import_status).setText(modelStatus)
        screen.findViewById<View>(R.id.settings_model_import_button).apply {
            isEnabled = !modelImportInFlight.get() && !localModelSelfTestInFlight.get()
            setOnClickListener { openModelDocumentPicker() }
        }
        screen.findViewById<View>(R.id.settings_local_model_self_test_button).apply {
            isEnabled = isLocalModelSelfTestEligible(container) && !localModelSelfTestInFlight.get() &&
                !modelImportInFlight.get()
            setOnClickListener { startLocalModelSelfTest() }
        }
        renderLocalModelSelfTestResult(screen)

        val geminiSaved = security.ProviderCredentialCatalog.GEMINI_FREE in state.configuredProviderIds
        val groqSaved = security.ProviderCredentialCatalog.GROQ_FREE in state.configuredProviderIds
        screen.findViewById<TextView>(R.id.gemini_status).setText(
            if (geminiSaved) R.string.credential_saved else R.string.credential_not_saved
        )
        screen.findViewById<TextView>(R.id.groq_status).setText(
            if (groqSaved) R.string.credential_saved else R.string.credential_not_saved
        )
        screen.findViewById<View>(R.id.gemini_remove_button).apply {
            isEnabled = geminiSaved
            setOnClickListener { dispatch { it.removeProviderKey(security.ProviderCredentialCatalog.GEMINI_FREE) } }
        }
        screen.findViewById<View>(R.id.groq_remove_button).apply {
            isEnabled = groqSaved
            setOnClickListener { dispatch { it.removeProviderKey(security.ProviderCredentialCatalog.GROQ_FREE) } }
        }
        attachCredentialSave(
            screen,
            inputId = R.id.gemini_key_input,
            buttonId = R.id.gemini_save_button,
            providerName = security.ProviderCredentialCatalog.GEMINI_FREE
        )
        attachCredentialSave(
            screen,
            inputId = R.id.groq_key_input,
            buttonId = R.id.groq_save_button,
            providerName = security.ProviderCredentialCatalog.GROQ_FREE
        )

        val timeoutSpinner = screen.findViewById<Spinner>(R.id.timeout_spinner)
        val timeoutIndex = timeoutOptionsMs.indexOf(state.backgroundTimeoutMs).let { if (it < 0) 1 else it }
        timeoutSpinner.setSelection(timeoutIndex)
        screen.findViewById<View>(R.id.timeout_save_button).setOnClickListener {
            val selected = timeoutSpinner.selectedItemPosition.coerceIn(timeoutOptionsMs.indices)
            dispatch { it.setBackgroundTimeout(timeoutOptionsMs[selected]) }
        }
        val biometricSwitch = screen.findViewById<Switch>(R.id.biometric_switch)
        biometricSwitch.isChecked = state.biometricEnabled
        screen.findViewById<View>(R.id.biometric_save_button).setOnClickListener {
            val enabled = biometricSwitch.isChecked
            dispatch { it.setBiometricEnabled(enabled) }
        }
        screen.findViewById<View>(R.id.settings_panic_reset_button).setOnClickListener {
            cancelLocalModelSelfTest()
            dispatch { it.openPanicReset() }
        }
        screen.findViewById<View>(R.id.settings_recovery_button).setOnClickListener {
            cancelLocalModelSelfTest()
            dispatch { it.openRecovery() }
        }
        screen.findViewById<View>(R.id.settings_back_button).setOnClickListener {
            cancelLocalModelSelfTest()
            dispatch { it.goHome() }
        }
        screen.findViewById<View>(R.id.settings_lock_button).setOnClickListener {
            cancelLocalModelSelfTest()
            dispatch { it.lockNow() }
        }
    }

    private fun openModelDocumentPicker() {
        if (localModelSelfTestInFlight.get()) return
        val container = (application as AgentApplication).agentContainer
        if (!container.appLockManager.isAppUnlocked()) {
            modelImportStatusOverride = R.string.model_import_auth_required
            updateModelImportStatus(modelImportStatusOverride!!)
            dispatch { it.state() }
            return
        }

        modelImportStatusOverride = null
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(picker, MODEL_IMPORT_REQUEST_CODE)
        } catch (_: Exception) {
            modelImportStatusOverride = R.string.model_import_picker_unavailable
            updateModelImportStatus(modelImportStatusOverride!!)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != MODEL_IMPORT_REQUEST_CODE || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val container = (application as AgentApplication).agentContainer
        if (!container.appLockManager.isAppUnlocked()) {
            modelImportStatusOverride = R.string.model_import_auth_required
            dispatch { it.state() }
            return
        }
        if (!modelImportInFlight.compareAndSet(false, true)) return

        modelImportStatusOverride = R.string.model_import_status_importing
        updateModelImportStatus(modelImportStatusOverride!!)
        try {
            modelImportWorker.execute {
                val result = try {
                    contentResolver.openInputStream(uri)?.use(container.localModelAssetStore::importModel)
                        ?: ModelAssetStore.ImportResult.Failed("The selected document could not be opened")
                } catch (error: Exception) {
                    ModelAssetStore.ImportResult.Failed(error.message ?: error.javaClass.simpleName)
                }
                runOnUiThread {
                    modelImportInFlight.set(false)
                    val status = when (result) {
                        is ModelAssetStore.ImportResult.Installed,
                        ModelAssetStore.ImportResult.AlreadyInstalled -> R.string.model_import_status_ready
                        is ModelAssetStore.ImportResult.IntegrityFailure -> R.string.model_import_status_mismatch
                        is ModelAssetStore.ImportResult.Failed -> R.string.model_import_status_failed
                    }
                    modelImportStatusOverride = status
                    if (!isFinishing && !isDestroyed && lastRenderedState is SecurityUiState.Settings &&
                        container.appLockManager.isAppUnlocked()
                    ) {
                        updateModelImportStatus(status)
                        if (result is ModelAssetStore.ImportResult.IntegrityFailure ||
                            result is ModelAssetStore.ImportResult.Failed
                        ) {
                            Toast.makeText(this, status, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            modelImportInFlight.set(false)
            modelImportStatusOverride = R.string.model_import_status_failed
            updateModelImportStatus(modelImportStatusOverride!!)
        }
    }

    private fun updateModelImportStatus(resourceId: Int) {
        if (!::screenContainer.isInitialized) return
        screenContainer.findViewById<TextView>(R.id.model_import_status)?.setText(resourceId)
        screenContainer.findViewById<View>(R.id.settings_model_import_button)?.isEnabled =
            !modelImportInFlight.get() && !localModelSelfTestInFlight.get()
        updateLocalModelSelfTestButton()
    }

    private fun startLocalModelSelfTest() {
        val container = (application as AgentApplication).agentContainer
        if (!isLocalModelSelfTestEligible(container) ||
            !localModelSelfTestInFlight.compareAndSet(false, true)
        ) return

        val cancellation = LocalModelSelfTestCancellation()
        activeLocalModelSelfTestCancellation = cancellation
        localModelSelfTestDisplayResult = null
        renderLocalModelSelfTestResult(screenContainer)
        screenContainer.findViewById<View>(R.id.settings_model_import_button)?.isEnabled = false
        updateLocalModelSelfTestButton()

        val timeout = Runnable {
            if (activeLocalModelSelfTestCancellation === cancellation) {
                cancellation.cancel()
                removeLocalModelSelfTestWatchdogs()
                localModelSelfTestDisplayResult = LocalModelSelfTestOutcome.Failure
                if (lastRenderedState is SecurityUiState.Settings && !isFinishing && !isDestroyed) {
                    renderLocalModelSelfTestResult(screenContainer)
                    updateLocalModelSelfTestButton()
                }
            }
        }
        localModelSelfTestTimeoutRunnable = timeout
        localModelSelfTestHandler.postDelayed(timeout, LOCAL_MODEL_SELF_TEST_TIMEOUT_MS)

        val eligibilityWatcher = object : Runnable {
            override fun run() {
                if (activeLocalModelSelfTestCancellation !== cancellation || cancellation.isCancelled()) return
                if (!isLocalModelSelfTestEligible(container)) {
                    cancelLocalModelSelfTest()
                    return
                }
                localModelSelfTestHandler.postDelayed(this, LOCAL_MODEL_SELF_TEST_ELIGIBILITY_POLL_MS)
            }
        }
        localModelSelfTestEligibilityRunnable = eligibilityWatcher
        localModelSelfTestHandler.postDelayed(eligibilityWatcher, LOCAL_MODEL_SELF_TEST_ELIGIBILITY_POLL_MS)

        try {
            localModelSelfTestWorker.execute {
                val outcome = LocalModelSelfTestRunner(
                    sessionFactory = { currentCancellation, nThreads ->
                        container.createLocalModelSelfTestSession(currentCancellation, nThreads)
                    },
                    isEligible = { isLocalModelSelfTestEligible(container) }
                ).run(cancellation)
                runOnUiThread { completeLocalModelSelfTest(container, cancellation, outcome) }
            }
        } catch (_: RejectedExecutionException) {
            cancellation.cancel()
            completeLocalModelSelfTest(container, cancellation, LocalModelSelfTestOutcome.Failure)
        }
    }

    private fun completeLocalModelSelfTest(
        container: runtime.di.AgentContainer,
        cancellation: LocalModelSelfTestCancellation,
        outcome: LocalModelSelfTestOutcome
    ) {
        if (activeLocalModelSelfTestCancellation !== cancellation) return
        removeLocalModelSelfTestWatchdogs()
        activeLocalModelSelfTestCancellation = null
        localModelSelfTestInFlight.set(false)
        localModelSelfTestDisplayResult = if (
            cancellation.isCancelled() || !isLocalModelSelfTestEligible(container)
        ) LocalModelSelfTestOutcome.Failure else outcome
        if (lastRenderedState is SecurityUiState.Settings && !isFinishing && !isDestroyed) {
            renderLocalModelSelfTestResult(screenContainer)
            updateLocalModelSelfTestButton()
            screenContainer.findViewById<View>(R.id.settings_model_import_button)?.isEnabled =
                !modelImportInFlight.get()
        }
    }

    private fun cancelLocalModelSelfTest() {
        val cancellation = activeLocalModelSelfTestCancellation ?: return
        cancellation.cancel()
        removeLocalModelSelfTestWatchdogs()
        localModelSelfTestDisplayResult = LocalModelSelfTestOutcome.Failure
        if (lastRenderedState is SecurityUiState.Settings && ::screenContainer.isInitialized &&
            !isFinishing && !isDestroyed
        ) {
            renderLocalModelSelfTestResult(screenContainer)
            updateLocalModelSelfTestButton()
        }
    }

    private fun removeLocalModelSelfTestWatchdogs() {
        localModelSelfTestTimeoutRunnable?.let(localModelSelfTestHandler::removeCallbacks)
        localModelSelfTestEligibilityRunnable?.let(localModelSelfTestHandler::removeCallbacks)
        localModelSelfTestTimeoutRunnable = null
        localModelSelfTestEligibilityRunnable = null
    }

    private fun isLocalModelSelfTestEligible(container: runtime.di.AgentContainer): Boolean = runCatching {
        container.appLockManager.isAppUnlocked() &&
            container.lockdownCondition() == LockdownCondition.NONE &&
            container.localModelAssetStore.isInstalled()
    }.getOrDefault(false)

    private fun updateLocalModelSelfTestButton() {
        if (!::screenContainer.isInitialized) return
        val container = (application as AgentApplication).agentContainer
        screenContainer.findViewById<View>(R.id.settings_local_model_self_test_button)?.isEnabled =
            isLocalModelSelfTestEligible(container) && !localModelSelfTestInFlight.get() &&
                !modelImportInFlight.get()
    }

    private fun renderLocalModelSelfTestResult(screen: View) {
        val status = screen.findViewById<TextView>(R.id.local_model_self_test_status)
        val metricsView = screen.findViewById<TextView>(R.id.local_model_self_test_metrics)
        when (val result = localModelSelfTestDisplayResult) {
            null -> {
                status.visibility = View.GONE
                metricsView.visibility = View.GONE
                metricsView.text = ""
            }
            LocalModelSelfTestOutcome.Failure -> {
                status.setText(R.string.local_model_self_test_failed)
                status.visibility = View.VISIBLE
                metricsView.visibility = View.GONE
                metricsView.text = ""
            }
            is LocalModelSelfTestOutcome.Success -> {
                val metrics = result.metrics
                status.setText(R.string.local_model_self_test_succeeded)
                status.visibility = View.VISIBLE
                val unavailable = getString(R.string.local_model_self_test_value_unavailable)
                val caseLines = metrics.cases.flatMap { case ->
                    val allowedCores = case.allowedCoreIds?.joinToString(prefix = "[", postfix = "]")
                        ?: unavailable
                    val promptTokensLine = case.promptTokenCount?.let {
                        getString(R.string.local_model_self_test_prompt_tokens, it)
                    } ?: getString(R.string.local_model_self_test_prompt_tokens_unavailable)
                    val firstTokenLine = case.firstTokenTimeMs?.let {
                        getString(R.string.local_model_self_test_first_token_time, it)
                    } ?: getString(R.string.local_model_self_test_first_token_time_unavailable)
                    val tokenRateLine = case.tokensPerSecond?.let {
                        getString(R.string.local_model_self_test_tokens_per_second, it)
                    } ?: getString(R.string.local_model_self_test_tokens_per_second_unavailable)
                    listOf(
                        getString(
                            R.string.local_model_self_test_case_header,
                            case.configuration.name,
                            case.runNumber
                        ),
                        getString(R.string.local_model_self_test_n_threads, case.nThreads),
                        getString(R.string.local_model_self_test_allowed_cores, allowedCores),
                        promptTokensLine,
                        firstTokenLine,
                        tokenRateLine,
                        getString(R.string.local_model_self_test_run_status, case.status.displayLabel)
                    )
                }
                metricsView.text = (caseLines + getString(
                    R.string.local_model_self_test_system_info,
                    metrics.systemInfo
                )).joinToString("\n")
                metricsView.visibility = View.VISIBLE
            }
        }
    }

    private fun attachCredentialSave(screen: View, inputId: Int, buttonId: Int, providerName: String) {
        val input = screen.findViewById<EditText>(inputId)
        screen.findViewById<View>(buttonId).setOnClickListener {
            val key = input.takeCharsAndClear()
            if (!dispatch { it.saveProviderKey(providerName, key) }) key.fill('\u0000')
        }
    }

    private fun renderPanicReset(screen: View, state: SecurityUiState.PanicResetConfirmation) {
        showMessage(screen.findViewById(R.id.screen_message), state.message)
        screen.findViewById<View>(R.id.primary_button).setOnClickListener {
            val phrase = screen.findViewById<EditText>(R.id.confirmation_input).text.toString()
            screen.findViewById<EditText>(R.id.confirmation_input).text.clear()
            dispatch { it.beginPanicReset(phrase) }
        }
        screen.findViewById<View>(R.id.back_button).setOnClickListener {
            dispatch { it.goHome() }
        }
    }

    private fun renderRecovery(screen: View, state: SecurityUiState.Recovery) {
        screen.findViewById<TextView>(R.id.recovery_status).text = getString(lockdownLabel(state.condition))
        val phraseHint = screen.findViewById<TextView>(R.id.recovery_phrase_hint)
        phraseHint.setText(
            if (state.condition == LockdownCondition.INTEGRITY_FAILURE) {
                R.string.recovery_reset_phrase
            } else {
                R.string.recovery_release_phrase
            }
        )
        showMessage(screen.findViewById(R.id.screen_message), state.message)
        screen.findViewById<View>(R.id.primary_button).setOnClickListener {
            val phrase = screen.findViewById<EditText>(R.id.confirmation_input).text.toString()
            screen.findViewById<EditText>(R.id.confirmation_input).text.clear()
            dispatch { it.releaseLockdown(phrase) }
        }
        screen.findViewById<View>(R.id.back_button).setOnClickListener {
            dispatch { it.goHome() }
        }
    }

    private fun renderResetOutcome(screen: View, result: PanicResetRequestResult) {
        val title = screen.findViewById<TextView>(R.id.outcome_title)
        val details = screen.findViewById<TextView>(R.id.outcome_details)
        when (result) {
            is PanicResetRequestResult.Completed -> {
                title.text = getString(R.string.reset_complete, result.epoch)
                details.setText(R.string.status_lockdown)
            }
            is PanicResetRequestResult.Incomplete -> {
                title.text = getString(R.string.reset_incomplete, result.failedStageIds.joinToString(", "))
                details.setText(R.string.status_lockdown)
            }
            PanicResetRequestResult.AuthenticationRequired -> {
                title.setText(R.string.reset_auth_required)
                details.setText(R.string.status_lockdown)
            }
            PanicResetRequestResult.ConfirmationRequired -> {
                title.setText(R.string.reset_result_unexpected)
                details.setText(R.string.status_lockdown)
            }
        }
        screen.findViewById<View>(R.id.done_button).setOnClickListener {
            dispatch { it.dismissOutcome() }
        }
    }

    private fun renderReleaseOutcome(screen: View, result: LockdownReleaseResult) {
        val title = screen.findViewById<TextView>(R.id.outcome_title)
        val details = screen.findViewById<TextView>(R.id.outcome_details)
        when (result) {
            is LockdownReleaseResult.Released -> {
                if (result.securityStateReset) {
                    title.setText(R.string.security_state_rebuilt)
                } else {
                    title.text = getString(R.string.release_complete, result.epoch)
                }
                details.setText(R.string.recovery_description)
            }
            is LockdownReleaseResult.Failed -> {
                title.text = getString(R.string.release_failed, result.stageId)
                details.setText(R.string.status_lockdown)
            }
            LockdownReleaseResult.AuthenticationRequired -> {
                title.setText(R.string.release_auth_required)
                details.setText(R.string.status_lockdown)
            }
            LockdownReleaseResult.ConfirmationRequired -> {
                title.setText(R.string.release_result_unexpected)
                details.setText(R.string.status_lockdown)
            }
            LockdownReleaseResult.NotLocked -> {
                title.setText(R.string.release_not_locked)
                details.setText(R.string.lockdown_none)
            }
        }
        screen.findViewById<View>(R.id.done_button).setOnClickListener {
            dispatch { it.dismissOutcome() }
        }
    }

    private fun showMessage(view: TextView, message: SecurityUiMessage?) {
        val resourceId = message?.let(::messageResource)
        if (resourceId == null) {
            view.visibility = View.GONE
        } else {
            view.setText(resourceId)
            view.visibility = View.VISIBLE
        }
    }

    private fun messageResource(message: SecurityUiMessage): Int = when (message) {
        SecurityUiMessage.PIN_INVALID -> R.string.message_pin_invalid
        SecurityUiMessage.PIN_MISMATCH -> R.string.message_pin_mismatch
        SecurityUiMessage.PIN_INCORRECT -> R.string.message_pin_incorrect
        SecurityUiMessage.PIN_LOCKED_OUT -> R.string.message_pin_locked
        SecurityUiMessage.PIN_STORAGE_FAILURE -> R.string.message_pin_storage
        SecurityUiMessage.API_KEY_SAVED -> R.string.message_key_saved
        SecurityUiMessage.API_KEY_REMOVED -> R.string.message_key_removed
        SecurityUiMessage.API_KEY_EMPTY -> R.string.message_key_empty
        SecurityUiMessage.API_KEY_UNKNOWN_PROVIDER -> R.string.message_key_unknown
        SecurityUiMessage.API_KEY_STORAGE_FAILURE -> R.string.message_key_storage
        SecurityUiMessage.AUTHENTICATION_REQUIRED -> R.string.message_auth_required
        SecurityUiMessage.LOCKDOWN_ACTIVE -> R.string.message_lockdown_active
        SecurityUiMessage.TIMEOUT_SAVED -> R.string.message_timeout_saved
        SecurityUiMessage.BIOMETRIC_SAVED -> R.string.message_biometric_saved
        SecurityUiMessage.BIOMETRIC_FAILED -> R.string.message_biometric_failed
        SecurityUiMessage.RESET_CONFIRMATION_REQUIRED -> R.string.message_reset_confirmation
        SecurityUiMessage.RELEASE_CONFIRMATION_REQUIRED -> R.string.message_release_confirmation
    }

    private fun lockdownLabel(condition: LockdownCondition): Int = when (condition) {
        LockdownCondition.NONE -> R.string.lockdown_none
        LockdownCondition.PANIC_RESET_LATCHED -> R.string.lockdown_panic
        LockdownCondition.INTEGRITY_FAILURE -> R.string.lockdown_integrity
        LockdownCondition.MODE_LOCKDOWN -> R.string.lockdown_mode
    }

    private fun showBiometricPrompt() {
        try {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.biometric_prompt_title))
                .setDescription(getString(R.string.biometric_prompt_description))
                .setNegativeButton(getString(R.string.biometric_negative_button), mainExecutor) { _, _ -> }
                .build()
            prompt.authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        dispatch { it.onBiometricAuthenticated() }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this@MainActivity, R.string.biometric_error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.biometric_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun clearSensitiveInputs() {
        if (!::screenContainer.isInitialized) return
        listOf(
            R.id.pin_input,
            R.id.pin_confirmation,
            R.id.gemini_key_input,
            R.id.groq_key_input
        ).forEach { id ->
            screenContainer.findViewById<EditText>(id)?.text?.clear()
        }
    }

    private fun EditText.takeCharsAndClear(): CharArray {
        val result = CharArray(text.length) { index -> text[index] }
        text.clear()
        return result
    }

    private fun EditText.takePinCharsAndClear(): CharArray {
        val result = CharArray(text.length) { index ->
            when (val char = text[index]) {
                in '\u06F0'..'\u06F9' -> ('0'.code + char.code - '\u06F0'.code).toChar()
                in '\u0660'..'\u0669' -> ('0'.code + char.code - '\u0660'.code).toChar()
                else -> char
            }
        }
        text.clear()
        return result
    }
}
