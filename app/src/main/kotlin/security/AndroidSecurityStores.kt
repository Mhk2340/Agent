package security

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Supplies non-exportable integrity keys used by security state stores. */
interface IntegrityMacKeyProvider {
    fun getExistingKey(alias: String): SecretKey?
    fun getOrCreateKey(alias: String): SecretKey
    fun deleteKey(alias: String): Boolean
}

/** Production HMAC-SHA-256 key provider backed by Android Keystore. */
class AndroidKeystoreIntegrityMacKeyProvider : IntegrityMacKeyProvider {
    override fun getExistingKey(alias: String): SecretKey? = synchronized(lock) {
        keyStore().getKey(alias, null) as? SecretKey
    }

    override fun getOrCreateKey(alias: String): SecretKey = synchronized(lock) {
        val existing = keyStore().getKey(alias, null) as? SecretKey
        existing ?: try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
            }.generateKey()
        } catch (_: Exception) {
            throw IllegalStateException("Unable to create security-store integrity key")
        }
    }

    override fun deleteKey(alias: String): Boolean = synchronized(lock) {
        try {
            val store = keyStore()
            if (!store.containsAlias(alias)) return@synchronized false
            store.deleteEntry(alias)
            true
        } catch (_: Exception) {
            throw IllegalStateException("Unable to delete security-store integrity key")
        }
    }

    private fun keyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (_: Exception) {
        throw IllegalStateException("Unable to access Android Keystore integrity keys")
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        val lock = Any()
    }
}

/**
 * Durable monotonic reset_epoch storage. Its SharedPreferences file and HMAC key are independent
 * of the resettable policy Config Store. There is deliberately no setter/decrement operation.
 */
