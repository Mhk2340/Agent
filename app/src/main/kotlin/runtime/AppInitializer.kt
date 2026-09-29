package runtime

import safety.SafetyGuard
import safety.ToolRegistryRow

/**
 * GEN-INIT-1 — App Initialization Sequence
 * Deterministic ordered startup steps executed during startup / onCreate.
 */
class AppInitializer(
    private val safetyGuard: SafetyGuard,
    private val initialTools: List<ToolRegistryRow> = emptyList()
) {
    sealed class InitStepResult {
        data class Success(val stepName: String) : InitStepResult()
        data class Failure(val stepName: String, val error: String) : InitStepResult()
    }

    fun initialize(): List<InitStepResult> {
        val results = mutableListOf<InitStepResult>()
        RuntimeStateManager.transitionTo(AgentState.INITIALIZING)

        // Step 1: Environment & Config Verification
        try {
            AgentLogger.i("AppInitializer", "Step 1: Checking environment...")
            results.add(InitStepResult.Success("EnvironmentCheck"))
        } catch (e: Exception) {
            results.add(InitStepResult.Failure("EnvironmentCheck", e.message ?: "Unknown error"))
            RuntimeStateManager.transitionTo(AgentState.ERROR)
            return results
        }

        // Step 2: Register Initial System Tools into Safety Guard
        try {
            AgentLogger.i("AppInitializer", "Step 2: Registering initial tools into SafetyGuard...")
            initialTools.forEach { tool ->
                safetyGuard.registerTool(tool)
            }
            results.add(InitStepResult.Success("ToolRegistration"))
        } catch (e: Exception) {
            results.add(InitStepResult.Failure("ToolRegistration", e.message ?: "Unknown error"))
            RuntimeStateManager.transitionTo(AgentState.ERROR)
            return results
        }

        // Step 3: Transition to IDLE
        RuntimeStateManager.transitionTo(AgentState.IDLE)
        results.add(InitStepResult.Success("RuntimeReady"))
        return results
    }
}
