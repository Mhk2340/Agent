package chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EncryptedLocalChatHistoryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun persistsCiphertextOnlyAndRoundTripsTheFullArchive() {
        val file = File(temporaryFolder.root, "local-chat-history.enc")
        val store = store(file)
        val archive = archiveWith("a sensitive local answer")

        assertTrue(store.write(archive).isSuccess)
        assertTrue(file.exists())
        assertFalse(String(file.readBytes(), Charsets.UTF_8).contains("sensitive local answer"))
        assertEquals(archive, store.read().getOrThrow())
    }

    @Test
    fun lockedReadsAndWritesFailClosed() {
        val file = File(temporaryFolder.root, "locked.enc")
        var allowed = false
        val store = EncryptedLocalChatHistoryStore(file, TestCipher(), { allowed })

        assertTrue(store.read().isFailure)
        assertTrue(store.write(archiveWith("private")).isFailure)
        assertFalse(file.exists())

        allowed = true
        assertTrue(store.write(archiveWith("private")).isSuccess)
        allowed = false
        assertTrue(store.read().isFailure)
    }

    @Test
    fun tamperedCiphertextNeverReturnsPlaintext() {
        val file = File(temporaryFolder.root, "tampered.enc")
        val store = store(file)
        assertTrue(store.write(archiveWith("do not expose this")).isSuccess)
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertTrue(store.read().isFailure)
    }

    @Test
    fun missingFileIsAnEmptyArchiveAndClearRemovesHistory() {
        val file = File(temporaryFolder.root, "clear.enc")
        val store = store(file)

        assertEquals(LocalChatArchive(), store.read().getOrThrow())
        assertTrue(store.write(archiveWith("stored privately")).isSuccess)
        assertTrue(file.exists())
        assertTrue(store.clear().isSuccess)
        assertFalse(file.exists())
        assertEquals(LocalChatArchive(), store.read().getOrThrow())
    }

    @Test
    fun diagnosticStringDoesNotRevealStoredMessages() {
        val store = store(File(temporaryFolder.root, "redacted.enc"))

        assertFalse(store.toString().contains("secret body"))
        assertFalse(archiveWith("secret body").toString().contains("secret body"))
        assertFalse(conversation("secret body").toString().contains("secret body"))
        assertFalse(message("secret body").toString().contains("secret body"))
    }

    private fun store(file: File) = EncryptedLocalChatHistoryStore(file, TestCipher(), { true })

    private fun archiveWith(content: String): LocalChatArchive {
        val conversation = conversation(content)
        return LocalChatArchive(activeConversationId = conversation.id, conversations = listOf(conversation))
    }

    private fun conversation(content: String) = LocalChatConversation(
        id = "conversation-1",
        title = "A private title",
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        messages = listOf(
            LocalChatMessage("user-1", LocalChatRole.USER, "question", 1L),
            LocalChatMessage("assistant-1", LocalChatRole.ASSISTANT, content, 2L)
        )
    )

    private fun message(content: String) = LocalChatMessage(
        "redacted-message", LocalChatRole.ASSISTANT, content, 0L
    )

    private class TestCipher : LocalChatPayloadCipher {
        override fun encrypt(plainText: ByteArray): ByteArray = plainText.copyOf().also { bytes ->
            bytes.indices.forEach { index -> bytes[index] = (bytes[index].toInt() xor XOR_MASK).toByte() }
        }

        override fun decrypt(cipherText: ByteArray): ByteArray = encrypt(cipherText)

        private companion object {
            const val XOR_MASK = 0x5a
        }
    }
}
