package persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import memory.AesGcmCipher

/** One Room database and one migration ladder for memory, audit logs, and encrypted provider secrets. */
@Database(
    entities = [
        LongTermMemoryEntity::class,
        ActionAuditEntity::class,
        ActionIdempotencyEntity::class,
        ChangeProvenanceEntity::class,
        ChangeAuditEntity::class,
        ProviderSecretEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun longTermMemoryDao(): LongTermMemoryDao
    abstract fun actionAuditDao(): ActionAuditDao
    abstract fun actionIdempotencyDao(): ActionIdempotencyDao
    abstract fun changeProvenanceDao(): ChangeProvenanceDao
    abstract fun changeAuditDao(): ChangeAuditDao
    abstract fun providerSecretDao(): ProviderSecretDao
}

object AgentDatabaseFactory {
    // PROVISIONAL: roadmap specifies one shared Room database but does not prescribe its filename.
    const val DEFAULT_DATABASE_NAME = "android_agent.db"

    fun open(
        context: Context,
        databaseName: String = DEFAULT_DATABASE_NAME,
        cipher: AesGcmCipher = AesGcmCipher()
    ): AgentDatabase = Room.databaseBuilder(
        context.applicationContext,
        AgentDatabase::class.java,
        databaseName
    )
        .addMigrations(AgentMigrations.migration1To2(cipher), AgentMigrations.migration2To3())
        .addCallback(AgentMigrations.schemaCallback())
        .build()
}

data class DatabaseInitializationResult(
    val database: AgentDatabase,
    val recoveredByReset: Boolean,
    val initialFailure: String? = null
)

class DatabaseRecoveryException(
    val initialFailure: Exception,
    val resetFailure: Exception
) : IllegalStateException(
    "Database open/migration failed (${initialFailure.message}); reset/recreate also failed (${resetFailure.message})",
    resetFailure
) {
    init {
        addSuppressed(initialFailure)
    }
}

/** Process-wide Room instance shared by all production repositories. */
object AgentDatabaseProvider {
    @Volatile
    private var instance: AgentDatabase? = null

    fun get(context: Context): AgentDatabase = instance ?: synchronized(this) {
        instance ?: AgentDatabaseFactory.open(context.applicationContext).also { instance = it }
    }

    /** Closes and clears the process-wide instance, e.g. during app-level teardown. */
    fun close() = synchronized(this) {
        instance?.close()
        instance = null
    }

    /**
     * Opens Room and runs pending migrations. As the roadmap's last-resort recovery, if the
     * existing database cannot be opened after the migration attempt, close it, delete the DB,
     * and create a fresh one. The caller must clearly report this data loss to the user.
     */
    fun initialize(context: Context): DatabaseInitializationResult = synchronized(this) {
        val appContext = context.applicationContext
        val current = instance ?: AgentDatabaseFactory.open(appContext).also { instance = it }
        try {
            current.openHelper.writableDatabase
            return@synchronized DatabaseInitializationResult(current, recoveredByReset = false)
        } catch (initialFailure: Exception) {
            runCatching { current.close() }
            instance = null
            appContext.deleteDatabase(AgentDatabaseFactory.DEFAULT_DATABASE_NAME)

            val recreated = AgentDatabaseFactory.open(appContext).also { instance = it }
            try {
                recreated.openHelper.writableDatabase
                DatabaseInitializationResult(
                    database = recreated,
                    recoveredByReset = true,
                    initialFailure = initialFailure.message ?: initialFailure::class.java.simpleName
                )
            } catch (resetFailure: Exception) {
                runCatching { recreated.close() }
                instance = null
                throw DatabaseRecoveryException(initialFailure, resetFailure)
            }
        }
    }
}
