package security

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import memory.AesGcmCipher
import memory.EncryptedMemoryStorage
import memory.KeystoreKeyProvider
import memory.LongTermMemoryEntry
import memory.MemoryType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import persistence.ActionIdempotencyEntity
import persistence.AgentDatabase
import persistence.FloatVectorCodec
import runtime.AgentLogger
import testutil.RoomTestEnvironment
import testutil.TestOnlyKeystoreKeyProvider
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKey

// PROVISIONAL: API 28 covers Room/file persistence in Robolectric; it does not establish API 34 Keystore behavior.
@Config(sdk = [28], manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class KeystoreSecretManagerPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun providerCredentialsSurviveClosingAndReopeningFileBackedRoom() {
        val databaseName = "provider-secrets-${UUID.randomUUID()}.db"
        val testKeys = TestOnlyKeystoreKeyProvider()
        val databaseCipher = AesGcmCipher(testKeys, "test-memory-store")
        val geminiSecret = "gemini-test-secret-that-must-not-be-stored-as-plaintext"
        val groqSecret = "groq-test-secret-that-must-not-be-stored-as-plaintext"

        try {
            val firstDatabase = RoomTestEnvironment.fileDatabase(context, databaseName, databaseCipher)
            try {
                val manager = KeystoreSecretManager(firstDatabase, testKeys)
                manager.storeSecret("gemini", geminiSecret)
                manager.storeSecret("groq", groqSecret)

                assertEquals(geminiSecret, manager.getSecret("gemini"))
                assertEquals(groqSecret, manager.getSecret("groq"))
                assertFalse(
                    "Raw provider BLOB must not contain the credential bytes",
                    containsBytes(
                        firstDatabase.providerSecretDao().getByProviderId("gemini")!!.ciphertext,
                        geminiSecret.toByteArray(Charsets.UTF_8)
                    )
                )
                assertFalse(
                    firstDatabase.providerSecretDao().getByProviderId("gemini")!!.toString()
                        .contains(geminiSecret)
                )
                assertFalse(manager.toString().contains(geminiSecret))
                assertTrue(firstDatabase.actionAuditDao().getAll().isEmpty())

                val providerKeyAliases = testKeys.aliases().filter(::isProviderKeyAlias)
                assertEquals("Each provider must have its own Keystore alias", 2, providerKeyAliases.size)
                assertEquals(2, providerKeyAliases.toSet().size)
                assertEquals(
                    "Each provider must receive independent AES key material",
                    2,
                    providerKeyAliases.map { testKeys.getSecretKey(it)!!.encoded.toList() }.toSet().size
                )
            } finally {
                firstDatabase.close()
            }

            // The test key provider stands in for Android Keystore's persistence across app restarts;
            // the credential rows themselves are read from a newly opened SQLite/Room instance.
            val reopenedDatabase = RoomTestEnvironment.fileDatabase(context, databaseName, databaseCipher)
            try {
                val reopenedManager = KeystoreSecretManager(reopenedDatabase, testKeys)
                assertEquals(geminiSecret, reopenedManager.getSecret("gemini"))
                assertEquals(groqSecret, reopenedManager.getSecret("groq"))
                assertEquals(listOf("gemini", "groq"), reopenedManager.listSecretAliases())
            } finally {
                reopenedDatabase.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
            testKeys.clearAllKeysForTest()
        }
    }

    @Test
    fun versionTwoDatabaseMigratesToThreePreservingEncryptedMemoryAndIdempotency() {
        val databaseName = "provider-secrets-v2-${UUID.randomUUID()}.db"
        val testKeys = TestOnlyKeystoreKeyProvider()
        val databaseCipher = AesGcmCipher(testKeys, "test-memory-store")
        val memoryContent = "legacy-v2-memory-content"
        val embedding = listOf(0.125f, -0.5f, 0.75f, 1.0f)
        val encryptedEmbedding = databaseCipher.encryptBytes(FloatVectorCodec.encode(embedding))
        val idempotencyKey = "legacy-action-key-001"

        try {
            createVersionTwoDatabase(
                databaseName = databaseName,
                databaseCipher = databaseCipher,
                memoryContent = memoryContent,
                encryptedEmbedding = encryptedEmbedding,
                idempotencyKey = idempotencyKey
            )

            val migratedDatabase = RoomTestEnvironment.fileDatabase(context, databaseName, databaseCipher)
            try {
                assertEquals(3, migratedDatabase.openHelper.readableDatabase.version)
                assertEquals(1, migratedDatabase.longTermMemoryDao().count())
                val rawMemory = migratedDatabase.longTermMemoryDao().getById("legacy-memory-v2")!!
                assertArrayEquals("Migration must preserve the encrypted embedding bytes", encryptedEmbedding, rawMemory.embedding)

                val decryptedMemory = EncryptedMemoryStorage(migratedDatabase, databaseCipher)
                    .getById("legacy-memory-v2")!!
                assertEquals(memoryContent, decryptedMemory.content)
                assertEquals(MemoryType.SEMANTIC, decryptedMemory.memoryType)
                assertEquals(embedding, decryptedMemory.embedding)
                assertEquals("legacy-origin", decryptedMemory.origin)
                assertEquals(2, decryptedMemory.schemaVersion)

                assertEquals(
                    ActionIdempotencyEntity(idempotencyKey, "IN_FLIGHT"),
                    migratedDatabase.actionIdempotencyDao().findByKeyAndStatus(idempotencyKey)
                )
                assertTrue("New provider_secrets table must be empty after migration", migratedDatabase.providerSecretDao().getProviderIds().isEmpty())
                assertNull(migratedDatabase.providerSecretDao().getByProviderId("gemini"))

                val secretManager = KeystoreSecretManager(migratedDatabase, testKeys)
                val newCredential = "usable-after-v2-to-v3-migration"
                secretManager.storeSecret("gemini", newCredential)
                assertEquals(newCredential, secretManager.getSecret("gemini"))
            } finally {
                migratedDatabase.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
            testKeys.clearAllKeysForTest()
        }
    }

    @Test
    fun removingOneProviderDeletesOnlyItsCiphertextAndKeystoreKeyAndClearRemovesAll() {
        val testKeys = TestOnlyKeystoreKeyProvider()
        val database = RoomTestEnvironment.inMemoryDatabase(
            context,
            AesGcmCipher(testKeys, "test-memory-store")
        )
        try {
            val manager = KeystoreSecretManager(database, testKeys)
            manager.storeSecret("gemini", "gemini-isolated-key-value")
            manager.storeSecret("openrouter", "openrouter-isolated-key-value")
            testKeys.getOrCreateSecretKey("unrelated-workspace-key")

            val keysBeforeRemoval = testKeys.aliases().filter(::isProviderKeyAlias).toSet()
            assertEquals(2, keysBeforeRemoval.size)
            assertTrue(manager.removeSecret("gemini"))
            assertNull(database.providerSecretDao().getByProviderId("gemini"))
            assertEquals("openrouter-isolated-key-value", manager.getSecret("openrouter"))
            assertEquals(1, testKeys.aliases().count(::isProviderKeyAlias))
            assertEquals(listOf("openrouter"), manager.listSecretAliases())

            manager.clearAllSecrets()
            assertTrue(database.providerSecretDao().getProviderIds().isEmpty())
            assertTrue(testKeys.aliases().none(::isProviderKeyAlias))
            assertNotNull("Provider cleanup must not remove unrelated Keystore aliases", testKeys.getSecretKey("unrelated-workspace-key"))
            assertNull(manager.getSecret("openrouter"))
            assertFalse(manager.removeSecret("absent-provider"))
        } finally {
            database.close()
            testKeys.clearAllKeysForTest()
        }
    }

    @Test
    fun removeSecretReportsAliasDeletionFailureAfterDeletingTheRoomRow() {
        val testKeys = TestOnlyKeystoreKeyProvider()
        val database = RoomTestEnvironment.inMemoryDatabase(
            context,
            AesGcmCipher(testKeys, "test-memory-store")
        )
        val secretValue = "remove-failure-test-credential-must-not-leak"
        try {
            val manager = KeystoreSecretManager(database, testKeys)
            manager.storeSecret("gemini", secretValue)
            val providerAlias = testKeys.aliases().single(::isProviderKeyAlias)
            testKeys.failSecretKeyDeletionForTest(providerAlias, secretValue)

            val failure = assertThrows(SecretStorageException::class.java) {
                manager.removeSecret("gemini")
            }

            assertEquals("Unable to remove provider credential", failure.message)
            assertNull("The Room ciphertext row is deleted before the key deletion failure", database.providerSecretDao().getByProviderId("gemini"))
            assertNotNull("Failed Keystore deletion leaves an orphan alias", testKeys.getSecretKey(providerAlias))
            assertFalse(failure.toString().contains(secretValue))
            assertNull("The injected provider failure must not be attached", failure.cause)

            testKeys.allowSecretKeyDeletionForTest(providerAlias)
            manager.clearAllSecrets()
            assertTrue(testKeys.aliases().none(::isProviderKeyAlias))
        } finally {
            database.close()
            testKeys.clearAllKeysForTest()
        }
    }

    @Test
    fun removeSecretDeletesKeystoreAliasEvenWhenRoomRowDeletionFails() {
        val testKeys = TestOnlyKeystoreKeyProvider()
        val database = RoomTestEnvironment.inMemoryDatabase(
            context,
            AesGcmCipher(testKeys, "test-memory-store")
        )
        val secretValue = "row-failure-test-credential-must-not-leak"
        try {
            val manager = KeystoreSecretManager(database, testKeys)
            manager.storeSecret("gemini", secretValue)
            val providerAlias = testKeys.aliases().single(::isProviderKeyAlias)
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER `fail_gemini_provider_secret_delete`
                BEFORE DELETE ON `provider_secrets`
                WHEN OLD.`provider_id` = 'gemini'
                BEGIN
                    SELECT RAISE(ABORT, 'injected provider row deletion failure');
                END
                """.trimIndent()
            )

            val failure = assertThrows(SecretStorageException::class.java) {
                manager.removeSecret("gemini")
            }

            assertEquals("Unable to remove provider credential", failure.message)
            assertNotNull("The injected SQLite failure leaves the Room row", database.providerSecretDao().getByProviderId("gemini"))
            assertNull("Key cleanup is still attempted after SQLite failure", testKeys.getSecretKey(providerAlias))
            assertNull("A surviving row without its Keystore key is unreadable", manager.getSecret("gemini"))
            assertFalse(failure.toString().contains(secretValue))
            assertNull("The SQLite exception must not be attached", failure.cause)

            database.openHelper.writableDatabase.execSQL("DROP TRIGGER `fail_gemini_provider_secret_delete`")
            manager.clearAllSecrets()
            assertTrue(database.providerSecretDao().getProviderIds().isEmpty())
        } finally {
            database.close()
            testKeys.clearAllKeysForTest()
        }
    }

    @Test
    fun clearAllSecretsContinuesPastFailedAliasAndDeletesOtherAndOrphanAliases() {
        val testKeys = TestOnlyKeystoreKeyProvider()
        val database = RoomTestEnvironment.inMemoryDatabase(
            context,
            AesGcmCipher(testKeys, "test-memory-store")
        )
        val failedCredential = "clear-failure-test-credential-must-not-leak"
        val orphanAlias = "android_agent_provider_api_key_orphan-test-key"
        try {
            val manager = KeystoreSecretManager(database, testKeys)
            manager.storeSecret("gemini", failedCredential)
            manager.storeSecret("groq", "independent-groq-test-credential")
            // Identify the alias belonging to gemini from its stable provider-ID hash, not map order.
            val geminiAlias = providerKeyAliasForTestId("gemini")
            assertNotNull(testKeys.getSecretKey(geminiAlias))
            testKeys.getOrCreateSecretKey(orphanAlias)
            testKeys.getOrCreateSecretKey("unrelated-workspace-key")
            testKeys.failSecretKeyDeletionForTest(geminiAlias, failedCredential)

            val failure = assertThrows(SecretStorageException::class.java) {
                manager.clearAllSecrets()
            }

            assertEquals("Unable to clear provider credentials", failure.message)
            assertNull("The aggregate failure must not retain the per-alias cause", failure.cause)
            assertFalse(failure.toString().contains(failedCredential))
            assertTrue(database.providerSecretDao().getProviderIds().isEmpty())
            assertEquals("Only the alias whose deletion failed should remain", setOf(geminiAlias), testKeys.aliases().filter(::isProviderKeyAlias).toSet())
            assertNull("Orphaned credential aliases are swept too", testKeys.getSecretKey(orphanAlias))
            assertNotNull("Unrelated Keystore aliases are outside the provider namespace", testKeys.getSecretKey("unrelated-workspace-key"))

            testKeys.allowSecretKeyDeletionForTest(geminiAlias)
            manager.clearAllSecrets()
            assertTrue(testKeys.aliases().none(::isProviderKeyAlias))
            assertNotNull(testKeys.getSecretKey("unrelated-workspace-key"))
        } finally {
            database.close()
            testKeys.clearAllKeysForTest()
        }
    }

    @Test
    fun credentialsAreNotLoggedAndStorageExceptionsDoNotCarryPlaintextOrOriginalCause() {
        val testKeys = TestOnlyKeystoreKeyProvider()
        val database = RoomTestEnvironment.inMemoryDatabase(
            context,
            AesGcmCipher(testKeys, "test-memory-store")
        )
        val capturedLogMessages = mutableListOf<String>()
        AgentLogger.setSink(object : AgentLogger.LogSink {
            override fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
                capturedLogMessages.add(message)
                throwable?.message?.let(capturedLogMessages::add)
            }
        })
        try {
            val storedSecret = "do-not-log-this-gemini-api-key-0123456789"
            val manager = KeystoreSecretManager(database, testKeys)
            manager.storeSecret("gemini", storedSecret)
            assertEquals(storedSecret, manager.getSecret("gemini"))

            val failingProvider = object : KeystoreKeyProvider {
                override fun getOrCreateSecretKey(alias: String): SecretKey =
                    throw IllegalStateException(storedSecret)

                override fun getSecretKey(alias: String): SecretKey? = null
                override fun deleteSecretKey(alias: String): Boolean = false
                override fun deleteSecretKeysWithPrefix(prefix: String): Int = 0
            }
            val failingManager = KeystoreSecretManager(database, failingProvider)
            val failure = assertThrows(SecretStorageException::class.java) {
                failingManager.storeSecret("groq", storedSecret)
            }

            assertFalse(failure.message.orEmpty().contains(storedSecret))
            assertFalse(failure.toString().contains(storedSecret))
            assertNull("The underlying provider exception must not be attached", failure.cause)
            assertTrue(capturedLogMessages.none { it.contains(storedSecret) })
            assertTrue(database.providerSecretDao().getByProviderId("groq") == null)
        } finally {
            AgentLogger.setSink(object : AgentLogger.LogSink {
                override fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
                    println("[$priority] [$tag] $message" + (throwable?.let { " : ${it.message}" } ?: ""))
                }
            })
            database.close()
            testKeys.clearAllKeysForTest()
        }
    }

    private fun createVersionTwoDatabase(
        databaseName: String,
        databaseCipher: AesGcmCipher,
        memoryContent: String,
        encryptedEmbedding: ByteArray,
        idempotencyKey: String
    ) {
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            VERSION_TWO_SCHEMA_SQL.forEach(legacy::execSQL)
            legacy.execSQL(
                """
                CREATE TRIGGER `guard_long_term_memory_type_insert`
                BEFORE INSERT ON `long_term_memory`
                WHEN NEW.`memory_type` NOT IN ('episodic', 'semantic', 'procedural', 'failure')
                BEGIN
                    SELECT RAISE(ABORT, 'invalid long_term_memory.memory_type');
                END
                """.trimIndent()
            )
            legacy.execSQL(
                """
                CREATE TRIGGER `guard_long_term_memory_type_update`
                BEFORE UPDATE OF `memory_type` ON `long_term_memory`
                WHEN NEW.`memory_type` NOT IN ('episodic', 'semantic', 'procedural', 'failure')
                BEGIN
                    SELECT RAISE(ABORT, 'invalid long_term_memory.memory_type');
                END
                """.trimIndent()
            )
            legacy.execSQL("CREATE TABLE `room_master_table` (`id` INTEGER PRIMARY KEY, `identity_hash` TEXT)")
            legacy.insertOrThrow(
                "room_master_table",
                null,
                ContentValues().apply {
                    put("id", 42)
                    put("identity_hash", VERSION_TWO_IDENTITY_HASH)
                }
            )
            legacy.insertOrThrow(
                "long_term_memory",
                null,
                ContentValues().apply {
                    put("id", "legacy-memory-v2")
                    put("memory_type", "semantic")
                    put("content", databaseCipher.encrypt(memoryContent))
                    put("embedding", encryptedEmbedding)
                    put("importance", 0.75f)
                    put("created_at", 100L)
                    put("last_accessed_at", 200L)
                    put("access_count", 3)
                    put("last_retrieved_at", 150L)
                    put("last_used_at", 175L)
                    put("successful_use_count", 2)
                    putNull("superseded_by")
                    put("origin", "legacy-origin")
                    putNull("guided_by_procedure_id")
                    putNull("retry_after")
                    put("schema_version", 2)
                }
            )
            legacy.insertOrThrow(
                "action_idempotency",
                null,
                ContentValues().apply {
                    put("idempotency_key", idempotencyKey)
                    put("status", "IN_FLIGHT")
                }
            )
            legacy.version = 2

            legacy.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'provider_secrets'",
                null
            ).use { cursor -> assertFalse("Version 2 must not already contain provider_secrets", cursor.moveToFirst()) }
        } finally {
            legacy.close()
        }
    }

    private fun providerKeyAliasForTestId(providerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(providerId.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
        return "android_agent_provider_api_key_v1_$hex"
    }

    private fun isProviderKeyAlias(alias: String): Boolean =
        alias.startsWith("android_agent_provider_api_key_")

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return (0..haystack.size - needle.size).any { start ->
            needle.indices.all { offset -> haystack[start + offset] == needle[offset] }
        }
    }

    private companion object {
        const val VERSION_TWO_IDENTITY_HASH = "06bb1a782f6b7e9a1e84dac34e8b5864"
        const val HEX = "0123456789abcdef"

        // DDL copied from the checked-in Room v2 schema; the DB is opened by Room at user_version=2.
        val VERSION_TWO_SCHEMA_SQL = listOf(
            """CREATE TABLE IF NOT EXISTS `long_term_memory` (`id` TEXT NOT NULL, `memory_type` TEXT NOT NULL, `content` TEXT NOT NULL, `embedding` BLOB, `importance` REAL NOT NULL DEFAULT 0.5, `created_at` INTEGER NOT NULL, `last_accessed_at` INTEGER NOT NULL, `access_count` INTEGER NOT NULL DEFAULT 0, `last_retrieved_at` INTEGER NOT NULL DEFAULT 0, `last_used_at` INTEGER NOT NULL DEFAULT 0, `successful_use_count` INTEGER NOT NULL DEFAULT 0, `superseded_by` TEXT, `origin` TEXT, `guided_by_procedure_id` TEXT, `retry_after` INTEGER, `schema_version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))""",
            """CREATE TABLE IF NOT EXISTS `action_audit_log` (`audit_id` TEXT NOT NULL, `action_id` TEXT NOT NULL, `skill_name` TEXT NOT NULL, `risk_level` INTEGER NOT NULL, `initiator` TEXT NOT NULL, `params_ciphertext` TEXT NOT NULL, `confirmation_prompt_ciphertext` TEXT, `user_confirmed` INTEGER NOT NULL, `status` TEXT NOT NULL, `output_ciphertext` TEXT, `failure_reason_ciphertext` TEXT, `cost_ms` INTEGER NOT NULL, `idempotency_key` TEXT, `guided_by_procedure_id` TEXT, `origin` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `schema_version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`audit_id`))""",
            "CREATE INDEX IF NOT EXISTS `index_action_audit_log_idempotency_key_status` ON `action_audit_log` (`idempotency_key`, `status`)",
            """CREATE TABLE IF NOT EXISTS `action_idempotency` (`idempotency_key` TEXT NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`idempotency_key`))""",
            """CREATE TABLE IF NOT EXISTS `change_provenance_log` (`provenance_id` TEXT NOT NULL, `change_id` TEXT NOT NULL, `trigger_signal_id` TEXT NOT NULL, `source_experience_id` TEXT, `rationale_ciphertext` TEXT NOT NULL, `proposed_target` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `schema_version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`provenance_id`))""",
            """CREATE TABLE IF NOT EXISTS `change_audit_log` (`change_id` TEXT NOT NULL, `target_type` TEXT NOT NULL, `target_key` TEXT NOT NULL, `status` TEXT NOT NULL, `reason_ciphertext` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `schema_version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`change_id`))"""
        )
    }
}
