package security

import memory.AesGcmCipher
import memory.KeystoreKeyProvider
import memory.StandardKeystoreKeyProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * GEN-578 — Secure Storage
 * Directly unified with GEN-MEM-6 (AES-256 GCM + Android Keystore Provider).
 *
 * GEN-579 / GEN-580 — Secret Management & Credential Isolation
 * Requirements:
 * 1. Zero hardcoded secrets in source code or plaintext configuration.
 * 2. Stored exclusively in Keystore-backed encrypted storage.
 * 3. Each provider key is stored and encrypted in isolation (exposure of one does not compromise others).
 * 4. Panic Reset wipes all provider API credentials completely.
 */
interface SecretManager {
    fun storeSecret(alias: String, secretValue: String)
    fun getSecret(alias: String): String?
    fun removeSecret(alias: String): Boolean
    fun clearAllSecrets()
    fun listSecretAliases(): List<String>
}

class KeystoreSecretManager(
    private val cipher: AesGcmCipher = AesGcmCipher(
        keyProvider = StandardKeystoreKeyProvider(),
        keyAlias = "android_agent_secret_vault_key"
    )
) : SecretManager {

    private val encryptedVault = ConcurrentHashMap<String, String>()

    override fun storeSecret(alias: String, secretValue: String) {
        val encrypted = cipher.encrypt(secretValue)
        encryptedVault[alias] = encrypted
        runtime.AgentLogger.i("SecretManager", "Secret '$alias' stored securely with AES-256 GCM.")
    }

    override fun getSecret(alias: String): String? {
        val encrypted = encryptedVault[alias] ?: return null
        return try {
            cipher.decrypt(encrypted)
        } catch (e: Exception) {
            runtime.AgentLogger.e("SecretManager", "Failed to decrypt secret '$alias': ${e.message}")
            null
        }
    }

    override fun removeSecret(alias: String): Boolean {
        return encryptedVault.remove(alias) != null
    }

    override fun clearAllSecrets() {
        encryptedVault.clear()
        runtime.AgentLogger.w("SecretManager", "All secrets in vault destroyed.")
    }

    override fun listSecretAliases(): List<String> = encryptedVault.keys().toList()
}
