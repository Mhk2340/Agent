package chat

import diagnostics.DiagnosticCode
import diagnostics.DiagnosticComponent
import diagnostics.DiagnosticField
import diagnostics.DiagnosticFieldKey
import diagnostics.DiagnosticLevel
import diagnostics.DiagnosticsLog
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface LocalChatPayloadCipher {
    fun encrypt(plainText: ByteArray): ByteArray
    fun decrypt(cipherText: ByteArray): ByteArray
}

internal interface LocalChatHistoryStore {
    fun read(): Result<LocalChatArchive>
    fun write(archive: LocalChatArchive): Result<Unit>
    fun clear(): Result<Unit>
}

/** Encrypted, app-private, no-backup chat persistence. Ciphertext is the only file payload. */
internal class EncryptedLocalChatHistoryStore(
    private val historyFile: File,
    private val cipher: LocalChatPayloadCipher,
    private val isAccessAllowed: () -> Boolean,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
    private val diagnostics: DiagnosticsLog? = null
) : LocalChatHistoryStore {
    private val lock = Any()
    private val temporaryFile: File = File(historyFile.parentFile, TEMP_FILE_NAME)

    override fun read(): Result<LocalChatArchive> = synchronized(lock) {
        diagnostics?.record(DiagnosticLevel.DEBUG, DiagnosticComponent.CHAT_STORE, DiagnosticCode.CHAT_STORE_READ_BEGIN)
        if (!isAllowed()) {
            val denied = ChatHistoryAccessException()
            diagnostics?.recordFailure(
                DiagnosticLevel.WARNING,
                DiagnosticComponent.CHAT_STORE,
                DiagnosticCode.CHAT_STORE_ACCESS_DENIED,
                denied
            )
            return@synchronized Result.failure(denied)
        }
        if (!historyFile.exists()) {
            diagnostics?.record(
                DiagnosticLevel.INFO,
                DiagnosticComponent.CHAT_STORE,
                DiagnosticCode.CHAT_STORE_READ_OK,
                DiagnosticField.number(DiagnosticFieldKey.BYTE_COUNT, 0L)
            )
            return@synchronized Result.success(LocalChatArchive())
        }

        var encrypted: ByteArray? = null
        var plain: ByteArray? = null
        var stage = HistoryStorageStage.FILE_READ
        try {
            encrypted = historyFile.readBytes()
            if (encrypted.isEmpty()) {
                throw ChatHistoryStorageException(DiagnosticCode.CHAT_STORE_ARCHIVE_FAILURE)
            }
            stage = HistoryStorageStage.DECRYPT
            plain = cipher.decrypt(encrypted)
            stage = HistoryStorageStage.DECODE
            val archive = json.decodeFromString<LocalChatArchive>(plain.toString(Charsets.UTF_8))
            if (archive.schemaVersion != LocalChatArchive.CURRENT_SCHEMA_VERSION) {
                throw ChatHistoryStorageException(DiagnosticCode.CHAT_STORE_ARCHIVE_FAILURE)
            }
            diagnostics?.record(
                DiagnosticLevel.INFO,
                DiagnosticComponent.CHAT_STORE,
                DiagnosticCode.CHAT_STORE_READ_OK,
                DiagnosticField.number(DiagnosticFieldKey.BYTE_COUNT, encrypted.size.toLong())
            )
            Result.success(archive)
        } catch (error: Exception) {
            val code = (error as? ChatHistoryStorageException)?.diagnosticCode ?: stage.diagnosticCode
            diagnostics?.recordFailure(
                DiagnosticLevel.ERROR,
                DiagnosticComponent.CHAT_STORE,
                code,
                error,
                DiagnosticField.enum(DiagnosticFieldKey.PHASE, stage)
            )
            Result.failure(ChatHistoryStorageException(code, error.javaClass))
        } catch (error: OutOfMemoryError) {
            diagnostics?.recordFailure(
                DiagnosticLevel.ERROR,
                DiagnosticComponent.CHAT_STORE,
                DiagnosticCode.CHAT_STORE_READ_FAILURE,
                error,
                DiagnosticField.enum(DiagnosticFieldKey.PHASE, stage)
            )
            Result.failure(ChatHistoryStorageException(DiagnosticCode.CHAT_STORE_READ_FAILURE, error.javaClass))
        } finally {
            encrypted?.fill(0)
            plain?.fill(0)
        }
    }

    override fun write(archive: LocalChatArchive): Result<Unit> = synchronized(lock) {
        diagnostics?.record(DiagnosticLevel.DEBUG, DiagnosticComponent.CHAT_STORE, DiagnosticCode.CHAT_STORE_WRITE_BEGIN)
        if (!isAllowed()) {
            val denied = ChatHistoryAccessException()
            diagnostics?.recordFailure(
                DiagnosticLevel.WARNING,
                DiagnosticComponent.CHAT_STORE,
                DiagnosticCode.CHAT_STORE_ACCESS_DENIED,
                denied
            )
            return@synchronized Result.failure(denied)
        }
        var plain: ByteArray? = null
        var encrypted: ByteArray? = null
        var stage = HistoryStorageStage.ENCRYPT
        try {
            require(archive.schemaVersion == LocalChatArchive.CURRENT_SCHEMA_VERSION)
            plain = json.encodeToString(archive).toByteArray(Charsets.UTF_8)
            encrypted = cipher.encrypt(plain)
            stage = HistoryStorageStage.FILE_WRITE
            historyFile.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) throw ChatHistoryStorageException(DiagnosticCode.CHAT_STORE_WRITE_FAILURE)
            }
            FileOutputStream(temporaryFile).use { output ->
                output.write(encrypted)
                output.fd.sync()
            }
            stage = HistoryStorageStage.FILE_RENAME
            moveAtomically(temporaryFile, historyFile)
            diagnostics?.record(
                DiagnosticLevel.INFO,
                DiagnosticComponent.CHAT_STORE,
                DiagnosticCode.CHAT_STORE_WRITE_OK,
                DiagnosticField.number(DiagnosticFieldKey.BYTE_COUNT, encrypted.size.toLong())
            )
            Result.success(Unit)
        } catch (error: Exception) {
            runCatching { temporaryFile.delete() }
            val code = (error as? ChatHistoryStorageException)?.diagnosticCode ?: stage.diagnosticCode
            diagnostics?.recordFailure(
                DiagnosticLevel.ERROR,
                DiagnosticComponent.CHAT_STORE,
                code,
                error,
                DiagnosticField.enum(DiagnosticFieldKey.PHASE, stage)
            )
            Result.failure(ChatHistoryStorageException(code, error.javaClass))
        } catch (error: OutOfMemoryError) {
            runCatching { temporaryFile.delete() }
            diagnostics?.recordFailure(
                DiagnosticLevel.ERROR,
                DiagnosticComponent.CHAT_STORE,
                DiagnosticCode.CHAT_STORE_WRITE_FAILURE,
                error,
                DiagnosticField.enum(DiagnosticFieldKey.PHASE, stage)
            )
            Result.failure(ChatHistoryStorageException(DiagnosticCode.CHAT_STORE_WRITE_FAILURE, error.javaClass))
        } finally {
            plain?.fill(0)
            encrypted?.fill(0)
        }
    }

    override fun clear(): Result<Unit> = synchronized(lock) {
        try {
            val tempRemoved = !temporaryFile.exists() || temporaryFile.delete()
            val historyRemoved = !historyFile.exists() || historyFile.delete()
            if (!tempRemoved || !historyRemoved) {
                Result.failure(ChatHistoryStorageException(DiagnosticCode.CHAT_STORE_WRITE_FAILURE))
            } else {
                diagnostics?.record(DiagnosticLevel.INFO, DiagnosticComponent.CHAT_STORE, DiagnosticCode.CHAT_STORE_CLEARED)
                Result.success(Unit)
            }
        } catch (error: Exception) {
            diagnostics?.recordFailure(
                DiagnosticLevel.ERROR,
                DiagnosticComponent.CHAT_STORE,
                DiagnosticCode.CHAT_STORE_WRITE_FAILURE,
                error
            )
            Result.failure(ChatHistoryStorageException(DiagnosticCode.CHAT_STORE_WRITE_FAILURE, error.javaClass))
        }
    }

    private fun isAllowed(): Boolean = runCatching(isAccessAllowed).getOrDefault(false)

    private fun moveAtomically(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            if (to.exists() && !to.delete()) throw ChatHistoryStorageException(DiagnosticCode.CHAT_STORE_WRITE_FAILURE)
            if (!from.renameTo(to)) throw ChatHistoryStorageException(DiagnosticCode.CHAT_STORE_WRITE_FAILURE)
        }
    }

    override fun toString(): String = "EncryptedLocalChatHistoryStore(content=<redacted>)"

    private companion object {
        const val TEMP_FILE_NAME = "local-chat-history.enc.tmp"
    }
}

