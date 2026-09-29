package runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GEN-030 — Runtime State Manager (Central StateFlow)
 */
enum class AgentState {
    UNINITIALIZED,
    INITIALIZING,
    IDLE,
    PROCESSING,
    ERROR
}

object RuntimeStateManager {
    private val _currentState = MutableStateFlow(AgentState.UNINITIALIZED)
    val currentState: StateFlow<AgentState> = _currentState.asStateFlow()

    fun transitionTo(newState: AgentState) {
        AgentLogger.i("RuntimeState", "Transitioning state: ${_currentState.value} -> $newState")
        _currentState.value = newState
    }

    fun reset() {
        _currentState.value = AgentState.UNINITIALIZED
    }
}
