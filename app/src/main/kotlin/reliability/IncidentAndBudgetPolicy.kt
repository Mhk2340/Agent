package reliability

/**
 * GEN-SRE-3 — Blameless Incident Note
 * Automatically generated whenever an automated rollback, circuit trip,
 * fallback invocation, or crash anomaly occurs.
 */
data class BlamelessIncidentNote(
    val incidentId: String,
    val triggerSource: String, // e.g. "ROLLBACK", "CIRCUIT_BREAKER", "CRASH", "SLO_BREACH"
    val timestampMs: Long,
    val detectedAnomaly: String,
    val rootCauseSummary: String,
    val affectedComponents: List<String>,
    val mitigationActionTaken: String,
    val auditTraceId: String?,
    val recommendations: List<String>
)

/**
 * GEN-SRE-4 — Error Budget Policy
 * MANDATORY ARCHITECTURAL REQUIREMENT:
 * When error budget is exhausted, all automatic change mechanisms
 * (GEN-363 Adaptive Weight Updating, GEN-CI-2, Phase XII, GEN-MA-11) MUST BE HALTED.
 */
interface ErrorBudgetController {
    fun isChangeMechanismPermitted(): Boolean
    fun checkAndEnforceBudget(dashboard: SingleUserHealthDashboard): Boolean
}

class SystemErrorBudgetController(
    private val healthAggregator: HealthAggregator
) : ErrorBudgetController {

    @Volatile
    private var changeMechanismPermitted: Boolean = true

    override fun isChangeMechanismPermitted(): Boolean = changeMechanismPermitted

    override fun checkAndEnforceBudget(dashboard: SingleUserHealthDashboard): Boolean {
        // If error budget is exhausted or status is CRITICAL, freeze all mutation mechanisms
        if (dashboard.isErrorBudgetExhausted || dashboard.overallStatus == SystemHealthStatus.CRITICAL) {
            changeMechanismPermitted = false
            runtime.AgentLogger.w(
                "ErrorBudgetController",
                "Error budget EXHAUSTED! Halting all automated change mechanisms (GEN-363, GEN-CI-2, Phase XII)."
            )
            return false
        }
        changeMechanismPermitted = true
        return true
    }

    fun evaluateNow(currentTimeMs: Long): Boolean {
        val dashboard = healthAggregator.buildDashboard(currentTimeMs)
        return checkAndEnforceBudget(dashboard)
    }
}
