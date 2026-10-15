package security

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Domain-level gate shared by skill execution, memory access, and settings operations. */
fun interface AppLockAccess {
    fun isAppUnlocked(): Boolean
}

/** Pure status exported to the UI; it deliberately contains no PIN, salt, or verifier. */
enum class AppLockStatus {
    PIN_SETUP_REQUIRED,
    LOCKED,
    LOCKED_OUT,
    UNLOCKED,
    INTEGRITY_FAILURE
}

/** Pure state-machine rules; no Android APIs, persistence, or View types. */
object AppLockStateMachine {
    // PROVISIONAL: one minute is the initial background grace period; device testing may recalibrate it.
    const val DEFAULT_BACKGROUND_TIMEOUT_MS: Long = 60_000L
    const val MIN_BACKGROUND_TIMEOUT_MS: Long = 0L
    const val MAX_BACKGROUND_TIMEOUT_MS: Long = 24L * 60L * 60L * 1000L
    const val MIN_PIN_LENGTH: Int = 6
    const val MAX_PIN_LENGTH: Int = 12
    const val PBKDF2_ITERATIONS: Int = 210_000
    const val PIN_SALT_BYTES: Int = 16
    const val PIN_HASH_BYTES: Int = 32
    const val FIRST_BACKOFF_FAILURE: Int = 5
    const val INITIAL_BACKOFF_MS: Long = 30_000L
    const val MAX_BACKOFF_MS: Long = 60L * 60L * 1000L

    data class BackoffState(val failedAttempts: Int, val lockedUntilEpochMs: Long)

    /** The 5th failed PIN attempt starts a 30-second lockout, then delay doubles up to one hour. */
    fun afterFailedAttempt(previousFailures: Int, nowEpochMs: Long): BackoffState {
        val failures = if (previousFailures == Int.MAX_VALUE) Int.MAX_VALUE else previousFailures + 1
        if (failures < FIRST_BACKOFF_FAILURE) return BackoffState(failures, 0L)
        val exponent = (failures - FIRST_BACKOFF_FAILURE).coerceAtMost(16)
        val multiplier = 1L shl exponent
        val delay = (INITIAL_BACKOFF_MS * multiplier).coerceAtMost(MAX_BACKOFF_MS)
        val until = try {
            Math.addExact(nowEpochMs, delay)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        return BackoffState(failures, until)
    }

    fun lockoutRemainingMs(lockedUntilEpochMs: Long, nowEpochMs: Long): Long =
        (lockedUntilEpochMs - nowEpochMs).coerceAtLeast(0L)

    fun backgroundTimeoutExpired(backgroundAtEpochMs: Long, foregroundAtEpochMs: Long, timeoutMs: Long): Boolean {
        if (timeoutMs !in MIN_BACKGROUND_TIMEOUT_MS..MAX_BACKGROUND_TIMEOUT_MS) return true
        if (foregroundAtEpochMs < backgroundAtEpochMs) return true
        return foregroundAtEpochMs - backgroundAtEpochMs >= timeoutMs
    }

    fun isValidPin(pin: CharArray): Boolean =
        pin.size in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }
}

/**
 * Persistent app-lock fields. Credential material is intentionally not exposed through properties
 * or the generated data-class toString; the only representation is redacted.
 */
