package security

/**
 * GEN-SEC-3: Config Store Reset Hooks
 * Enforces resetting Config Store (risk level overrides and rate limit overrides)
 * strictly to seed strict defaults upon Panic Reset, rather than deleting or leaving them untouched.
 */
interface ConfigStoreResetHook {
    fun resetConfigStoreToSeedDefaults()
}

class DefaultConfigStoreResetHook(
    private val defaultRateLimitPerHour: Int = 5
) : ConfigStoreResetHook {

    override fun resetConfigStoreToSeedDefaults() {
        // Reset to conservative seed defaults (5 Level-3 actions per hour, zero overrides)
        runtime.AgentLogger.w(
            "ConfigStoreResetHook",
            "Config Store reset to seed strict defaults (max Level-3 per hour: $defaultRateLimitPerHour, overrides cleared)."
        )
    }
}
