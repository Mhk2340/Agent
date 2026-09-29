package safety.internal

import java.security.MessageDigest

/**
 * Calculates SHA-256 integrity checksum of a file or string.
 * Used by localVerify and runtime integrity guards.
 */
object SafetyConfigChecksum {
    fun sha256(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