internal class InMemoryLocalChatHistoryStore : LocalChatHistoryStore {
    @Volatile
    private var archive = LocalChatArchive()

    override fun read(): Result<LocalChatArchive> = Result.success(archive)
    override fun write(archive: LocalChatArchive): Result<Unit> {
        this.archive = archive
        return Result.success(Unit)
    }
    override fun clear(): Result<Unit> {
        archive = LocalChatArchive()
        return Result.success(Unit)
    }
}

internal enum class HistoryStorageStage(val diagnosticCode: DiagnosticCode) {
    FILE_READ(DiagnosticCode.CHAT_STORE_READ_FAILURE),
    DECRYPT(DiagnosticCode.CHAT_STORE_CRYPTO_FAILURE),
    DECODE(DiagnosticCode.CHAT_STORE_ARCHIVE_FAILURE),
    ENCRYPT(DiagnosticCode.CHAT_STORE_CRYPTO_FAILURE),
    FILE_WRITE(DiagnosticCode.CHAT_STORE_WRITE_FAILURE),
    FILE_RENAME(DiagnosticCode.CHAT_STORE_WRITE_FAILURE)
}

internal class ChatHistoryStorageException(
    val diagnosticCode: DiagnosticCode = DiagnosticCode.CHAT_STORE_WRITE_FAILURE,
    val failureClass: Class<out Throwable>? = null
) : IllegalStateException("Local chat history is unavailable")

internal class ChatHistoryAccessException : SecurityException("App Lock is closed")