class AndroidSecurityEpochStore(
    context: Context,
    private val keyProvider: IntegrityMacKeyProvider = AndroidKeystoreIntegrityMacKeyProvider(),
    private val preferencesName: String = PREFERENCES_NAME,
    private val integrityKeyAlias: String = INTEGRITY_KEY_ALIAS
) : SecurityEpochStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun getCurrentEpoch(): Long = readOrInitialize()

    @Synchronized
    override fun incrementEpoch(): Long {
        val current = readOrInitialize()
        val next = try {
            Math.addExact(current, 1L)
        } catch (_: ArithmeticException) {
            throw SecurityException("Security epoch cannot be incremented")
        }
        persist(next)
        return next
    }

    private fun readOrInitialize(): Long {
        val hasEpoch = preferences.contains(KEY_EPOCH)
        val hasMac = preferences.contains(KEY_MAC)
        if (!hasEpoch && !hasMac) {
            // An existing integrity key with missing state indicates deletion/tampering, not a
            // fresh installation; fail closed rather than silently resetting the epoch to 1.
            if (existingKeyOrFail() != null) integrityFailure()
            persist(INITIAL_EPOCH)
            return INITIAL_EPOCH
        }
        if (!hasEpoch || !hasMac) integrityFailure()

        val epoch = try {
            preferences.getLong(KEY_EPOCH, 0L)
        } catch (_: Exception) {
            integrityFailure()
        }
        if (epoch < INITIAL_EPOCH) integrityFailure()
        val key = existingKeyOrFail() ?: integrityFailure()
        val storedMac = try {
            android.util.Base64.decode(preferences.getString(KEY_MAC, null), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            integrityFailure()
        }
        val expectedMac = calculateMac(key, canonicalEpoch(epoch))
        if (!MessageDigest.isEqual(storedMac, expectedMac)) integrityFailure()
        return epoch
    }

    /**
     * Synchronous commit is intentional: the caller must know the Boolean result before relying on
     * a new security epoch. `apply()` has no durable-success result and is unsafe for this boundary.
     */
    @SuppressLint("ApplySharedPref")
    private fun persist(epoch: Long) {
        val key = try {
            keyProvider.getOrCreateKey(integrityKeyAlias)
        } catch (_: Exception) {
            throw SecurityException("Security epoch integrity key unavailable")
        }
        val mac = calculateMac(key, canonicalEpoch(epoch))
        val committed = preferences.edit()
            .putLong(KEY_EPOCH, epoch)
            .putString(KEY_MAC, android.util.Base64.encodeToString(mac, android.util.Base64.NO_WRAP))
            .commit()
        if (!committed) throw SecurityException("Unable to persist security epoch")
    }

    private fun existingKeyOrFail(): SecretKey? = try {
        keyProvider.getExistingKey(integrityKeyAlias)
    } catch (_: Exception) {
        throw SecurityException("Security epoch integrity key unavailable")
    }

    private fun canonicalEpoch(epoch: Long): ByteArray =
        "agent-security-epoch-v1:$epoch".toByteArray(Charsets.UTF_8)

    private fun calculateMac(key: SecretKey, data: ByteArray): ByteArray = try {
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(key)
            doFinal(data)
        }
    } catch (_: Exception) {
        throw SecurityException("Security epoch integrity check unavailable")
    }

    private fun integrityFailure(): Nothing =
        throw SecurityException("Security epoch integrity validation failed")

    private companion object {
        const val PREFERENCES_NAME = "agent_security_epoch_store"
        const val INTEGRITY_KEY_ALIAS = "android_agent_security_epoch_integrity_v1"
        const val INITIAL_EPOCH = 1L
        const val KEY_EPOCH = "reset_epoch"
        const val KEY_MAC = "reset_epoch_hmac"
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

/** Monotonic latch for Panic Reset. Recovery is restricted to the authenticated container API. */
interface PanicResetLockdownStore {
    fun isLocked(): Boolean
    fun latch()
}

/** Two-phase fail-closed release protocol; the latch remains locked until the final commit. */
interface RecoverablePanicResetLockdownStore : PanicResetLockdownStore {
    fun prepareAuthorizedRelease()
    fun finalizeAuthorizedRelease()
    fun abortAuthorizedRelease()
}

/** TEST ONLY. Production containers use [AndroidPanicResetLockdownStore]. */
class InMemoryPanicResetLockdownStore(initiallyLocked: Boolean = false) : RecoverablePanicResetLockdownStore {
    @Volatile
    private var active = initiallyLocked
    @Volatile
    private var releasePending = false

    override fun isLocked(): Boolean = active || releasePending

    @Synchronized
    override fun latch() {
        active = true
        releasePending = false
    }

    @Synchronized
    override fun prepareAuthorizedRelease() {
        releasePending = true
    }

    @Synchronized
    override fun finalizeAuthorizedRelease() {
        check(releasePending) { "No authorized release is pending" }
        active = false
        releasePending = false
    }

    @Synchronized
    override fun abortAuthorizedRelease() {
        active = true
        releasePending = true
    }
}

/**
 * Durable, HMAC-protected Panic Reset latch. Existing v1 locked records remain readable. Release
 * rotates the integrity alias and uses a persistent pending bit so every interrupted stage fails
 * closed until an authorized retry reaches the final commit.
 */
class AndroidPanicResetLockdownStore(
    context: Context,
    private val keyProvider: IntegrityMacKeyProvider = AndroidKeystoreIntegrityMacKeyProvider(),
    private val preferencesName: String = PREFERENCES_NAME,
    private val integrityKeyAlias: String = INTEGRITY_KEY_ALIAS
) : RecoverablePanicResetLockdownStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )

    @Volatile
    private var releaseFailedInProcess = false

    @Synchronized
    override fun isLocked(): Boolean {
        if (releaseFailedInProcess) return true
        val releasePending = readPendingMarker()
        if (releasePending) return true

        val hasState = preferences.contains(KEY_LOCKED)
        val hasMac = preferences.contains(KEY_MAC)
        if (!hasState && !hasMac) {
            if (preferences.contains(KEY_VERSION) || preferences.contains(KEY_RELEASE_PENDING)) integrityFailure()
            if (existingKeyOrFail() != null) integrityFailure()
            return false
        }
        if (!hasState || !hasMac) integrityFailure()
        val locked = try {
            preferences.getBoolean(KEY_LOCKED, false)
        } catch (_: Exception) {
            integrityFailure()
        }
        val version = try {
            preferences.getInt(KEY_VERSION, LEGACY_VERSION)
        } catch (_: Exception) {
            integrityFailure()
        }
        val canonicalState = when (version) {
            LEGACY_VERSION -> {
                if (!locked) integrityFailure()
                canonicalLegacyState(locked)
            }
            CURRENT_VERSION -> {
                if (!preferences.contains(KEY_RELEASE_PENDING) || readPendingMarker()) integrityFailure()
                canonicalState(locked, releasePending = false)
            }
            else -> integrityFailure()
        }
        val storedMac = try {
            android.util.Base64.decode(preferences.getString(KEY_MAC, null), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            integrityFailure()
        }
        val key = existingKeyOrFail() ?: integrityFailure()
        val expectedMac = calculateMac(key, canonicalState)
        val valid = MessageDigest.isEqual(storedMac, expectedMac)
        storedMac.fill(0)
        expectedMac.fill(0)
        if (!valid) integrityFailure()
        return locked
    }

    @Synchronized
    override fun latch() {
        if (readPendingMarker()) return
        if (preferences.contains(KEY_LOCKED) || preferences.contains(KEY_MAC)) {
            if (isLocked()) return
        } else if (existingKeyOrFail() != null) {
            integrityFailure()
        }
        val key = getOrCreateKeyOrFail()
        persistCurrentState(locked = true, releasePending = false, key = key)
    }

    /**
     * Writes the persistent pending marker before touching either the old HMAC key or state. The
     * marker is always interpreted as locked, including if subsequent key rotation fails.
     */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    override fun prepareAuthorizedRelease() {
        releaseFailedInProcess = true
        val markedPending = try {
            preferences.edit().putBoolean(KEY_RELEASE_PENDING, true).commit()
        } catch (_: Exception) {
            false
        }
        if (!markedPending) throw SecurityException("Unable to persist lockdown recovery marker")

        try {
            val oldKey = existingKeyOrFail()
            if (oldKey != null && !keyProvider.deleteKey(integrityKeyAlias)) {
                throw IllegalStateException("Integrity alias deletion failed")
            }
            val replacementKey = getOrCreateKeyOrFail()
            persistCurrentState(locked = false, releasePending = true, key = replacementKey)
        } catch (_: Exception) {
            // Keep the pending marker and in-process latch; a later authenticated retry can rebuild.
            throw SecurityException("Unable to prepare lockdown release")
        }
    }

    /** Writes the unlocked snapshot only after the release audit event has been durably appended. */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    override fun finalizeAuthorizedRelease() {
        try {
            if (!readPendingMarker()) integrityFailure()
            if (preferences.getInt(KEY_VERSION, -1) != CURRENT_VERSION) integrityFailure()
            if (preferences.getBoolean(KEY_LOCKED, true)) integrityFailure()
            val key = existingKeyOrFail() ?: integrityFailure()
            val storedMac = android.util.Base64.decode(
                preferences.getString(KEY_MAC, null), android.util.Base64.NO_WRAP
            )
            val expectedMac = calculateMac(key, canonicalState(locked = false, releasePending = true))
            val valid = MessageDigest.isEqual(storedMac, expectedMac)
            storedMac.fill(0)
            expectedMac.fill(0)
            if (!valid) integrityFailure()
            persistCurrentState(locked = false, releasePending = false, key = key)
            releaseFailedInProcess = false
        } catch (_: Exception) {
            releaseFailedInProcess = true
            persistReleasePendingMarkerBestEffort()
            throw SecurityException("Unable to finalize lockdown release")
        }
    }

    /** Best effort only: leave the authenticated pending marker in place, which means locked. */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    override fun abortAuthorizedRelease() {
        releaseFailedInProcess = true
        persistReleasePendingMarkerBestEffort()
    }

    /**
     * A bare pending=true marker is intentionally fail-closed even when it cannot be re-HMACed.
     * This is used only on a failure path; authenticated retry rotates/rebuilds the state and alias.
     */
    @SuppressLint("ApplySharedPref")
    private fun persistReleasePendingMarkerBestEffort() {
        runCatching { preferences.edit().putBoolean(KEY_RELEASE_PENDING, true).commit() }
    }

    /**
     * Synchronous commits are deliberate: after a failed security-store write the app must remain
     * locked. `apply()` would not report whether the persistent transition succeeded.
     */
    @SuppressLint("ApplySharedPref")
    private fun persistCurrentState(locked: Boolean, releasePending: Boolean, key: SecretKey) {
        val mac = calculateMac(key, canonicalState(locked, releasePending))
        val committed = try {
            preferences.edit()
                .putInt(KEY_VERSION, CURRENT_VERSION)
                .putBoolean(KEY_LOCKED, locked)
                .putBoolean(KEY_RELEASE_PENDING, releasePending)
                .putString(KEY_MAC, android.util.Base64.encodeToString(mac, android.util.Base64.NO_WRAP))
                .commit()
        } catch (_: Exception) {
            false
        } finally {
            mac.fill(0)
        }
        if (!committed) throw SecurityException("Unable to persist Panic Reset lockdown state")
    }

    private fun readPendingMarker(): Boolean = try {
        if (!preferences.contains(KEY_RELEASE_PENDING)) false
        else preferences.getBoolean(KEY_RELEASE_PENDING, false)
    } catch (_: Exception) {
        // A malformed marker must not be interpreted as a successful release.
        integrityFailure()
    }

    private fun existingKeyOrFail(): SecretKey? = try {
        keyProvider.getExistingKey(integrityKeyAlias)
    } catch (_: Exception) {
        throw SecurityException("Panic Reset latch integrity key unavailable")
    }

    private fun getOrCreateKeyOrFail(): SecretKey = try {
        keyProvider.getOrCreateKey(integrityKeyAlias)
    } catch (_: Exception) {
        throw SecurityException("Panic Reset latch integrity key unavailable")
    }

    private fun canonicalLegacyState(locked: Boolean): ByteArray =
        "agent-panic-lockdown-v1:$locked".toByteArray(Charsets.UTF_8)

    private fun canonicalState(locked: Boolean, releasePending: Boolean): ByteArray =
        "agent-panic-lockdown-v2:$locked:$releasePending".toByteArray(Charsets.UTF_8)

    private fun calculateMac(key: SecretKey, data: ByteArray): ByteArray = try {
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(key)
            doFinal(data)
        }
    } catch (_: Exception) {
        throw SecurityException("Panic Reset latch integrity check unavailable")
    } finally {
        data.fill(0)
    }

    private fun integrityFailure(): Nothing =
        throw SecurityException("Panic Reset latch integrity validation failed")

    override fun toString(): String = "AndroidPanicResetLockdownStore(state=<redacted>)"

    private companion object {
        const val PREFERENCES_NAME = "agent_panic_reset_lockdown"
        const val INTEGRITY_KEY_ALIAS = "android_agent_panic_reset_lock_integrity_v1"
        const val KEY_LOCKED = "locked"
        const val KEY_MAC = "integrity_mac"
        const val KEY_VERSION = "format_version"
        const val KEY_RELEASE_PENDING = "release_pending"
        const val LEGACY_VERSION = 1
        const val CURRENT_VERSION = 2
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
