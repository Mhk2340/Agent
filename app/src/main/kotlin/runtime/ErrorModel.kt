package runtime

/**
 * GEN-031 / GEN-032 — Error Model & Strategy
 * Standard Kotlin Result pattern with typed runtime exceptions.
 */
sealed class AgentRuntimeError(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InitializationError(message: String, cause: Throwable? = null) : AgentRuntimeError("ERR_INIT", message, cause)
    class ConfigurationError(message: String) : AgentRuntimeError("ERR_CONFIG", message)
    class StateTransitionError(message: String) : AgentRuntimeError("ERR_STATE", message)
    class ExecutionError(message: String, cause: Throwable? = null) : AgentRuntimeError("ERR_EXEC", message, cause)
}