class AppLockPersistentState internal constructor(
    private val saltBase64: String,
    private val verifierBase64: String,
    val iterations: Int,
    val failedAttempts: Int,
    val lockedUntilEpochMs: Long,
    val backgroundTimeoutMs: Long,
    val biometricEnabled: Boolean
) {
    internal fun saltBytes(): ByteArray = Base64.getDecoder().decode(saltBase64)
    internal fun verifierBytes(): ByteArray = Base64.getDecoder().decode(verifierBase64)

    internal fun withAuthenticationState(failures: Int, lockoutUntil: Long): AppLockPersistentState =
        AppLockPersistentState(
            saltBase64, verifierBase64, iterations, failures, lockoutUntil,
            backgroundTimeoutMs, biometricEnabled
        )

    internal fun withSettings(timeoutMs: Long = backgroundTimeoutMs, useBiometric: Boolean = biometricEnabled): AppLockPersistentState =
        AppLockPersistentState(
            saltBase64, verifierBase64, iterations, failedAttempts, lockedUntilEpochMs,
            timeoutMs, useBiometric
        )

    internal fun canonicalFields(): List<String> = listOf(
        saltBase64,
        verifierBase64,
        iterations.toString(),
        failedAttempts.toString(),
        lockedUntilEpochMs.toString(),
        backgroundTimeoutMs.toString(),
        biometricEnabled.toString()
    )

    override fun toString(): String =
        "AppLockPersistentState(credentials=<redacted>, failedAttempts=$failedAttempts, " +
            "lockedUntilEpochMs=$lockedUntilEpochMs, backgroundTimeoutMs=$backgroundTimeoutMs, " +
            "biometricEnabled=$biometricEnabled)"

    internal companion object {
        fun create(salt: ByteArray, verifier: ByteArray, iterations: Int): AppLockPersistentState =
            AppLockPersistentState(
                saltBase64 = Base64.getEncoder().withoutPadding().encodeToString(salt),
                verifierBase64 = Base64.getEncoder().withoutPadding().encodeToString(verifier),
                iterations = iterations,
                failedAttempts = 0,
                lockedUntilEpochMs = 0L,
                backgroundTimeoutMs = AppLockStateMachine.DEFAULT_BACKGROUND_TIMEOUT_MS,
                biometricEnabled = false
            )
    }
}

sealed class AppLockStoreRead {
    object Empty : AppLockStoreRead() {
        override fun toString(): String = "AppLockStoreRead.Empty"
    }

    class Configured internal constructor(internal val state: AppLockPersistentState) : AppLockStoreRead() {
        override fun toString(): String = "AppLockStoreRead.Configured(credentials=<redacted>)"
    }
}

/** Synchronous persistence boundary because callers must know whether a security transition stuck. */
interface AppLockStateStore {
    fun read(): AppLockStoreRead
    fun write(state: AppLockPersistentState)
}

/** TEST ONLY in-memory state store; production AgentContainer uses AndroidAppLockStateStore. */
class InMemoryAppLockStateStore : AppLockStateStore {
    private var saved: AppLockPersistentState? = null

    @Synchronized
    override fun read(): AppLockStoreRead = saved?.let(AppLockStoreRead::Configured) ?: AppLockStoreRead.Empty

    @Synchronized
    override fun write(state: AppLockPersistentState) {
        saved = state
    }

    @Synchronized
    fun clearForTest() {
        saved = null
    }

    override fun toString(): String = "InMemoryAppLockStateStore(credentials=<redacted>)"
}

sealed class AppLockOperationResult {
    object Success : AppLockOperationResult()
    object SetupRequired : AppLockOperationResult()
    object AlreadyConfigured : AppLockOperationResult()
    object PinMismatch : AppLockOperationResult()
    object InvalidPinFormat : AppLockOperationResult()
    object IncorrectPin : AppLockOperationResult()
    object StorageFailure : AppLockOperationResult()
    object IntegrityFailure : AppLockOperationResult()
    object AuthenticationRequired : AppLockOperationResult()
    data class LockedOut(val remainingSeconds: Long) : AppLockOperationResult()
}

/** PBKDF2-HMAC-SHA256 pin verifier. PIN arrays and derived verifier bytes are cleared by callers. */
class Pbkdf2PinHasher {
    fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations in MIN_ALLOWED_ITERATIONS..MAX_ALLOWED_ITERATIONS)
        val spec = PBEKeySpec(pin, salt, iterations, AppLockStateMachine.PIN_HASH_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val MIN_ALLOWED_ITERATIONS = 100_000
        const val MAX_ALLOWED_ITERATIONS = 500_000
    }
}

