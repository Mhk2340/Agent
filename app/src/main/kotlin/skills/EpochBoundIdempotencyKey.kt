package skills

import java.util.UUID

/** Canonical idempotency keys bind one UUID to the monotonic Panic Reset epoch. */
object EpochBoundIdempotencyKey {
    fun create(resetEpoch: Long, uuid: UUID = UUID.randomUUID()): String {
        require(resetEpoch >= 1L) { "Reset epoch must be positive" }
        return "$resetEpoch:${uuid.toString().lowercase()}"
    }

    fun belongsToEpoch(key: String, resetEpoch: Long): Boolean {
        if (resetEpoch < 1L) return false
        val separator = key.indexOf(':')
        if (separator <= 0 || separator != key.lastIndexOf(':')) return false
        if (key.substring(0, separator).toLongOrNull() != resetEpoch) return false
        val uuidPart = key.substring(separator + 1)
        val parsed = try {
            UUID.fromString(uuidPart)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return parsed.toString().equals(uuidPart, ignoreCase = true)
    }
}
