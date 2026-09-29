package runtime.di

import runtime.AppInitializer
import safety.AccessMethod
import safety.SafetyGuard
import safety.ToolRegistryRow

/**
 * GEN-RT-1 — Dependency Injection Container / Module
 * Standard dependency provider structure.
 */
class AgentContainer(
    val safetyGuard: SafetyGuard = SafetyGuard()
) {
    val defaultTools = listOf(
        ToolRegistryRow(
            tool_name = "system_info",
            domain = "system",
            description = "Reads non-sensitive system status",
            access_method = AccessMethod.SKILL,
            risk_level = 1
        )
    )

    val appInitializer: AppInitializer by lazy {
        AppInitializer(
            safetyGuard = safetyGuard,
            initialTools = defaultTools
        )
    }
}