/**
 * Application-level lock session manager. The verifier/backoff/settings persist through the store;
 * the unlocked session never persists and therefore starts locked after process restart.
 */
class AppLockManager(
    private val store: AppLockStateStore,
    private val hasher: Pbkdf2PinHasher = Pbkdf2PinHasher(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom()
) : AppLockAccess {
    private val lock = Any()
    @Volatile private var sessionUnlocked = false
    @Volatile private var backgroundedAtEpochMs: Long? = null
    @Volatile private var integrityFailed = false

    fun status(): AppLockStatus = synchronized(lock) {
        val state = readStateOrNull() ?: return@synchronized if (integrityFailed) {
            AppLockStatus.INTEGRITY_FAILURE
        } else {
            AppLockStatus.PIN_SETUP_REQUIRED
        }
        if (integrityFailed) return@synchronized AppLockStatus.INTEGRITY_FAILURE
        if (state.lockedUntilEpochMs > clock()) {
            sessionUnlocked = false
            return@synchronized AppLockStatus.LOCKED_OUT
        }
        if (sessionUnlocked && backgroundTimeoutExpired(state)) sessionUnlocked = false
        if (sessionUnlocked) AppLockStatus.UNLOCKED else AppLockStatus.LOCKED
    }

    fun isConfigured(): Boolean = when (status()) {
        AppLockStatus.PIN_SETUP_REQUIRED, AppLockStatus.INTEGRITY_FAILURE -> false
        AppLockStatus.LOCKED, AppLockStatus.LOCKED_OUT, AppLockStatus.UNLOCKED -> true
    }

    override fun isAppUnlocked(): Boolean = synchronized(lock) {
        if (!sessionUnlocked || integrityFailed) return@synchronized false
        val state = readStateOrNull() ?: run {
            sessionUnlocked = false
            return@synchronized false
        }
        if (state.lockedUntilEpochMs > clock() || backgroundTimeoutExpired(state)) {
            sessionUnlocked = false
            backgroundedAtEpochMs = null
            return@synchronized false
        }
        true
    }

    /** First-run setup is mandatory; no implicit/unconfigured-unlocked state exists. */
    fun configurePin(pin: CharArray, confirmation: CharArray): AppLockOperationResult = synchronized(lock) {
        try {
            if (!AppLockStateMachine.isValidPin(pin) || !AppLockStateMachine.isValidPin(confirmation)) {
                return@synchronized AppLockOperationResult.InvalidPinFormat
            }
            val firstBytes = charsToUtf8(pin)
            val secondBytes = charsToUtf8(confirmation)
            val match = try {
                MessageDigest.isEqual(firstBytes, secondBytes)
            } finally {
                firstBytes.fill(0)
                secondBytes.fill(0)
            }
            if (!match) return@synchronized AppLockOperationResult.PinMismatch

            when (readStore()) {
                AppLockStoreRead.Empty -> Unit
                is AppLockStoreRead.Configured -> return@synchronized AppLockOperationResult.AlreadyConfigured
            }
            val salt = ByteArray(AppLockStateMachine.PIN_SALT_BYTES).also(random::nextBytes)
            val verifier = hasher.derive(pin, salt, AppLockStateMachine.PBKDF2_ITERATIONS)
            try {
                store.write(AppLockPersistentState.create(salt, verifier, AppLockStateMachine.PBKDF2_ITERATIONS))
            } catch (_: Exception) {
                sessionUnlocked = false
                return@synchronized AppLockOperationResult.StorageFailure
            } finally {
                salt.fill(0)
                verifier.fill(0)
            }
            integrityFailed = false
            sessionUnlocked = true
            backgroundedAtEpochMs = null
            AppLockOperationResult.Success
        } catch (_: AppLockIntegrityException) {
            integrityFailed = true
            sessionUnlocked = false
            AppLockOperationResult.IntegrityFailure
        } catch (_: Exception) {
            sessionUnlocked = false
            AppLockOperationResult.StorageFailure
        } finally {
            pin.fill('\u0000')
            confirmation.fill('\u0000')
        }
    }

    fun unlockWithPin(pin: CharArray): AppLockOperationResult = synchronized(lock) {
        try {
            val state = when (val read = readStore()) {
                AppLockStoreRead.Empty -> {
                    sessionUnlocked = false
                    return@synchronized AppLockOperationResult.SetupRequired
                }
                is AppLockStoreRead.Configured -> read.state
            }
            val now = clock()
            val remaining = AppLockStateMachine.lockoutRemainingMs(state.lockedUntilEpochMs, now)
            if (remaining > 0L) {
                sessionUnlocked = false
                return@synchronized AppLockOperationResult.LockedOut((remaining + 999L) / 1000L)
            }
            if (!AppLockStateMachine.isValidPin(pin)) {
                return@synchronized AppLockOperationResult.InvalidPinFormat
            }
            val salt = state.saltBytes()
            val candidate = try {
                hasher.derive(pin, salt, state.iterations)
            } finally {
                salt.fill(0)
            }
            val expected = state.verifierBytes()
            val correct = try {
                MessageDigest.isEqual(candidate, expected)
            } finally {
                candidate.fill(0)
                expected.fill(0)
            }
            if (correct) {
                try {
                    store.write(state.withAuthenticationState(0, 0L))
                } catch (_: Exception) {
                    sessionUnlocked = false
                    return@synchronized AppLockOperationResult.StorageFailure
                }
                integrityFailed = false
                sessionUnlocked = true
                backgroundedAtEpochMs = null
                AppLockOperationResult.Success
            } else {
                val backoff = AppLockStateMachine.afterFailedAttempt(state.failedAttempts, now)
                try {
                    store.write(state.withAuthenticationState(backoff.failedAttempts, backoff.lockedUntilEpochMs))
                } catch (_: Exception) {
                    sessionUnlocked = false
                    return@synchronized AppLockOperationResult.StorageFailure
                }
                sessionUnlocked = false
                if (backoff.lockedUntilEpochMs > now) {
                    AppLockOperationResult.LockedOut((backoff.lockedUntilEpochMs - now + 999L) / 1000L)
                } else {
                    AppLockOperationResult.IncorrectPin
                }
            }
        } catch (_: AppLockIntegrityException) {
            integrityFailed = true
            sessionUnlocked = false
            AppLockOperationResult.IntegrityFailure
        } catch (_: Exception) {
            sessionUnlocked = false
            AppLockOperationResult.StorageFailure
        } finally {
            pin.fill('\u0000')
        }
    }

    /** Successful strong-biometrics callback can unlock only an enrolled, already PIN-configured app. */
    fun unlockAfterBiometric(): Boolean = synchronized(lock) {
        try {
            val state = readConfiguredState() ?: return@synchronized false
            if (!state.biometricEnabled) return@synchronized false
            store.write(state.withAuthenticationState(0, 0L))
            integrityFailed = false
            sessionUnlocked = true
            backgroundedAtEpochMs = null
            true
        } catch (_: Exception) {
            sessionUnlocked = false
            integrityFailed = true
            false
        }
    }

    fun setBiometricEnabled(enabled: Boolean): AppLockOperationResult = synchronized(lock) {
        if (!isAppUnlocked()) return@synchronized AppLockOperationResult.AuthenticationRequired
        try {
            val state = readConfiguredState() ?: return@synchronized AppLockOperationResult.SetupRequired
            store.write(state.withSettings(useBiometric = enabled))
            AppLockOperationResult.Success
        } catch (_: AppLockIntegrityException) {
            integrityFailed = true
            sessionUnlocked = false
            AppLockOperationResult.IntegrityFailure
        } catch (_: Exception) {
            AppLockOperationResult.StorageFailure
        }
    }

    fun isBiometricEnabled(): Boolean = synchronized(lock) {
        try {
            readConfiguredState()?.biometricEnabled ?: false
        } catch (_: Exception) {
            integrityFailed = true
            sessionUnlocked = false
            false
        }
    }

    fun setBackgroundTimeout(timeoutMs: Long): AppLockOperationResult = synchronized(lock) {
        if (!isAppUnlocked()) return@synchronized AppLockOperationResult.AuthenticationRequired
        if (timeoutMs !in AppLockStateMachine.MIN_BACKGROUND_TIMEOUT_MS..AppLockStateMachine.MAX_BACKGROUND_TIMEOUT_MS) {
            return@synchronized AppLockOperationResult.StorageFailure
        }
        try {
            val state = readConfiguredState() ?: return@synchronized AppLockOperationResult.SetupRequired
            store.write(state.withSettings(timeoutMs = timeoutMs))
            AppLockOperationResult.Success
        } catch (_: AppLockIntegrityException) {
            integrityFailed = true
            sessionUnlocked = false
            AppLockOperationResult.IntegrityFailure
        } catch (_: Exception) {
            AppLockOperationResult.StorageFailure
        }
    }

    fun backgroundTimeoutMs(): Long = synchronized(lock) {
        try {
            readConfiguredState()?.backgroundTimeoutMs ?: AppLockStateMachine.DEFAULT_BACKGROUND_TIMEOUT_MS
        } catch (_: Exception) {
            integrityFailed = true
            sessionUnlocked = false
            AppLockStateMachine.DEFAULT_BACKGROUND_TIMEOUT_MS
        }
    }

    fun lock() = synchronized(lock) {
        sessionUnlocked = false
        backgroundedAtEpochMs = null
    }

    fun onAppMovedToBackground() = synchronized(lock) {
        if (isAppUnlocked()) backgroundedAtEpochMs = clock()
    }

    fun onAppMovedToForeground() = synchronized(lock) {
        val backgroundAt = backgroundedAtEpochMs ?: return@synchronized
        try {
            val state = readConfiguredState() ?: run {
                sessionUnlocked = false
                backgroundedAtEpochMs = null
                return@synchronized
            }
            if (AppLockStateMachine.backgroundTimeoutExpired(backgroundAt, clock(), state.backgroundTimeoutMs)) {
                sessionUnlocked = false
            }
        } catch (_: Exception) {
            integrityFailed = true
            sessionUnlocked = false
        } finally {
            backgroundedAtEpochMs = null
        }
    }

    private fun backgroundTimeoutExpired(state: AppLockPersistentState): Boolean {
        val backgroundAt = backgroundedAtEpochMs ?: return false
        return AppLockStateMachine.backgroundTimeoutExpired(backgroundAt, clock(), state.backgroundTimeoutMs)
    }

    private fun readConfiguredState(): AppLockPersistentState? = when (val read = readStore()) {
        AppLockStoreRead.Empty -> null
        is AppLockStoreRead.Configured -> read.state
    }

    private fun readStateOrNull(): AppLockPersistentState? = try {
        readConfiguredState()
    } catch (_: Exception) {
        integrityFailed = true
        sessionUnlocked = false
        null
    }

    private fun readStore(): AppLockStoreRead = try {
        store.read()
    } catch (_: Exception) {
        integrityFailed = true
        sessionUnlocked = false
        throw AppLockIntegrityException()
    }

    private fun charsToUtf8(chars: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
        return ByteArray(buffer.remaining()).also { bytes ->
            buffer.get(bytes)
            if (buffer.hasArray()) buffer.array().fill(0)
        }
    }

    override fun toString(): String = "AppLockManager(session=<redacted>, persistedCredentials=<redacted>)"
}

/** Cause-free integrity error. */
class AppLockIntegrityException : SecurityException("App Lock integrity validation failed")