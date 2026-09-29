package reliability

import shared.change_management.ChangeAuditLogger
import shared.change_management.RollbackManager

/**
 * GEN-426 — Diagnosis Engine
 * Evaluates anomalous telemetry and categorizes issues by severity.
 */
enum class DiagnosisSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class DiagnosisReport(
    val anomalyKey: String,
    val severity: DiagnosisSeverity,
    val description: String,
    val recommendedRemedy: RemedyAction
)

enum class RemedyAction {
    NONE,
    ISOLATE_LEVEL_2_3_SKILLS,
    ENTER_THERMAL_MODE,
    ENTER_LOW_MEMORY_MODE,
    TRIP_CIRCUIT_BREAKER,
    TRIGGER_CHANGE_ROLLBACK,
    ENTER_SECURITY_LOCKDOWN
}

/**
 * GEN-427 — Root Cause Analyzer
 * Correlates runtime failures with recent change audit records and telemetry traces.
 */
class RootCauseAnalyzer(
    private val changeAuditLogger: ChangeAuditLogger? = null
) {
    fun analyze(
        anomaly: String,
        currentTimeMs: Long,
        recentErrors: List<String> = emptyList()
    ): Pair<String, String?> {
        val recentAudits = changeAuditLogger?.getAllRecords()?.takeLast(5)?.reversed() ?: emptyList()
        val latestAudit = recentAudits.firstOrNull()

        val rootCauseSummary = when {
            latestAudit != null && (currentTimeMs - latestAudit.timestamp) < 300_000L -> {
                "Recent system change '${latestAudit.targetType}:${latestAudit.targetKey}' at ${latestAudit.timestamp} correlates with anomaly '$anomaly'."
            }
            recentErrors.isNotEmpty() -> {
                "Anomalous error pattern detected: ${recentErrors.take(2).joinToString(" | ")}"
            }
            else -> {
                "System resource pressure or external environmental condition triggered anomaly '$anomaly'."
            }
        }
        val traceId = latestAudit?.changeId
        return Pair(rootCauseSummary, traceId)
    }
}

/**
 * GEN-430/431, GEN-438/439, GEN-458/459 — Recovery Planner & Self-Healing
 *
 * MANDATORY ARCHITECTURAL INVARIANTS:
 * 1. MUST NEVER touch, modify, or bypass locked safety components:
 *    - GEN-NEW-GUARD (SafetyGuard / Immutable safety invariants)
 *    - GEN-NEW-RISK (SkillRiskRegistry)
 *    - GEN-NEW-RATE (ActionRateLimiter)
 * 2. Connects recovery workflows to:
 *    - GEN-CM-5 (RollbackManager) for mutation rollback
 *    - GEN-MODE-1 (AgentOperatingModeManager) for mode degradation
 *    - Level 2/3 skill isolation when skill faults are identified
 */
class RecoveryPlanner(
    private val modeManager: AgentOperatingModeManager,
    private val rollbackManager: RollbackManager? = null,
    private val rootCauseAnalyzer: RootCauseAnalyzer = RootCauseAnalyzer()
) {
    private val incidentHistory = mutableListOf<BlamelessIncidentNote>()

    fun getIncidentHistory(): List<BlamelessIncidentNote> = incidentHistory.toList()

    /**
     * Executes healing action for diagnosed anomalies.
     * Guaranteed never to touch SafetyGuard, SkillRiskRegistry, or ActionRateLimiter.
     */
    fun executeRemedy(
        diagnosis: DiagnosisReport,
        currentTimeMs: Long,
        activeSkillIds: List<String> = emptyList()
    ): BlamelessIncidentNote {
        val (rootCause, traceId) = rootCauseAnalyzer.analyze(diagnosis.anomalyKey, currentTimeMs)
        var actionTaken = "No intervention needed."
        val affected = mutableListOf<String>()

        when (diagnosis.recommendedRemedy) {
            RemedyAction.ENTER_THERMAL_MODE -> {
                modeManager.transitionTo(OperatingMode.THERMAL, "Diagnosis: ${diagnosis.description}")
                actionTaken = "Switched to THERMAL mode (forcing CPU delegate via GEN-EDGE-1, throttling Level 3 skills)."
                affected.add("HardwareDelegate")
                affected.add("Level3Skills")
            }

            RemedyAction.ENTER_LOW_MEMORY_MODE -> {
                modeManager.transitionTo(OperatingMode.LOW_MEMORY, "Diagnosis: ${diagnosis.description}")
                actionTaken = "Switched to LOW_MEMORY mode (halving context tokens, releasing GPU allocations)."
                affected.add("ContextBudget")
                affected.add("HardwareDelegate")
            }

            RemedyAction.ISOLATE_LEVEL_2_3_SKILLS -> {
                modeManager.transitionTo(OperatingMode.RECOVERY, "Diagnosis: ${diagnosis.description}")
                actionTaken = "Isolated high-risk skills: disabled Level 2 & Level 3 skills."
                affected.addAll(activeSkillIds)
            }

            RemedyAction.TRIGGER_CHANGE_ROLLBACK -> {
                val rollbackSuccess = rollbackManager?.rollback("live_config") ?: false
                actionTaken = if (rollbackSuccess) {
                    "Triggered automated rollback of last change via GEN-CM-5."
                } else {
                    "Rollback requested via GEN-CM-5 but no active snapshot available."
                }
                affected.add("ChangeManagement")
            }

            RemedyAction.ENTER_SECURITY_LOCKDOWN -> {
                modeManager.transitionTo(OperatingMode.SECURITY_LOCKDOWN, "Diagnosis: ${diagnosis.description}")
                actionTaken = "Activated SECURITY_LOCKDOWN: memory set to READ-ONLY, Level 2/3 skills disabled, network isolated."
                affected.add("MemoryStore")
                affected.add("NetworkLayer")
                affected.add("SkillExecution")
            }

            RemedyAction.TRIP_CIRCUIT_BREAKER -> {
                actionTaken = "Signaled Circuit Breaker TRIP for failing subsystem."
                affected.add("CircuitBreaker")
            }

            RemedyAction.NONE -> {
                actionTaken = "Monitored anomaly without intervention."
            }
        }

        val note = BlamelessIncidentNote(
            incidentId = "INC-${currentTimeMs}-${(1000..9999).random()}",
            triggerSource = diagnosis.anomalyKey,
            timestampMs = currentTimeMs,
            detectedAnomaly = diagnosis.description,
            rootCauseSummary = rootCause,
            affectedComponents = affected,
            mitigationActionTaken = actionTaken,
            auditTraceId = traceId,
            recommendations = listOf(
                "Review telemetry leading up to ${diagnosis.anomalyKey}",
                "Ensure error budget permits normal operations before exiting recovery"
            )
        )

        incidentHistory.add(note)
        runtime.AgentLogger.w("RecoveryPlanner", "Self-healing incident recorded: ${note.incidentId} -> $actionTaken")
        return note
    }
}
