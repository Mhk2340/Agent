package security

/** Lockdown condition exposed without leaking storage or integrity exception details. */
enum class LockdownCondition {
    NONE,
    PANIC_RESET_LATCHED,
    INTEGRITY_FAILURE,
    MODE_LOCKDOWN
}

sealed class LockdownReleaseResult {
    object AuthenticationRequired : LockdownReleaseResult()
    object ConfirmationRequired : LockdownReleaseResult()
    object NotLocked : LockdownReleaseResult()
    data class Released(val epoch: Long, val securityStateReset: Boolean) : LockdownReleaseResult()
    data class Failed(val stageId: String) : LockdownReleaseResult()
}

/** The audit sink writes a fixed, credential-free event before the final unlock commit. */
fun interface SecurityRecoveryAuditSink {
    fun recordReleaseAuthorized(timestampEpochMs: Long, epoch: Long, securityStateReset: Boolean)
}

/** Pure policy and orchestration around the Android store; it contains no UI or Android classes. */
class LockdownReleaseCoordinator(
    private val appLockAccess: AppLockAccess,
    private val lockdownStore: PanicResetLockdownStore,
    private val secretManager: SecretManager,
    private val epochStore: SecurityEpochStore,
    private val auditSink: SecurityRecoveryAuditSink,
    private val isModeLockdownActive: () -> Boolean,
    private val onReleaseCompleted: () -> Unit,
    private val onReleaseFailed: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun currentCondition(): LockdownCondition {
        val latched = try {
            lockdownStore.isLocked()
        } catch (_: Exception) {
            return LockdownCondition.INTEGRITY_FAILURE
        }
        if (latched) return LockdownCondition.PANIC_RESET_LATCHED
        return try {
            if (isModeLockdownActive()) LockdownCondition.MODE_LOCKDOWN else LockdownCondition.NONE
        } catch (_: Exception) {
            LockdownCondition.INTEGRITY_FAILURE
        }
    }

    /**
     * Release requires a live App Lock session and a phrase chosen for the observed failure mode.
     * API keys are erased before unlock so they must be re-entered afterwards. Persistent panic
     * recovery is two-phase: the store records pending/rotates the HMAC alias, audit is committed,
     * then the pending latch is cleared. Any interruption before that final commit remains locked.
     */
    fun release(confirmation: String): LockdownReleaseResult {
        if (!isAppUnlockedFailClosed()) return LockdownReleaseResult.AuthenticationRequired
        val condition = currentCondition()
        if (condition == LockdownCondition.NONE) return LockdownReleaseResult.NotLocked
        val requiredPhrase = if (condition == LockdownCondition.INTEGRITY_FAILURE) {
            RESET_SECURITY_STATE_CONFIRMATION
        } else {
            RELEASE_LOCKDOWN_CONFIRMATION
        }
        if (confirmation != requiredPhrase) return LockdownReleaseResult.ConfirmationRequired

        val epoch = try {
            epochStore.getCurrentEpoch()
        } catch (_: Exception) {
            onReleaseFailedSafely()
            return LockdownReleaseResult.Failed("security-epoch")
        }

        val recoverableStore = if (condition == LockdownCondition.MODE_LOCKDOWN) {
            null
        } else {
            lockdownStore as? RecoverablePanicResetLockdownStore
                ?: run {
                    onReleaseFailedSafely()
                    return LockdownReleaseResult.Failed("recovery-store")
                }
        }
        var preparationStarted = false
        var stage = "latch-prepare"
        try {
            if (recoverableStore != null) {
                preparationStarted = true
                recoverableStore.prepareAuthorizedRelease()
            }
            stage = "provider-keys"
            secretManager.clearAllSecrets()
            stage = "release-audit"
            auditSink.recordReleaseAuthorized(
                timestampEpochMs = clock(),
                epoch = epoch,
                securityStateReset = condition == LockdownCondition.INTEGRITY_FAILURE
            )
            if (recoverableStore != null) {
                stage = "latch-finalize"
                recoverableStore.finalizeAuthorizedRelease()
            }
            stage = "process-state"
            onReleaseCompleted()
            return LockdownReleaseResult.Released(
                epoch = epoch,
                securityStateReset = condition == LockdownCondition.INTEGRITY_FAILURE
            )
        } catch (_: Exception) {
            if (preparationStarted) runCatching { recoverableStore?.abortAuthorizedRelease() }
            onReleaseFailedSafely()
            return LockdownReleaseResult.Failed(stage)
        }
    }

    private fun isAppUnlockedFailClosed(): Boolean = try {
        appLockAccess.isAppUnlocked()
    } catch (_: Exception) {
        false
    }

    private fun onReleaseFailedSafely() {
        runCatching { onReleaseFailed() }
    }

    companion object {
        const val RELEASE_LOCKDOWN_CONFIRMATION = "RELEASE SECURITY LOCKDOWN"
        const val RESET_SECURITY_STATE_CONFIRMATION = "RESET SECURITY STATE"
    }
}
