package security

import inference.PanicResetBenchmarkWorkCanceller
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
    private val clock: () -> Long = { System.currentTimeMillis() }
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

        val failures = mutableListOf<String>()
        fun attempt(stage: String, operation: () -> Unit) {
            try {
                operation()
            } catch (_: Exception) {
                failures += stage
                AgentLogger.e("PanicReset", "Stage failed: $stage")
            }
        }

        attempt("persistent-lockdown-latch") { panicResetLockdownStore.latch() }
        attempt("level-3-budget") {
            val check = rateLimiter.checkAndRecord(RiskLevel.LEVEL_3)
            if (!check.allowed) throw IllegalStateException("Level-3 budget unavailable")
        }
        var longTermMemoryCleared = false
        attempt("long-term-memory") {
            memoryStorage.clearAll()
            longTermMemoryCleared = true
        }
        attempt("session-memory") { memoryManager.clearSessionForPanicReset() }
        attempt("provider-secrets") { secretManager.clearAllSecrets() }
        attempt("active-async-jobs") { phaseXVIHooks.cancelActiveJobs() }
        attempt("benchmark-work") { benchmarkWorkCanceller.cancelForPanicReset() }
        attempt("shared-workspace") { phaseXVIHooks.purgeActiveSharedWorkspace() }
        attempt("local-chat-history") {
            if (!phaseXVIHooks.purgeLocalChatHistory()) {
                throw IllegalStateException("Local chat history could not be purged")
            }
        }
        attempt("policy-config") { configStoreResetHook.resetConfigStoreToSeedDefaults() }

        var newEpoch: Long? = null
        attempt("security-epoch") { newEpoch = epochStore.incrementEpoch() }

        // The current DB AES key is shared by encrypted long-term memory and AUDIT payloads.
        // Retire it before Genesis so older ciphertext cannot be decrypted; recordPanicResetGenesis
        // then creates a fresh key for the new Genesis row only.
        var databaseKeyRetired = false
        attempt("audit-key-retirement") {
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
                attempt("memory-fallback-purge") {
                    memoryStorage.clearAll()
                    fallbackMemoryCleared = true
                }
            }
            attempt("audit-history-fallback-purge") {
                actionAuditLog.clearHistoryForPanicReset()
                fallbackAuditPurged = true
            }
        }
        val priorHistoryUnreadable = databaseKeyRetired || (fallbackMemoryCleared && fallbackAuditPurged)

        if (newEpoch == null) {
            failures += "genesis-audit-epoch-unavailable"
            AgentLogger.e("PanicReset", "Stage failed: genesis-audit-epoch-unavailable")
        } else if (!priorHistoryUnreadable) {
            failures += "genesis-audit-history-not-retired"
            AgentLogger.e("PanicReset", "Stage failed: genesis-audit-history-not-retired")
        } else {
            attempt("genesis-audit") {
                actionAuditLog.recordPanicResetGenesis(clock(), checkNotNull(newEpoch))
            }
        }

        val completedEpoch = newEpoch
        if (failures.isNotEmpty() || completedEpoch == null) {
            throw PanicResetIncompleteException(failures.ifEmpty { listOf("security-epoch") })
        }
        AgentLogger.w("PanicReset", "Reset sequence finished with epoch=$completedEpoch; lockdown remains latched.")
        return completedEpoch
    }
}
