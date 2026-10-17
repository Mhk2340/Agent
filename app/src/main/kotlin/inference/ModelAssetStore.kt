package inference

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Streaming, fail-closed SAF import for the single pinned GGUF asset.
 * The caller owns and closes [input]; unverified bytes are never made visible as the installed model.
 */
class ModelAssetStore(
    private val directory: File,
    private val expectedSha256: String = Qwen3ModelAsset.EXPECTED_SHA256,
    private val fileName: String = Qwen3ModelAsset.FILE_NAME
) {
    sealed class ImportResult {
        data class Installed(val file: File, val sha256: String, val byteCount: Long) : ImportResult()
        object AlreadyInstalled : ImportResult()
        data class IntegrityFailure(val expectedSha256: String, val actualSha256: String) : ImportResult()
        data class Failed(val reason: String) : ImportResult()
    }

    private val installedFile: File
        get() = File(directory, fileName)

    private val verificationFile: File
        get() = File(directory, "$fileName.sha256")

    /** Returns a private file only when its post-import verification marker matches the pinned hash. */
    @Synchronized
    fun verifiedModelFile(): File? {
        val model = installedFile
        val marker = verificationFile
        if (!model.isFile || !marker.isFile) return null
        val recordedHash = runCatching { marker.readText(StandardCharsets.US_ASCII).trim() }.getOrNull()
            ?: return null
        return model.takeIf { recordedHash.equals(expectedSha256, ignoreCase = true) }
    }

    fun isInstalled(): Boolean = verifiedModelFile() != null

    @Synchronized
    fun importModel(input: InputStream): ImportResult {
        if (verifiedModelFile() != null) return ImportResult.AlreadyInstalled

        val partial = File(directory, "$fileName.importing")
        val markerPartial = File(directory, "$fileName.sha256.importing")
        try {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                return ImportResult.Failed("Could not create the private model directory")
            }
            partial.delete()
            markerPartial.delete()

            val actualHash = FileOutputStream(partial).use { output ->
                val hash = ChecksumUtil.copyAndSha256(input, output)
                output.fd.sync()
                hash
            }

            if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
                partial.delete()
                return ImportResult.IntegrityFailure(expectedSha256, actualHash)
            }

            atomicReplace(partial, installedFile)
            FileOutputStream(markerPartial).use { output ->
                output.write(expectedSha256.toByteArray(StandardCharsets.US_ASCII))
                output.fd.sync()
            }
            atomicReplace(markerPartial, verificationFile)
            val model = installedFile
            return ImportResult.Installed(model, actualHash, model.length())
        } catch (error: Exception) {
            partial.delete()
            markerPartial.delete()
            return ImportResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun atomicReplace(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}
