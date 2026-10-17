package inference

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Shared SHA-256 implementation used by ModelDownloadProtocol and the SAF importer.
 * Keeps :app completely isolated from safety.internal packages (Konsist Boundary Invariant).
 */
object ChecksumUtil {
    fun sha256(bytes: ByteArray): String = digestToHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Hashes while copying, so large SAF imports never need to be buffered in heap memory. */
    fun copyAndSha256(input: InputStream, output: OutputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        return digestToHex(digest.digest())
    }

    private fun digestToHex(hash: ByteArray): String =
        hash.joinToString("") { "%02x".format(it) }
}
