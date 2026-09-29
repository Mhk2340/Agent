package security

import java.util.concurrent.atomic.AtomicLong

/**
 * GEN-SEC-3: Monotonic Security Epoch Store
 *
 * CRITICAL ARCHITECTURAL FIX:
 * reset_epoch is kept completely outside the mutable config store.
 * It is strictly monotonic (reset_epoch_new = reset_epoch_old + 1)
 * and CAN NEVER be decreased or reset to zero by Panic Reset.
 */
interface SecurityEpochStore {
    fun getCurrentEpoch(): Long
    fun incrementEpoch(): Long
}

class InMemorySecurityEpochStore(initialEpoch: Long = 1L) : SecurityEpochStore {
    private val epoch = AtomicLong(initialEpoch)

    override fun getCurrentEpoch(): Long = epoch.get()

    override fun incrementEpoch(): Long {
        return epoch.incrementAndGet()
    }
}

/**
 * GEN-SEC-2 — App-Level Lock
 * Enforces an authentication barrier before accessing memory or issuing agent instructions.
 * Supports configurable background timeout (default 5 minutes).
 */
class AppLockManager(
    private val backgroundLockTimeoutMs: Long = 5 * 60 * 1000L, // 5 minutes default
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    private var isUnlocked: Boolean = false
    @Volatile
    private var lastBackgroundTimestampMs: Long = 0L
    @Volatile
    private var storedPinHash: String? = null

    fun setPin(pin: String) {
        // Basic SHA-256 equivalent hashing for pin in local runtime
        storedPinHash = hashPin(pin)
        isUnlocked = true
    }

    fun isConfigured(): Boolean = storedPinHash != null

    fun isAppUnlocked(): Boolean {
        if (!isConfigured()) return isUnlocked
        if (!isUnlocked) return false

        // Check background timeout
        if (lastBackgroundTimestampMs > 0L) {
            val elapsed = clock() - lastBackgroundTimestampMs
            if (elapsed > backgroundLockTimeoutMs) {
                isUnlocked = false
                return false
            }
        }
        return true
    }

    fun unlockWithPin(pin: String): Boolean {
        if (storedPinHash == null) {
            isUnlocked = true
            return true
        }
        if (hashPin(pin) == storedPinHash) {
            isUnlocked = true
            lastBackgroundTimestampMs = 0L
            return true
        }
        return false
    }

    fun lock() {
        isUnlocked = false
    }

    fun onAppMovedToBackground() {
        lastBackgroundTimestampMs = clock()
    }

    fun onAppMovedToForeground() {
        if (lastBackgroundTimestampMs > 0L) {
            val elapsed = clock() - lastBackgroundTimestampMs
            if (elapsed > backgroundLockTimeoutMs) {
                isUnlocked = false
            }
        }
    }

    private fun hashPin(pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
