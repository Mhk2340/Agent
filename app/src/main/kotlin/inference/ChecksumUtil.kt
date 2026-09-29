package inference

import java.security.MessageDigest

/**
 * Utility for SHA-256 calculation in application layer.
 * Keeps :app completely isolated from safety.internal packages (Konsist Boundary Invariant).
 */
object ChecksumUtil {
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
