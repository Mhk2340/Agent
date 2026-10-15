package persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testutil.RoomTestEnvironment

// PROVISIONAL: API 28 keeps Robolectric's platform artifact smaller; these tests only assert SQLite/Room schema semantics supported at this API.
@Config(sdk = [28], manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class LongTermMemorySchemaTest {
    private lateinit var database: AgentDatabase
    private lateinit var cipher: memory.AesGcmCipher

    @Before
    fun setup() {
        cipher = RoomTestEnvironment.newCipher()
        database = RoomTestEnvironment.inMemoryDatabase(
            ApplicationProvider.getApplicationContext<Context>(),
            cipher
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun memoryTypeIsConstrainedToCanonicalEnumValues() {
        val invalid = LongTermMemoryEntity(
            id = "invalid-memory-type",
            memoryType = "unrecognized",
            content = cipher.encrypt("test"),
            embedding = null,
            importance = 0.5f,
            createdAt = 1L,
            lastAccessedAt = 1L,
            accessCount = 0,
            lastRetrievedAt = 0L,
            lastUsedAt = 0L,
            successfulUseCount = 0,
            supersededBy = null,
            origin = null,
            guidedByProcedureId = null,
            retryAfter = null,
            schemaVersion = 1
        )

        assertTrue(
            "SQLite trigger must enforce the GEN-MEM-1 memory_type enum",
            runCatching { database.longTermMemoryDao().insert(invalid) }.isFailure
        )
    }
}
