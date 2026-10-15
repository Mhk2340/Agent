package testutil

import android.content.Context
import androidx.room.Room
import memory.AesGcmCipher
import memory.KeystoreKeyProvider
import persistence.AgentDatabase
import persistence.AgentMigrations
import security.IntegrityMacKeyProvider
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Test-only JVM key provider; never referenced by production source or production defaults. */
class TestOnlyKeystoreKeyProvider : KeystoreKeyProvider {
    private val keys = ConcurrentHashMap<String, SecretKey>()

    override fun getOrCreateSecretKey(alias: String): SecretKey = keys.computeIfAbsent(alias) {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        SecretKeySpec(keyBytes, "AES")
    }

    override fun getSecretKey(alias: String): SecretKey? = keys[alias]

    private val deletionFailures = ConcurrentHashMap<String, String>()

    override fun deleteSecretKey(alias: String): Boolean {
        deletionFailures[alias]?.let { throw IllegalStateException(it) }
        return keys.remove(alias) != null
    }

    override fun deleteSecretKeysWithPrefix(prefix: String): Int {
        val matchingAliases = keys.keys.filter { it.startsWith(prefix) }
        var deleted = 0
        var failed = false
        matchingAliases.forEach { alias ->
            try {
                if (deleteSecretKey(alias)) deleted++
            } catch (_: Exception) {
                // Match the production provider contract: finish the namespace sweep first.
                failed = true
            }
        }
        if (failed) throw IllegalStateException("Unable to delete all namespaced test keys")
        return deleted
    }

    fun failSecretKeyDeletionForTest(alias: String, message: String) {
        deletionFailures[alias] = message
    }

    fun allowSecretKeyDeletionForTest(alias: String) {
        deletionFailures.remove(alias)
    }

    fun aliases(): Set<String> = keys.keys.toSet()

    fun clearAllKeysForTest() {
        deletionFailures.clear()
        keys.clear()
    }
}

/** Test-only HMAC keys for exercising production integrity-store code under Robolectric. */
class TestOnlyIntegrityMacKeyProvider : IntegrityMacKeyProvider {
    private val keys = ConcurrentHashMap<String, SecretKey>()

    override fun getExistingKey(alias: String): SecretKey? = keys[alias]

    override fun getOrCreateKey(alias: String): SecretKey = keys.computeIfAbsent(alias) {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        SecretKeySpec(keyBytes, "HmacSHA256")
    }

    override fun deleteKey(alias: String): Boolean = keys.remove(alias) != null

    fun deleteKeyForTest(alias: String) {
        keys.remove(alias)
    }

    fun clearForTest() {
        keys.clear()
    }
}

object RoomTestEnvironment {
    fun newCipher(keyProvider: TestOnlyKeystoreKeyProvider = TestOnlyKeystoreKeyProvider()) =
        AesGcmCipher(keyProvider = keyProvider)

    fun inMemoryDatabase(context: Context, cipher: AesGcmCipher): AgentDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .addMigrations(AgentMigrations.migration1To2(cipher), AgentMigrations.migration2To3())
            .addCallback(AgentMigrations.schemaCallback())
            .build()

    fun fileDatabase(context: Context, name: String, cipher: AesGcmCipher): AgentDatabase =
        Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(AgentMigrations.migration1To2(cipher), AgentMigrations.migration2To3())
            .addCallback(AgentMigrations.schemaCallback())
            .build()
}
