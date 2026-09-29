package reasoning

import runtime.AgentLogger

/**
 * GEN-NEW-D — Turn Budget Governor
 *
 * MANDATORY ARCHITECTURAL REQUIREMENTS:
 * 1. Strict limit of max 3 LLM calls per turn.
 * 2. Strict timeout limit of 30 seconds per turn.
 * 3. Blocking ceiling: Attempts to execute a 4th call within the same turn are strictly blocked
 *    and rejected with an explicit TurnBudgetExceededException.
 * 4. Transparent reason reporting in Reasoning Trace.
 */
class TurnBudgetGovernor(
    val maxLlmCallsPerTurn: Int = 3,
    val timeoutPerTurnSeconds: Int = 30,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var turnStartTimeMs: Long = 0L
    private var callsMadeInCurrentTurn: Int = 0
    private var isTurnActive: Boolean = false

    fun startTurn() {
        turnStartTimeMs = clock()
        callsMadeInCurrentTurn = 0
        isTurnActive = true
    }

    /**
     * Checks whether an LLM call is permitted within the turn's call and time budget.
     * Increments call counter if allowed, or throws [TurnBudgetExceededException].
     */
    fun recordAndCheckCall(callName: String): Int {
        if (!isTurnActive) {
            startTurn()
        }

        val elapsedSec = (clock() - turnStartTimeMs) / 1000L
        if (elapsedSec > timeoutPerTurnSeconds) {
            AgentLogger.w("TurnBudgetGovernor", "Turn timeout exceeded: ${elapsedSec}s > ${timeoutPerTurnSeconds}s")
            throw TurnBudgetExceededException(
                reason = "Turn budget timeout exceeded (${elapsedSec}s > ${timeoutPerTurnSeconds}s) before executing $callName",
                callsMade = callsMadeInCurrentTurn,
                elapsedSeconds = elapsedSec
            )
        }

        if (callsMadeInCurrentTurn >= maxLlmCallsPerTurn) {
            AgentLogger.w("TurnBudgetGovernor", "Max LLM calls ceiling reached: $callsMadeInCurrentTurn >= $maxLlmCallsPerTurn")
            throw TurnBudgetExceededException(
                reason = "Turn budget call limit exceeded: Cannot execute $callName (already made $callsMadeInCurrentTurn calls; max is $maxLlmCallsPerTurn)",
                callsMade = callsMadeInCurrentTurn,
                elapsedSeconds = elapsedSec
            )
        }

        callsMadeInCurrentTurn++
        AgentLogger.i("TurnBudgetGovernor", "Call #$callsMadeInCurrentTurn permitted: $callName")
        return callsMadeInCurrentTurn
    }

    fun getCallsMade(): Int = callsMadeInCurrentTurn

    fun getRemainingCalls(): Int = (maxLlmCallsPerTurn - callsMadeInCurrentTurn).coerceAtLeast(0)

    fun resetTurn() {
        callsMadeInCurrentTurn = 0
        isTurnActive = false
    }
}

class TurnBudgetExceededException(
    val reason: String,
    val callsMade: Int,
    val elapsedSeconds: Long
) : RuntimeException(reason)
