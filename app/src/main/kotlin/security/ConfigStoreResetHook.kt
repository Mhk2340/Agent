package security

/**
 * GEN-SEC-3: Config Store Reset Hooks
 * Enforces resetting Config Store (risk level overrides and rate limit overrides)
 * strictly to seed strict defaults upon Panic Reset, rather than deleting or leaving them untouched.
 */
interface ConfigStoreResetHook {
    fun resetConfigStoreToSeedDefaults()
}

/**
 * TEST ONLY — logging-only stub. It resets no store and MUST NOT be wired into production.
 */
class DefaultConfigStoreResetHook(
    private val defaultRateLimitPerHour: Int = 5
) : ConfigStoreResetHook {

    override fun resetConfigStoreToSeedDefaults() {
        // TEST ONLY: this stub deliberately changes no storage; production must use
        // AndroidAgentPolicyConfigResetHook backed by AndroidAgentPolicyConfigStore.
        runtime.AgentLogger.w(
            "ConfigStoreResetHook",
            "TEST-ONLY NO-OP: Config Store was NOT reset (configured seed ceiling=$defaultRateLimitPerHour)."
        )
    }
}
