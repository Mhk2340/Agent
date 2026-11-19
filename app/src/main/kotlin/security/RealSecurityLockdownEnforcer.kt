package security

import inference.PanicResetBenchmarkWorkCanceller
import diagnostics.DiagnosticCode
import diagnostics.DiagnosticComponent
import diagnostics.DiagnosticField
import diagnostics.DiagnosticFieldKey
import diagnostics.DiagnosticLevel
import diagnostics.DiagnosticsLog
import diagnostics.PanicResetOutcome
import diagnostics.PanicResetStage
import memory.AesGcmCipher
import memory.EncryptedMemoryStorage
import memory.KeystoreKeyProvider
import memory.MemoryManager
import reliability.SecurityLockdownEnforcer
import runtime.AgentLogger
import skills.ActionAuditLog
import skills.ActionRateLimiter
import safety.RiskLevel

/** Cause-free summary; it contains stage identifiers only, never exception text or user/provider data. */
class PanicResetIncompleteException(
    failedStages: List<String>
) : IllegalStateException(
    "Panic Reset remains locked; failed stages: ${failedStages.joinToString(", ")}"
) {
    val failedStages: List<String> = failedStages.toList()
}

/**
 * GEN-SEC-3 — Panic Reset & Security Lockdown Controller.
 *
 * The durable panic latch is set before destructive work. Each operation is isolated so an error
 * cannot skip later cleanup. A failed stage is reported only by its fixed identifier; no exception
 * message or cause is logged or propagated. The Level-3 counter is consumed but never cleared.
 */
