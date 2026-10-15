package security

import java.util.concurrent.atomic.AtomicLong

/**
 * GEN-SEC-3: Monotonic Security Epoch Store
 *
 * reset_epoch is kept outside the mutable policy Config Store. It is strictly monotonic
 * (reset_epoch_new = reset_epoch_old + 1) and is never decreased by Panic Reset or recovery.
 */
interface SecurityEpochStore {
    fun getCurrentEpoch(): Long
    fun incrementEpoch(): Long
}

class InMemorySecurityEpochStore(initialEpoch: Long = 1L) : SecurityEpochStore {
    init {
        require(initialEpoch >= 1L) { "Security epoch must start positive" }
    }

    private val epoch = AtomicLong(initialEpoch)

    override fun getCurrentEpoch(): Long = epoch.get()

    override fun incrementEpoch(): Long {
        while (true) {
            val current = epoch.get()
            val next = Math.addExact(current, 1L)
            if (epoch.compareAndSet(current, next)) return next
        }
    }
}
