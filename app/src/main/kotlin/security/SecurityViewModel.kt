package security

/** Application actions needed by the security UI. Implemented by the production AgentContainer. */
interface SecurityUiActions {
    fun configuredProviderCredentialIds(): Set<String>
    fun storeProviderCredential(providerName: String, apiKey: CharArray): ProviderKeyOperationResult
    fun removeProviderCredential(providerName: String): ProviderKeyOperationResult
    fun lockdownCondition(): LockdownCondition
    fun requestPanicReset(confirmation: String): PanicResetRequestResult
    fun releaseSecurityLockdown(confirmation: String): LockdownReleaseResult
}

enum class SecurityUiMessage {
    PIN_INVALID,
    PIN_MISMATCH,
    PIN_INCORRECT,
    PIN_LOCKED_OUT,
    PIN_STORAGE_FAILURE,
    API_KEY_SAVED,
    API_KEY_REMOVED,
    API_KEY_EMPTY,
    API_KEY_UNKNOWN_PROVIDER,
    API_KEY_STORAGE_FAILURE,
    AUTHENTICATION_REQUIRED,
    LOCKDOWN_ACTIVE,
    TIMEOUT_SAVED,
    BIOMETRIC_SAVED,
    BIOMETRIC_FAILED,
    RESET_CONFIRMATION_REQUIRED,
    RELEASE_CONFIRMATION_REQUIRED
}

sealed class SecurityUiState {
    data class PinSetup(val message: SecurityUiMessage? = null) : SecurityUiState()
    data class PinEntry(val status: AppLockStatus, val message: SecurityUiMessage? = null) : SecurityUiState()
    object AppLockIntegrityFailure : SecurityUiState()
    data class Home(val lockdown: LockdownCondition) : SecurityUiState()
    data class Settings(
        val configuredProviderIds: Set<String>,
        val backgroundTimeoutMs: Long,
        val biometricEnabled: Boolean,
        val message: SecurityUiMessage? = null
    ) : SecurityUiState()
    data class PanicResetConfirmation(val message: SecurityUiMessage? = null) : SecurityUiState()
    data class Recovery(val condition: LockdownCondition, val message: SecurityUiMessage? = null) : SecurityUiState()
    data class ResetOutcome(val result: PanicResetRequestResult) : SecurityUiState()
    data class ReleaseOutcome(val result: LockdownReleaseResult, val condition: LockdownCondition) : SecurityUiState()
}

/**
 * Lifecycle-independent ViewModel for security flows. It contains only Kotlin/domain state, so its
 * transitions and confirmation rules can be tested without Activity, Views, or Robolectric.
 */
