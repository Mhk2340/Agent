package security

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Android persistence for the app-lock verifier and durable PIN-backoff state. The PIN itself is
 * never written; each stored snapshot is authenticated by a dedicated Android-Keystore HMAC key.
 */
class AndroidAppLockStateStore(
    context: Context,
    private val keyProvider: IntegrityMacKeyProvider = AndroidKeystoreIntegrityMacKeyProvider(),
    preferencesName: String = PREFERENCES_NAME,
    private val integrityKeyAlias: String = INTEGRITY_KEY_ALIAS
) : AppLockStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun read(): AppLockStoreRead {
        val anyState = STATE_KEYS.any(preferences::contains)
        val key = existingKeyOrFail()
        if (!anyState) {
            if (key != null) integrityFailure()
            return AppLockStoreRead.Empty
        }
        if (STATE_KEYS.any { !preferences.contains(it) }) integrityFailure()

        val version = readInt(KEY_VERSION)
        if (version != FORMAT_VERSION) integrityFailure()
        val salt = readString(KEY_SALT)
        val verifier = readString(KEY_VERIFIER)
        val iterations = readInt(KEY_ITERATIONS)
        val failedAttempts = readInt(KEY_FAILED_ATTEMPTS)
        val lockedUntil = readLong(KEY_LOCKED_UNTIL)
        val timeout = readLong(KEY_BACKGROUND_TIMEOUT)
        val biometricEnabled = readBoolean(KEY_BIOMETRIC_ENABLED)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) integrityFailure()
        if (failedAttempts < 0 || lockedUntil < 0L) integrityFailure()
        if (timeout !in AppLockStateMachine.MIN_BACKGROUND_TIMEOUT_MS..AppLockStateMachine.MAX_BACKGROUND_TIMEOUT_MS) {
            integrityFailure()
        }
        val saltBytes = decode(salt)
        try {
            val verifierBytes = decode(verifier)
            try {
                if (saltBytes.size != AppLockStateMachine.PIN_SALT_BYTES ||
                    verifierBytes.size != AppLockStateMachine.PIN_HASH_BYTES
                ) integrityFailure()
            } finally {
                verifierBytes.fill(0)
            }
        } finally {
            saltBytes.fill(0)
        }

        val macKey = key ?: integrityFailure()
        val storedMac = decode(readString(KEY_MAC))
        val valid = try {
            val expectedMac = calculateMac(macKey, canonical(stateFields(
                salt, verifier, iterations, failedAttempts, lockedUntil, timeout, biometricEnabled
            )))
            try {
                MessageDigest.isEqual(storedMac, expectedMac)
            } finally {
                expectedMac.fill(0)
            }
        } finally {
            storedMac.fill(0)
        }
        if (!valid) integrityFailure()

        return AppLockStoreRead.Configured(
            AppLockPersistentState(
                saltBase64 = salt,
                verifierBase64 = verifier,
                iterations = iterations,
                failedAttempts = failedAttempts,
                lockedUntilEpochMs = lockedUntil,
                backgroundTimeoutMs = timeout,
                biometricEnabled = biometricEnabled
            )
        )
    }

    /**
     * Synchronous commit is required: PIN-failure counters and authentication decisions must not
     * become visible before the durable fail-closed state is known. Do not replace with apply().
     */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    override fun write(state: AppLockPersistentState) {
        val fields = state.canonicalFields()
        if (state.iterations !in MIN_ITERATIONS..MAX_ITERATIONS ||
            state.failedAttempts < 0 || state.lockedUntilEpochMs < 0L ||
            state.backgroundTimeoutMs !in AppLockStateMachine.MIN_BACKGROUND_TIMEOUT_MS..AppLockStateMachine.MAX_BACKGROUND_TIMEOUT_MS
        ) throw IllegalArgumentException("Invalid App Lock state")

        val key = try {
            keyProvider.getOrCreateKey(integrityKeyAlias)
        } catch (_: Exception) {
            throw AppLockIntegrityException()
        }
        val payload = canonical(fields)
        val mac = calculateMac(key, payload)
        val committed = try {
            preferences.edit()
                .putInt(KEY_VERSION, FORMAT_VERSION)
                .putString(KEY_SALT, fields[FIELD_SALT])
                .putString(KEY_VERIFIER, fields[FIELD_VERIFIER])
                .putInt(KEY_ITERATIONS, state.iterations)
                .putInt(KEY_FAILED_ATTEMPTS, state.failedAttempts)
                .putLong(KEY_LOCKED_UNTIL, state.lockedUntilEpochMs)
                .putLong(KEY_BACKGROUND_TIMEOUT, state.backgroundTimeoutMs)
                .putBoolean(KEY_BIOMETRIC_ENABLED, state.biometricEnabled)
                .putString(KEY_MAC, Base64.encodeToString(mac, Base64.NO_WRAP))
                .commit()
        } catch (_: Exception) {
            false
        } finally {
            mac.fill(0)
        }
        if (!committed) throw IllegalStateException("Unable to persist App Lock state")
    }

    private fun existingKeyOrFail(): SecretKey? = try {
        keyProvider.getExistingKey(integrityKeyAlias)
    } catch (_: Exception) {
        throw AppLockIntegrityException()
    }

    private fun calculateMac(key: SecretKey, data: ByteArray): ByteArray = try {
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(key)
            doFinal(data)
        }
    } catch (_: Exception) {
        throw AppLockIntegrityException()
    } finally {
        data.fill(0)
    }

    private fun readString(name: String): String = try {
        preferences.getString(name, null) ?: integrityFailure()
    } catch (_: AppLockIntegrityException) {
        throw AppLockIntegrityException()
    } catch (_: Exception) {
        integrityFailure()
    }

    private fun readInt(name: String): Int = try {
        preferences.getInt(name, Int.MIN_VALUE)
    } catch (_: Exception) {
        integrityFailure()
    }.also { if (it == Int.MIN_VALUE) integrityFailure() }

    private fun readLong(name: String): Long = try {
        preferences.getLong(name, Long.MIN_VALUE)
    } catch (_: Exception) {
        integrityFailure()
    }.also { if (it == Long.MIN_VALUE) integrityFailure() }

    private fun readBoolean(name: String): Boolean = try {
        preferences.getBoolean(name, false)
    } catch (_: Exception) {
        integrityFailure()
    }

    private fun decode(value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (_: Exception) {
        integrityFailure()
    }

    private fun stateFields(
        salt: String,
        verifier: String,
        iterations: Int,
        failures: Int,
        lockedUntil: Long,
        timeout: Long,
        biometricEnabled: Boolean
    ): List<String> = listOf(
        salt,
        verifier,
        iterations.toString(),
        failures.toString(),
        lockedUntil.toString(),
        timeout.toString(),
        biometricEnabled.toString()
    )

    private fun canonical(fields: List<String>): ByteArray =
        ("android-agent-app-lock-v1\n" + fields.joinToString("\n"))
            .toByteArray(Charsets.UTF_8)

    private fun integrityFailure(): Nothing = throw AppLockIntegrityException()

    override fun toString(): String = "AndroidAppLockStateStore(credentials=<redacted>)"

    private companion object {
        const val PREFERENCES_NAME = "agent_app_lock_state"
        const val INTEGRITY_KEY_ALIAS = "android_agent_app_lock_integrity_v1"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val FORMAT_VERSION = 1
        const val MIN_ITERATIONS = 100_000
        const val MAX_ITERATIONS = 500_000
        const val KEY_VERSION = "format_version"
        const val KEY_SALT = "pin_salt"
        const val KEY_VERIFIER = "pin_verifier"
        const val KEY_ITERATIONS = "pbkdf2_iterations"
        const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKED_UNTIL = "locked_until_epoch_ms"
        const val KEY_BACKGROUND_TIMEOUT = "background_timeout_ms"
        const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        const val KEY_MAC = "integrity_mac"
        const val FIELD_SALT = 0
        const val FIELD_VERIFIER = 1
        val STATE_KEYS = listOf(
            KEY_VERSION, KEY_SALT, KEY_VERIFIER, KEY_ITERATIONS,
            KEY_FAILED_ATTEMPTS, KEY_LOCKED_UNTIL, KEY_BACKGROUND_TIMEOUT,
            KEY_BIOMETRIC_ENABLED, KEY_MAC
        )
    }
}
