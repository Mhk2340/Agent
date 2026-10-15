package security

import android.content.Context
import org.json.JSONObject

/** Seed policy configuration kept apart from SafetyGuard and SafetyInternalStore. */
data class AgentPolicyConfig(
    val riskLevelOverrides: Map<String, Int> = emptyMap(),
    val maxLevel3ActionsPerHour: Int = SEED_MAX_LEVEL3_ACTIONS_PER_HOUR
) {
    companion object {
        const val SEED_MAX_LEVEL3_ACTIONS_PER_HOUR = 5
    }
}

/**
 * Resettable agent policy settings. This store owns only policy overrides and the configured
 * Level-3 ceiling; it never reads or writes SafetyGuard, SafetyInternalStore, or integrity inputs.
 */
class AndroidAgentPolicyConfigStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun snapshot(): AgentPolicyConfig {
        val riskOverrides = try {
            val json = JSONObject(preferences.getString(KEY_RISK_OVERRIDES, "{}") ?: "{}")
            buildMap<String, Int> {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, json.getInt(key))
                }
            }
        } catch (_: Exception) {
            throw IllegalStateException("Agent policy Config Store is unreadable")
        }
        val limit = try {
            preferences.getInt(KEY_LEVEL3_LIMIT, AgentPolicyConfig.SEED_MAX_LEVEL3_ACTIONS_PER_HOUR)
        } catch (_: Exception) {
            throw IllegalStateException("Agent policy Config Store is unreadable")
        }
        if (limit < 1) throw IllegalStateException("Agent policy Config Store has an invalid Level-3 ceiling")
        return AgentPolicyConfig(riskOverrides, limit)
    }

    @Synchronized
    fun setRiskLevelOverride(name: String, level: Int) {
        require(name.isNotBlank())
        require(level in 1..3)
        val updated = snapshot().riskLevelOverrides.toMutableMap().apply { put(name, level) }
        val committed = preferences.edit()
            .putString(KEY_RISK_OVERRIDES, JSONObject(updated as Map<*, *>).toString())
            .commit()
        if (!committed) throw IllegalStateException("Unable to persist agent policy Config Store")
    }

    @Synchronized
    fun setMaxLevel3ActionsPerHour(limit: Int) {
        require(limit >= 1)
        if (!preferences.edit().putInt(KEY_LEVEL3_LIMIT, limit).commit()) {
            throw IllegalStateException("Unable to persist agent policy Config Store")
        }
    }

    /** Clears only this policy store; seed values are supplied by [snapshot] defaults. */
    @Synchronized
    fun resetToSeedDefaults() {
        if (!preferences.edit().clear().commit()) {
            throw IllegalStateException("Unable to reset agent policy Config Store")
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "agent_policy_config"
        const val KEY_RISK_OVERRIDES = "risk_level_overrides"
        const val KEY_LEVEL3_LIMIT = "max_level3_actions_per_hour"
    }
}

/** Production ConfigStoreResetHook; never reaches safety/integrity stores. */
class AndroidAgentPolicyConfigResetHook(
    private val configStore: AndroidAgentPolicyConfigStore
) : ConfigStoreResetHook {
    override fun resetConfigStoreToSeedDefaults() = configStore.resetToSeedDefaults()
}
