package security

import memory.AesGcmCipher
import memory.AndroidKeystoreKeyProvider
import memory.KeystoreKeyProvider
import persistence.AgentDatabase
import persistence.ProviderSecretEntity
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * GEN-579 / GEN-580 provider credential storage contract. Provider identifiers are public labels;
 * the corresponding credential value is only ever passed to the encrypt/decrypt boundary.
 */
interface SecretManager {
    fun storeSecret(alias: String, secretValue: String)
    fun getSecret(alias: String): String?
    fun removeSecret(alias: String): Boolean
    fun clearAllSecrets()
    fun listSecretAliases(): List<String>
}

/** Safe, message-only error type: it deliberately carries no provider value, credential, or cause. */
class SecretStorageException internal constructor(message: String) : IllegalStateException(message)

/**
 * Durable provider credential vault.
 *
 * Each provider ID maps to a separately generated, non-exportable Android Keystore AES key. Only
 * AES-GCM ciphertext is persisted in the Room `provider_secrets` table. Provider IDs are hashed
 * before being used in Keystore aliases, and deletion removes both the database row and key.
 *
 * Production uses Android Keystore by default. The alternate key provider constructor parameter is
 * an injection seam for JVM/Robolectric tests; production wiring must not supply a JVM key provider.
 */
class KeystoreSecretManager(
    private val database: AgentDatabase,
    private val keyProvider: KeystoreKeyProvider = AndroidKeystoreKeyProvider()
) : SecretManager {

    override fun storeSecret(alias: String, secretValue: String) = operationLock.withLock {
        validateProviderId(alias)
        try {
            val plainBytes = secretValue.toByteArray(Charsets.UTF_8)
            val ciphertext = try {
                cipherFor(alias).encryptBytes(plainBytes)
            } finally {
                plainBytes.fill(0)
            }
            database.providerSecretDao().upsert(
                ProviderSecretEntity(providerId = alias, ciphertext = ciphertext)
            )
        } catch (_: Exception) {
            throw SecretStorageException(STORE_FAILURE)
        }
        Unit
    }

    override fun getSecret(alias: String): String? = operationLock.withLock {
        validateProviderId(alias)
        val stored = try {
            database.providerSecretDao().getByProviderId(alias)
        } catch (_: Exception) {
            throw SecretStorageException(READ_FAILURE)
        } ?: return@withLock null

        val cipher = try {
            if (keyProvider.getSecretKey(keyAliasFor(alias)) == null) return@withLock null
            cipherFor(alias)
        } catch (_: Exception) {
            return@withLock null
        }
        val plainBytes = try {
            cipher.decryptBytes(stored.ciphertext)
        } catch (_: Exception) {
            // Missing or invalid key/ciphertext never falls back to unauthenticated plaintext.
            return@withLock null
        }
        try {
            String(plainBytes, Charsets.UTF_8)
        } finally {
            plainBytes.fill(0)
        }
    }

    override fun removeSecret(alias: String): Boolean = operationLock.withLock {
        validateProviderId(alias)
        var failed = false
        var rowDeleted = false
        var keyDeleted = false
        try {
            rowDeleted = database.providerSecretDao().deleteByProviderId(alias) > 0
        } catch (_: Exception) {
            failed = true
        }
        // Attempt key deletion even if SQLite failed; report failure without retaining its cause.
        try {
            keyDeleted = keyProvider.deleteSecretKey(keyAliasFor(alias))
        } catch (_: Exception) {
            failed = true
        }
        if (failed) throw SecretStorageException(REMOVE_FAILURE)
        rowDeleted || keyDeleted
    }

    override fun clearAllSecrets() = operationLock.withLock {
        var failure = false
        try {
            database.providerSecretDao().deleteAll()
        } catch (_: Exception) {
            failure = true
        }
        // Try key cleanup even if SQLite failed so credentials are not left decryptable.
        try {
            keyProvider.deleteSecretKeysWithPrefix(KEY_ALIAS_NAMESPACE)
        } catch (_: Exception) {
            failure = true
        }
        if (failure) throw SecretStorageException(CLEAR_FAILURE)
        Unit
    }

    override fun listSecretAliases(): List<String> = operationLock.withLock {
        try {
            database.providerSecretDao().getProviderIds()
        } catch (_: Exception) {
            throw SecretStorageException(READ_FAILURE)
        }
    }

    private fun cipherFor(providerId: String): AesGcmCipher =
        AesGcmCipher(keyProvider = keyProvider, keyAlias = keyAliasFor(providerId))

    private fun keyAliasFor(providerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(providerId.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
        return "$KEY_ALIAS_NAMESPACE$KEY_ALIAS_VERSION$hex"
    }

    private fun validateProviderId(providerId: String) {
        if (providerId.isBlank()) throw IllegalArgumentException("Provider ID must not be blank")
    }

    private companion object {
        const val KEY_ALIAS_NAMESPACE = "android_agent_provider_api_key_"
        const val KEY_ALIAS_VERSION = "v1_"
        const val STORE_FAILURE = "Unable to store provider credential"
        const val READ_FAILURE = "Unable to read provider credential"
        const val REMOVE_FAILURE = "Unable to remove provider credential"
        const val CLEAR_FAILURE = "Unable to clear provider credentials"
        const val HEX = "0123456789abcdef"
        val operationLock = ReentrantLock()
    }
}
