package chat

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
    }
) : LocalChatHistoryStore {
    private val lock = Any()
    private val temporaryFile: File = File(historyFile.parentFile, TEMP_FILE_NAME)

    override fun read(): Result<LocalChatArchive> = synchronized(lock) {
        if (!isAllowed()) return@synchronized Result.failure(ChatHistoryAccessException())
        if (!historyFile.exists()) return@synchronized Result.success(LocalChatArchive())

        var encrypted: ByteArray? = null
        var plain: ByteArray? = null
        try {
            encrypted = historyFile.readBytes()
            if (encrypted.isEmpty()) return@synchronized Result.failure(ChatHistoryStorageException())
            plain = cipher.decrypt(encrypted)
            val archive = json.decodeFromString<LocalChatArchive>(plain.toString(Charsets.UTF_8))
            if (archive.schemaVersion != LocalChatArchive.CURRENT_SCHEMA_VERSION) {
                return@synchronized Result.failure(ChatHistoryStorageException())
            }
            Result.success(archive)
        } catch (_: Exception) {
            Result.failure(ChatHistoryStorageException())
        } catch (_: OutOfMemoryError) {
            Result.failure(ChatHistoryStorageException())
        } finally {
            encrypted?.fill(0)
            plain?.fill(0)
        }
    }

    override fun write(archive: LocalChatArchive): Result<Unit> = synchronized(lock) {
        if (!isAllowed()) return@synchronized Result.failure(ChatHistoryAccessException())
        var plain: ByteArray? = null
        var encrypted: ByteArray? = null
        try {
            require(archive.schemaVersion == LocalChatArchive.CURRENT_SCHEMA_VERSION)
            plain = json.encodeToString(archive).toByteArray(Charsets.UTF_8)
            encrypted = cipher.encrypt(plain)
            historyFile.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) throw ChatHistoryStorageException()
            }
            FileOutputStream(temporaryFile).use { output ->
                output.write(encrypted)
                output.fd.sync()
            }
            moveAtomically(temporaryFile, historyFile)
            Result.success(Unit)
        } catch (_: Exception) {
            runCatching { temporaryFile.delete() }
            Result.failure(ChatHistoryStorageException())
        } catch (_: OutOfMemoryError) {
            runCatching { temporaryFile.delete() }
            Result.failure(ChatHistoryStorageException())
        } finally {
            plain?.fill(0)
            encrypted?.fill(0)
        }
    }

    override fun clear(): Result<Unit> = synchronized(lock) {
        try {
            val tempRemoved = !temporaryFile.exists() || temporaryFile.delete()
            val historyRemoved = !historyFile.exists() || historyFile.delete()
            if (!tempRemoved || !historyRemoved) Result.failure(ChatHistoryStorageException())
            else Result.success(Unit)
        } catch (_: Exception) {
            Result.failure(ChatHistoryStorageException())
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
            if (to.exists() && !to.delete()) throw ChatHistoryStorageException()
            if (!from.renameTo(to)) throw ChatHistoryStorageException()
        }
    }

    override fun toString(): String = "EncryptedLocalChatHistoryStore(content=<redacted>)"

    private companion object {
        const val TEMP_FILE_NAME = "local-chat-history.enc.tmp"
    }
}

internal class ChatHistoryStorageException : IllegalStateException("Local chat history is unavailable")
internal class ChatHistoryAccessException : SecurityException("App Lock is closed")
