package runtime

import java.util.UUID

/**
 * GEN-017 — Identity System (Simplified)
 * Provides standardized unique component & execution instance tracking.
 */
object Identity {
    fun nextId(prefix: String = "id"): String {
        return "$prefix-${UUID.randomUUID()}"
    }
}
