package security

import memory.EncryptedMemoryStorage
import memory.MemoryCallerRole
import memory.MemoryManager
import memory.MemoryType
import reliability.SecurityLockdownEnforcer
import runtime.AgentLogger
import skills.ActionAuditLog
import skills.ActionRateLimiter

/**
 * GEN-SEC-3 — Panic Reset & Security Lockdown Controller
 *
 * CRITICAL ARCHITECTURAL CONSTRAINTS:
 * 1. Panic Reset is a Level 3 action: It consumes 1 unit from ActionRateLimiter (GEN-NEW-RATE)
 *    and NEVER resets or wipes ActionRateLimiter counters.
 * 2. Immutable Safety Invariants: NEVER touches, modifies, or resets:
 *    - GEN-NEW-GUARD
 *    - SkillRiskRegistry (base contracts)
 *    - ActionRateLimiter execution counters
 * 3. Config Store Reset to Strict Seed Defaults (Patch #2):
 *    - Config Store (risk_levels, rate limit ceilings) is reset to conservative seed defaults
 *      (neither deleted nor left untouched).
 * 4. Atomic destruction of sensitive data & Phase XVI cancellation (Patch #1):
 *    - Purges all LongTermMemory and SessionMemory.
 *    - Purges all external API credentials from SecretManager.
 *    - Invokes PanicResetPhaseXVIHooks to cancel active jobs and purge shared workspace.
 *    - Increments strictly monotonic reset_epoch via SecurityEpochStore.
 *    - Writes mandatory Genesis audit record: "Reset at T, epoch=N, prior history unreadable".
 */
class RealSecurityLockdownEnforcer(
    private val memoryManager: MemoryManager,
    private val memoryStorage: EncryptedMemoryStorage,
    private val secretManager: SecretManager,
    private val epochStore: SecurityEpochStore,
    private val rateLimiter: ActionRateLimiter,
    private val appLockManager: AppLockManager = AppLockManager(),
    // Integrated with Phase XVI (GEN-MA-3, GEN-MA-8)
    private val phaseXVIHooks: PanicResetPhaseXVIHooks = DefaultPanicResetPhaseXVIHooks(),
    private val configStoreResetHook: ConfigStoreResetHook = DefaultConfigStoreResetHook(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : SecurityLockdownEnforcer {

    @Volatile
    private var isLockdownActive: Boolean = false

    override fun enforceLockdown(enabled: Boolean) {
        isLockdownActive = enabled
        if (enabled) {
            appLockManager.lock()
            AgentLogger.w("SecurityLockdown", "REAL Security Lockdown ACTIVATED: Memory READ-ONLY, Level 2/3 skills disabled.")
        } else {
            AgentLogger.i("SecurityLockdown", "Security Lockdown DEACTIVATED.")
        }
    }

    override fun isMemoryReadOnly(): Boolean = isLockdownActive
    override fun areMutatingToolsAllowed(): Boolean = !isLockdownActive
    override fun isExternalNetworkAllowed(): Boolean = !isLockdownActive

    /**
     * Executes Panic Reset (GEN-SEC-3).
     * Returns the newly incremented security epoch.
     */
    @Synchronized
    fun executePanicReset(reason: String): Long {
        // Constraint 1: Panic Reset consumes from ActionRateLimiter Level-3 quota
        val rateCheck = rateLimiter.checkAndRecord(safety.RiskLevel.LEVEL_3)
        check(rateCheck.allowed) {
            "Panic Reset rejected by Action Rate Limiter (GEN-NEW-RATE): Hourly Level-3 action ceiling exceeded."
        }

        AgentLogger.w("PanicReset", "Executing PANIC RESET: reason=$reason")

        // 1. Wipe LongTermMemory and SessionMemory
        memoryStorage.clearAll()
        memoryManager.clearSession(MemoryCallerRole.SYSTEM_CORE)

        // 2. Wipe all external API credentials from Secret Vault
        secretManager.clearAllSecrets()

        // 3. Reset Config Store to strict seed defaults (Patch #2)
        configStoreResetHook.resetConfigStoreToSeedDefaults()

        // 4. Trigger Phase XVI forward-dependency hooks (Cancel jobs & purge workspace) (Patch #1)
        phaseXVIHooks.cancelActiveJobs()
        phaseXVIHooks.purgeActiveSharedWorkspace()

        // 5. Monotonically increment reset_epoch (CANNOT be reset to 0)
        val newEpoch = epochStore.incrementEpoch()

        // 6. Lock application
        appLockManager.lock()

        // 7. Mandatory Genesis Record in Long-Term Memory
        val now = clock()
        val genesisMessage = "[GENESIS_RECORD] Panic Reset executed at $now, epoch=$newEpoch, prior history unreadable. Reason: $reason"
        memoryManager.saveLongTermMemory(
            memoryType = MemoryType.EPISODIC,
            content = genesisMessage,
            embedding = null,
            importance = 1.0f,
            origin = "panic_reset",
            guidedByProcedureId = null,
            retryAfter = null,
            schemaVersion = 1,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )

        AgentLogger.w("PanicReset", "Panic Reset completed successfully. New reset_epoch: $newEpoch")
        return newEpoch
    }
}