class RealSecurityLockdownEnforcer(
    private val memoryManager: MemoryManager,
    private val memoryStorage: EncryptedMemoryStorage,
    private val secretManager: SecretManager,
    private val epochStore: SecurityEpochStore,
    private val rateLimiter: ActionRateLimiter,
    private val actionAuditLog: ActionAuditLog,
    private val databaseKeyProvider: KeystoreKeyProvider,
    private val panicResetLockdownStore: PanicResetLockdownStore,
    private val phaseXVIHooks: PanicResetPhaseXVIHooks,
    private val benchmarkWorkCanceller: PanicResetBenchmarkWorkCanceller,
    private val configStoreResetHook: ConfigStoreResetHook,
    private val appLockManager: AppLockManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val diagnostics: DiagnosticsLog? = null
) : SecurityLockdownEnforcer {

    @Volatile
    private var isLockdownActive: Boolean = false

    @Volatile
    private var panicResetLatchedInProcess: Boolean = false

    override fun enforceLockdown(enabled: Boolean) {
        if (!enabled && isPanicResetLatched()) {
            isLockdownActive = true
            appLockManager.lock()
            AgentLogger.w("SecurityLockdown", "Panic Reset latch prevents ordinary lockdown release.")
            return
        }
        isLockdownActive = enabled
        if (enabled) {
            appLockManager.lock()
            AgentLogger.w("SecurityLockdown", "Security lockdown active: mutating actions and external routing disabled.")
        } else {
            AgentLogger.i("SecurityLockdown", "Security lockdown deactivated.")
        }
    }

    override fun isMemoryReadOnly(): Boolean = isLockdownActive
    override fun areMutatingToolsAllowed(): Boolean = !isLockdownActive
    override fun isExternalNetworkAllowed(): Boolean = !isLockdownActive

    override fun isPanicResetLatched(): Boolean {
        if (panicResetLatchedInProcess) return true
        return try {
            panicResetLockdownStore.isLocked()
        } catch (_: Exception) {
            // An unreadable integrity-protected latch is treated as locked.
            true
        }
    }

    /** Called only by the authenticated recovery coordinator after the durable two-phase release. */
    @Synchronized
    internal fun completeAuthorizedRelease() {
        if (panicResetLockdownStore.isLocked()) {
            throw SecurityException("Panic Reset release is not durable")
        }
        panicResetLatchedInProcess = false
        isLockdownActive = false
    }

    /** Keeps this process fail-closed if an authorized release stops part-way through. */
    @Synchronized
    internal fun retainLockdownAfterFailedRelease() {
        panicResetLatchedInProcess = true
        isLockdownActive = true
        appLockManager.lock()
    }

    private fun recordPanicResetStage(
        stage: PanicResetStage,
        outcome: PanicResetOutcome,
        failure: Throwable? = null
    ) {
        val fields = arrayOf(
            DiagnosticField.enum(DiagnosticFieldKey.PHASE, stage),
            DiagnosticField.enum(DiagnosticFieldKey.RESULT_TYPE, outcome)
        )
        if (failure != null) {
            diagnostics?.recordFailure(
                if (outcome == PanicResetOutcome.FAILED) DiagnosticLevel.ERROR else DiagnosticLevel.INFO,
                DiagnosticComponent.PANIC_RESET,
                DiagnosticCode.PANIC_RESET_STAGE,
                failure,
                *fields
            )
        } else {
            diagnostics?.record(
                if (outcome == PanicResetOutcome.FAILED) DiagnosticLevel.ERROR else DiagnosticLevel.INFO,
                DiagnosticComponent.PANIC_RESET,
                DiagnosticCode.PANIC_RESET_STAGE,
                *fields
            )
        }
    }

    /**
     * Executes Panic Reset. The supplied reason is intentionally not logged or persisted because
     * it may contain plaintext or provider data. Returns the monotonically incremented epoch when
     * every stage succeeds; otherwise throws a cause-free [PanicResetIncompleteException].
     */
    @Suppress("UNUSED_PARAMETER")
    @Synchronized
    fun executePanicReset(reason: String): Long {
        // Immediate in-process closure precedes even the first fallible storage operation.
        panicResetLatchedInProcess = true
        enforceLockdown(true)
        diagnostics?.record(
            DiagnosticLevel.INFO,
            DiagnosticComponent.PANIC_RESET,
            DiagnosticCode.PANIC_RESET_STARTED,
            DiagnosticField.enum(DiagnosticFieldKey.PHASE, PanicResetStage.RESET),
            DiagnosticField.enum(DiagnosticFieldKey.RESULT_TYPE, PanicResetOutcome.STARTED)
        )

        val failures = mutableListOf<String>()
        fun attempt(stage: String, diagnosticStage: PanicResetStage, operation: () -> Unit) {
            recordPanicResetStage(diagnosticStage, PanicResetOutcome.STARTED)
            try {
                operation()
                recordPanicResetStage(diagnosticStage, PanicResetOutcome.SUCCEEDED)
            } catch (failure: Exception) {
                failures += stage
                recordPanicResetStage(diagnosticStage, PanicResetOutcome.FAILED, failure)
                AgentLogger.e("PanicReset", "Stage failed: $stage")
            }
        }

        attempt("persistent-lockdown-latch", PanicResetStage.LOCKDOWN_LATCH) { panicResetLockdownStore.latch() }
        attempt("level-3-budget", PanicResetStage.LEVEL_3_BUDGET) {
            val check = rateLimiter.checkAndRecord(RiskLevel.LEVEL_3)
            if (!check.allowed) throw IllegalStateException("Level-3 budget unavailable")
        }
        var longTermMemoryCleared = false
        attempt("long-term-memory", PanicResetStage.LONG_TERM_MEMORY) {
            memoryStorage.clearAll()
            longTermMemoryCleared = true
        }
        attempt("session-memory", PanicResetStage.SESSION_MEMORY) { memoryManager.clearSessionForPanicReset() }
        attempt("provider-secrets", PanicResetStage.PROVIDER_SECRETS) { secretManager.clearAllSecrets() }
        attempt("active-async-jobs", PanicResetStage.ACTIVE_JOBS) { phaseXVIHooks.cancelActiveJobs() }
        attempt("benchmark-work", PanicResetStage.BENCHMARK_WORK) { benchmarkWorkCanceller.cancelForPanicReset() }
        attempt("shared-workspace", PanicResetStage.SHARED_WORKSPACE) { phaseXVIHooks.purgeActiveSharedWorkspace() }
        attempt("local-chat-history", PanicResetStage.LOCAL_CHAT_HISTORY) {
            if (!phaseXVIHooks.purgeLocalChatHistory()) {
                throw IllegalStateException("Local chat history could not be purged")
            }
        }
        attempt("policy-config", PanicResetStage.POLICY_CONFIG) { configStoreResetHook.resetConfigStoreToSeedDefaults() }

        var newEpoch: Long? = null
        attempt("security-epoch", PanicResetStage.SECURITY_EPOCH) { newEpoch = epochStore.incrementEpoch() }

        // The current DB AES key is shared by encrypted long-term memory and AUDIT payloads.
        // Retire it before Genesis so older ciphertext cannot be decrypted; recordPanicResetGenesis
        // then creates a fresh key for the new Genesis row only.
        var databaseKeyRetired = false
        attempt("audit-key-retirement", PanicResetStage.AUDIT_KEY_RETIREMENT) {
            val previousKey = databaseKeyProvider.getSecretKey(AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS)
            val deleted = databaseKeyProvider.deleteSecretKey(AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS)
            databaseKeyRetired = previousKey == null || deleted
            if (!databaseKeyRetired) throw IllegalStateException("Previous database key remained present")
        }
        var fallbackMemoryCleared = longTermMemoryCleared
        var fallbackAuditPurged = false
        if (!databaseKeyRetired) {
            // If key deletion fails, retry deletion of the encrypted memory rows and remove the old
            // audit/idempotency rows. Genesis is written only when both prior histories are retired.
            if (!fallbackMemoryCleared) {
                attempt("memory-fallback-purge", PanicResetStage.MEMORY_FALLBACK_PURGE) {
                    memoryStorage.clearAll()
                    fallbackMemoryCleared = true
                }
            }
            attempt("audit-history-fallback-purge", PanicResetStage.AUDIT_HISTORY_FALLBACK_PURGE) {
                actionAuditLog.clearHistoryForPanicReset()
                fallbackAuditPurged = true
            }
        }
        val priorHistoryUnreadable = databaseKeyRetired || (fallbackMemoryCleared && fallbackAuditPurged)

        if (newEpoch == null) {
            failures += "genesis-audit-epoch-unavailable"
            recordPanicResetStage(PanicResetStage.GENESIS_AUDIT, PanicResetOutcome.FAILED)
            AgentLogger.e("PanicReset", "Stage failed: genesis-audit-epoch-unavailable")
        } else if (!priorHistoryUnreadable) {
            failures += "genesis-audit-history-not-retired"
            recordPanicResetStage(PanicResetStage.GENESIS_AUDIT, PanicResetOutcome.FAILED)
            AgentLogger.e("PanicReset", "Stage failed: genesis-audit-history-not-retired")
        } else {
            attempt("genesis-audit", PanicResetStage.GENESIS_AUDIT) {
                actionAuditLog.recordPanicResetGenesis(clock(), checkNotNull(newEpoch))
            }
        }

        val completedEpoch = newEpoch
        if (failures.isNotEmpty() || completedEpoch == null) {
            diagnostics?.record(
                DiagnosticLevel.ERROR,
                DiagnosticComponent.PANIC_RESET,
                DiagnosticCode.PANIC_RESET_FAILED,
                DiagnosticField.enum(DiagnosticFieldKey.PHASE, PanicResetStage.RESET),
                DiagnosticField.enum(DiagnosticFieldKey.RESULT_TYPE, PanicResetOutcome.FAILED)
            )
            throw PanicResetIncompleteException(failures.ifEmpty { listOf("security-epoch") })
        }
        diagnostics?.record(
            DiagnosticLevel.INFO,
            DiagnosticComponent.PANIC_RESET,
            DiagnosticCode.PANIC_RESET_STAGE,
            DiagnosticField.enum(DiagnosticFieldKey.PHASE, PanicResetStage.RESET),
            DiagnosticField.enum(DiagnosticFieldKey.RESULT_TYPE, PanicResetOutcome.SUCCEEDED)
        )
        AgentLogger.w("PanicReset", "Reset sequence finished with epoch=$completedEpoch; lockdown remains latched.")
        return completedEpoch
    }
}