class SecurityViewModel(
    private val appLockManager: AppLockManager,
    private val actions: SecurityUiActions
) {
    private enum class Page { HOME, SETTINGS, PANIC_RESET, RECOVERY }

    private var page = Page.HOME
    private var message: SecurityUiMessage? = null
    private var pendingResetResult: PanicResetRequestResult? = null
    private var pendingReleaseResult: LockdownReleaseResult? = null
    private var pendingReleaseCondition: LockdownCondition = LockdownCondition.NONE

    fun state(): SecurityUiState {
        pendingResetResult?.let { return SecurityUiState.ResetOutcome(it) }
        pendingReleaseResult?.let { return SecurityUiState.ReleaseOutcome(it, pendingReleaseCondition) }
        return when (val lockStatus = appLockManager.status()) {
            AppLockStatus.PIN_SETUP_REQUIRED -> SecurityUiState.PinSetup(message)
            AppLockStatus.INTEGRITY_FAILURE -> SecurityUiState.AppLockIntegrityFailure
            AppLockStatus.LOCKED, AppLockStatus.LOCKED_OUT -> SecurityUiState.PinEntry(lockStatus, message)
            AppLockStatus.UNLOCKED -> stateForUnlockedPage()
        }
    }

    fun configurePin(pin: CharArray, confirmation: CharArray): SecurityUiState {
        message = when (appLockManager.configurePin(pin, confirmation)) {
            AppLockOperationResult.Success -> null
            AppLockOperationResult.PinMismatch -> SecurityUiMessage.PIN_MISMATCH
            AppLockOperationResult.InvalidPinFormat -> SecurityUiMessage.PIN_INVALID
            AppLockOperationResult.IntegrityFailure -> return SecurityUiState.AppLockIntegrityFailure
            AppLockOperationResult.StorageFailure -> SecurityUiMessage.PIN_STORAGE_FAILURE
            else -> SecurityUiMessage.PIN_STORAGE_FAILURE
        }
        if (message == null) page = Page.HOME
        return state()
    }

    fun unlock(pin: CharArray): SecurityUiState {
        message = when (appLockManager.unlockWithPin(pin)) {
            AppLockOperationResult.Success -> null
            AppLockOperationResult.IncorrectPin -> SecurityUiMessage.PIN_INCORRECT
            is AppLockOperationResult.LockedOut -> SecurityUiMessage.PIN_LOCKED_OUT
            AppLockOperationResult.InvalidPinFormat -> SecurityUiMessage.PIN_INVALID
            AppLockOperationResult.SetupRequired -> return SecurityUiState.PinSetup()
            AppLockOperationResult.IntegrityFailure -> return SecurityUiState.AppLockIntegrityFailure
            AppLockOperationResult.StorageFailure -> SecurityUiMessage.PIN_STORAGE_FAILURE
            else -> SecurityUiMessage.PIN_STORAGE_FAILURE
        }
        if (message == null) page = Page.HOME
        return state()
    }

    fun onBiometricAuthenticated(): SecurityUiState {
        message = if (appLockManager.unlockAfterBiometric()) null else SecurityUiMessage.BIOMETRIC_FAILED
        if (message == null) page = Page.HOME
        return state()
    }

    fun lockNow(): SecurityUiState {
        appLockManager.lock()
        page = Page.HOME
        message = null
        return state()
    }

    fun openSettings(): SecurityUiState {
        if (!appLockManager.isAppUnlocked()) return state()
        page = Page.SETTINGS
        message = null
        return state()
    }

    fun openPanicReset(): SecurityUiState {
        if (!appLockManager.isAppUnlocked()) return state()
        page = Page.PANIC_RESET
        message = null
        return state()
    }

    fun openRecovery(): SecurityUiState {
        if (!appLockManager.isAppUnlocked()) return state()
        page = Page.RECOVERY
        message = null
        return state()
    }

    fun goHome(): SecurityUiState {
        page = Page.HOME
        message = null
        return state()
    }

    fun saveProviderKey(providerName: String, apiKey: CharArray): SecurityUiState {
        val result = try {
            actions.storeProviderCredential(providerName, apiKey)
        } catch (_: Exception) {
            ProviderKeyOperationResult.STORAGE_FAILURE
        } finally {
            apiKey.fill('\u0000')
        }
        message = when (result) {
            ProviderKeyOperationResult.SAVED -> SecurityUiMessage.API_KEY_SAVED
            ProviderKeyOperationResult.EMPTY_KEY -> SecurityUiMessage.API_KEY_EMPTY
            ProviderKeyOperationResult.UNKNOWN_PROVIDER -> SecurityUiMessage.API_KEY_UNKNOWN_PROVIDER
            ProviderKeyOperationResult.AUTHENTICATION_REQUIRED -> SecurityUiMessage.AUTHENTICATION_REQUIRED
            ProviderKeyOperationResult.LOCKDOWN_ACTIVE -> SecurityUiMessage.LOCKDOWN_ACTIVE
            ProviderKeyOperationResult.REMOVED -> SecurityUiMessage.API_KEY_REMOVED
            ProviderKeyOperationResult.STORAGE_FAILURE -> SecurityUiMessage.API_KEY_STORAGE_FAILURE
        }
        return settingsState()
    }

    fun removeProviderKey(providerName: String): SecurityUiState {
        val result = actions.removeProviderCredential(providerName)
        message = when (result) {
            ProviderKeyOperationResult.REMOVED -> SecurityUiMessage.API_KEY_REMOVED
            ProviderKeyOperationResult.AUTHENTICATION_REQUIRED -> SecurityUiMessage.AUTHENTICATION_REQUIRED
            ProviderKeyOperationResult.LOCKDOWN_ACTIVE -> SecurityUiMessage.LOCKDOWN_ACTIVE
            ProviderKeyOperationResult.UNKNOWN_PROVIDER -> SecurityUiMessage.API_KEY_UNKNOWN_PROVIDER
            ProviderKeyOperationResult.SAVED -> SecurityUiMessage.API_KEY_SAVED
            ProviderKeyOperationResult.EMPTY_KEY -> SecurityUiMessage.API_KEY_EMPTY
            ProviderKeyOperationResult.STORAGE_FAILURE -> SecurityUiMessage.API_KEY_STORAGE_FAILURE
        }
        return settingsState()
    }

    fun setBackgroundTimeout(timeoutMs: Long): SecurityUiState {
        message = when (appLockManager.setBackgroundTimeout(timeoutMs)) {
            AppLockOperationResult.Success -> SecurityUiMessage.TIMEOUT_SAVED
            AppLockOperationResult.AuthenticationRequired -> SecurityUiMessage.AUTHENTICATION_REQUIRED
            AppLockOperationResult.IntegrityFailure -> return SecurityUiState.AppLockIntegrityFailure
            else -> SecurityUiMessage.PIN_STORAGE_FAILURE
        }
        return settingsState()
    }

    fun setBiometricEnabled(enabled: Boolean): SecurityUiState {
        message = when (appLockManager.setBiometricEnabled(enabled)) {
            AppLockOperationResult.Success -> SecurityUiMessage.BIOMETRIC_SAVED
            AppLockOperationResult.AuthenticationRequired -> SecurityUiMessage.AUTHENTICATION_REQUIRED
            AppLockOperationResult.IntegrityFailure -> return SecurityUiState.AppLockIntegrityFailure
            else -> SecurityUiMessage.PIN_STORAGE_FAILURE
        }
        return settingsState()
    }

    fun beginPanicReset(confirmation: String): SecurityUiState {
        pendingResetResult = actions.requestPanicReset(confirmation)
        if (pendingResetResult == PanicResetRequestResult.ConfirmationRequired) {
            pendingResetResult = null
            message = SecurityUiMessage.RESET_CONFIRMATION_REQUIRED
            return SecurityUiState.PanicResetConfirmation(message)
        }
        return state()
    }

    fun releaseLockdown(confirmation: String): SecurityUiState {
        pendingReleaseCondition = actions.lockdownCondition()
        pendingReleaseResult = actions.releaseSecurityLockdown(confirmation)
        if (pendingReleaseResult == LockdownReleaseResult.ConfirmationRequired) {
            pendingReleaseResult = null
            message = SecurityUiMessage.RELEASE_CONFIRMATION_REQUIRED
            return SecurityUiState.Recovery(pendingReleaseCondition, message)
        }
        return state()
    }

    fun dismissOutcome(): SecurityUiState {
        pendingResetResult = null
        pendingReleaseResult = null
        pendingReleaseCondition = LockdownCondition.NONE
        message = null
        page = Page.HOME
        return state()
    }

    private fun settingsState(): SecurityUiState {
        if (!appLockManager.isAppUnlocked()) return state()
        page = Page.SETTINGS
        return SecurityUiState.Settings(
            configuredProviderIds = actions.configuredProviderCredentialIds(),
            backgroundTimeoutMs = appLockManager.backgroundTimeoutMs(),
            biometricEnabled = appLockManager.isBiometricEnabled(),
            message = message
        )
    }

    private fun stateForUnlockedPage(): SecurityUiState {
        val condition = actions.lockdownCondition()
        if (condition != LockdownCondition.NONE && page == Page.HOME) page = Page.RECOVERY
        return when (page) {
            Page.HOME -> SecurityUiState.Home(condition)
            Page.SETTINGS -> settingsState()
            Page.PANIC_RESET -> SecurityUiState.PanicResetConfirmation(message)
            Page.RECOVERY -> SecurityUiState.Recovery(condition, message)
        }
    }
}
