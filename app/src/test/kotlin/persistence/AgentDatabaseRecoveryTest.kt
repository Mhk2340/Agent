package persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// PROVISIONAL: API 28 keeps Robolectric's platform artifact smaller; these tests only assert SQLite/Room recovery semantics supported at this API.
@Config(sdk = [28], manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class AgentDatabaseRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        AgentDatabaseProvider.close()
        context.deleteDatabase(AgentDatabaseFactory.DEFAULT_DATABASE_NAME)
    }

    @Test
    fun unreadableDatabaseIsRecreatedAndRecoveryIsExplicitlyReported() {
        AgentDatabaseProvider.close()
        context.deleteDatabase(AgentDatabaseFactory.DEFAULT_DATABASE_NAME)
        val corruptedFile = context.getDatabasePath(AgentDatabaseFactory.DEFAULT_DATABASE_NAME)
        corruptedFile.parentFile?.mkdirs()
        val malformedDatabase = SQLiteDatabase.openOrCreateDatabase(corruptedFile, null)
        malformedDatabase.execSQL("CREATE TABLE `wrong_schema` (`id` INTEGER PRIMARY KEY)")
        malformedDatabase.version = 1
        malformedDatabase.close()

        val result = AgentDatabaseProvider.initialize(context)
        try {
            assertTrue(result.recoveredByReset)
            assertTrue(result.initialFailure.orEmpty().isNotBlank())
            val recoveredDb = result.database.openHelper.readableDatabase
            recoveredDb.query("SELECT COUNT(*) FROM `long_term_memory`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
            assertEquals(3, recoveredDb.version)
        } finally {
            AgentDatabaseProvider.close()
        }
    }
}
