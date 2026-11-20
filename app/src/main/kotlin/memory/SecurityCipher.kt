package memory

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Supplies non-exportable AES keys from Android Keystore. There is no production fallback. */
interface KeystoreKeyProvider {
    fun getOrCreateSecretKey(alias: String): SecretKey

    /** Reads an existing key without creating a new one; null means the alias is absent. */
    fun getSecretKey(alias: String): SecretKey?

    /** Deletes one non-exportable key alias. False means the alias was already absent. */
    fun deleteSecretKey(alias: String): Boolean

    /**
     * Attempts to delete every alias under a dedicated namespace, including orphaned credential
     * keys. Implementations must finish the sweep before reporting aggregate failure.
     */
    fun deleteSecretKeysWithPrefix(prefix: String): Int
}

/**
 * Production key provider. Keys are generated and retained by Android Keystore; this class never
 * exports key bytes or falls back to a JVM/in-memory key.
 */
class AndroidKeystoreKeyProvider : KeystoreKeyProvider {
    override fun getOrCreateSecretKey(alias: String): SecretKey = synchronized(lock) {
        val keyStore = loadKeyStore()
        (keyStore.getKey(alias, null) as? SecretKey) ?: generateKey(alias)
    }

    override fun getSecretKey(alias: String): SecretKey? = synchronized(lock) {
        loadKeyStore().getKey(alias, null) as? SecretKey
    }

    override fun deleteSecretKey(alias: String): Boolean = synchronized(lock) {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(alias)) return@synchronized false
        keyStore.deleteEntry(alias)
        true
    }

    override fun deleteSecretKeysWithPrefix(prefix: String): Int = synchronized(lock) {
        require(prefix.isNotBlank()) { "Keystore alias prefix must not be blank" }
        val keyStore = loadKeyStore()
        val aliases = mutableListOf<String>()
        val enumeration = keyStore.aliases()
        while (enumeration.hasMoreElements()) {
            val alias = enumeration.nextElement()
            if (alias.startsWith(prefix)) aliases.add(alias)
        }

        var deleted = 0
        var failed = false
        aliases.forEach { alias ->
            try {
                if (keyStore.containsAlias(alias)) {
                    keyStore.deleteEntry(alias)
                    deleted++
                }
            } catch (_: Exception) {
                failed = true
            }
        }
        if (failed) throw IllegalStateException("Unable to delete all namespaced Keystore keys")
        deleted
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }

    private fun generateKey(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE_PROVIDER)
        val parameters = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_256_KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(parameters)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        // AES-256 is an explicit GEN-MEM-6 requirement.
        const val AES_256_KEY_SIZE_BITS = 256
        val lock = Any()
    }
}

/**
 * AES-256-GCM content encryption. String payloads are stored as Base64-encoded IV+ciphertext;
 * binary payloads (e.g. embeddings) are stored as IV+ciphertext BLOBs. Authentication failures
 * are surfaced; data is never returned as plaintext on decryption failure.
 */
class AesGcmCipher(
    private val keyProvider: KeystoreKeyProvider = AndroidKeystoreKeyProvider(),
    private val keyAlias: String = DEFAULT_DATABASE_KEY_ALIAS
) {
    fun encrypt(plainText: String): String =
        Base64.getEncoder().encodeToString(encryptBytes(plainText.toByteArray(Charsets.UTF_8)))

    fun decrypt(cipherTextBase64: String): String =
        String(decryptBytes(Base64.getDecoder().decode(cipherTextBase64)), Charsets.UTF_8)

    fun encryptBytes(plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreateSecretKey(keyAlias))
        val iv = requireNotNull(cipher.iv) { "The AES-GCM provider did not supply an IV" }
        require(iv.size == IV_LENGTH_BYTES) { "The AES-GCM provider supplied an unexpected IV length" }
        val encrypted = cipher.doFinal(plainBytes)
        return ByteArray(iv.size + encrypted.size).also { combined ->
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        }
    }

    fun decryptBytes(cipherText: ByteArray): ByteArray {
        require(cipherText.size > IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) { "Invalid AES-GCM payload length" }
        val iv = cipherText.copyOfRange(0, IV_LENGTH_BYTES)
        val encrypted = cipherText.copyOfRange(IV_LENGTH_BYTES, cipherText.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider.getOrCreateSecretKey(keyAlias), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    companion object {
        const val DEFAULT_DATABASE_KEY_ALIAS = "android_agent_memory_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        // PROVISIONAL cryptographic parameters: roadmap specifies AES-256-GCM but not tag/IV lengths.
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
        private const val IV_LENGTH_BYTES = 12
    }
}
