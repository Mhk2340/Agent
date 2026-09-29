package runtime

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * GEN-NS-1 — In-Process Message Bus (Kotlin Coroutines Channels)
 * CRITICAL PATCH:
 * 1. "Status Signals" Channel: Channel.CONFLATED (only latest value matters, drops older without blocking).
 * 2. "Critical Events" Channel: BUFFERED with BufferOverflow.SUSPEND (never silently drops data; producer suspends if full).
 */
object NervousSystem {

    // Status Signals Channel (Job status, health pings, thermal changes)
    private val statusChannel = Channel<StatusSignal>(capacity = Channel.CONFLATED)

    // Critical Events Channel (Reasoning Trace, Action Audit Log, Change Provenance)
    // Capacity 256 with SUSPEND strategy (lossless)
    private val criticalEventChannel = Channel<CriticalEvent>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    fun statusFlow(): Flow<StatusSignal> = statusChannel.receiveAsFlow()
    fun criticalEventFlow(): Flow<CriticalEvent> = criticalEventChannel.receiveAsFlow()

    suspend fun emitStatus(signal: StatusSignal) {
        statusChannel.send(signal)
    }

    suspend fun emitCriticalEvent(event: CriticalEvent) {
        criticalEventChannel.send(event)
    }
}

sealed class StatusSignal {
    data class HealthPing(val timestamp: Long, val memoryAvailableMb: Long) : StatusSignal()
    data class StateChange(val oldState: AgentState, val newState: AgentState) : StatusSignal()
    data class JobProgress(val jobId: String, val stepIndex: Int, val totalSteps: Int) : StatusSignal()
}

sealed class CriticalEvent {
    data class ReasoningStep(val interactionId: String, val stepName: String, val payload: String) : CriticalEvent()
    data class ActionExecuted(val actionId: String, val toolName: String, val success: Boolean) : CriticalEvent()
    data class SystemChangeCommitted(val changeId: String, val target: String) : CriticalEvent()
}
