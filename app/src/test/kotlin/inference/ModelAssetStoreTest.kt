package inference

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class ModelAssetStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun streamsAndInstallsOnlyTheHashVerifiedFile() {
        val payload = "small streaming fixture for the pinned GGUF import path".toByteArray()
        val expectedHash = ChecksumUtil.sha256(payload)
        val store = ModelAssetStore(temporaryFolder.newFolder(), expectedHash, "fixture.gguf")

        val result = store.importModel(ByteArrayInputStream(payload))

        assertTrue(result is ModelAssetStore.ImportResult.Installed)
        val installed = result as ModelAssetStore.ImportResult.Installed
        assertEquals(expectedHash, installed.sha256)
        assertArrayEquals(payload, installed.file.readBytes())
        assertEquals(payload.size.toLong(), installed.byteCount)
        assertTrue(store.isInstalled())
        assertNotNull(store.verifiedModelFile())
    }

    @Test
    fun hashMismatchLeavesNoInstalledModelOrVerificationMarker() {
        val directory = temporaryFolder.newFolder()
        val store = ModelAssetStore(directory, "expected-pinned-hash", "fixture.gguf")

        val result = store.importModel(ByteArrayInputStream("different fixture".toByteArray()))

        assertTrue(result is ModelAssetStore.ImportResult.IntegrityFailure)
        assertFalse(store.isInstalled())
        assertFalse(directory.resolve("fixture.gguf").exists())
        assertFalse(directory.resolve("fixture.gguf.sha256").exists())
    }

    @Test
    fun alreadyVerifiedModelIsNotReplacedByAnotherImportAttempt() {
        val payload = "verified fixture".toByteArray()
        val directory = temporaryFolder.newFolder()
        val store = ModelAssetStore(directory, ChecksumUtil.sha256(payload), "fixture.gguf")
        val first = store.importModel(ByteArrayInputStream(payload))
        assertTrue(first is ModelAssetStore.ImportResult.Installed)

        val second = store.importModel(ByteArrayInputStream("untrusted replacement".toByteArray()))

        assertTrue(second is ModelAssetStore.ImportResult.AlreadyInstalled)
        assertArrayEquals(payload, directory.resolve("fixture.gguf").readBytes())
    }
}
