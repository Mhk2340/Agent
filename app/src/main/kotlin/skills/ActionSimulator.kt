package skills

import safety.RiskLevel
import safety.SafetyGuard

/**
 * GEN-350 — Action Simulation (Dry-Run Simulator)
 *
 * MANDATORY REQUIREMENT:
 * Simulation is now MANDATORY FOR ALL RISK LEVELS (Level 1, Level 2, and Level 3),
 * not just Level 3.
 *
 * Produces structured dry-run simulation detailing targets, parameters, and mutations,
 * directly feeding GEN-NEW-CONFIRM.
 */
class ActionSimulator(
    private val safetyGuard: SafetyGuard
) {
    fun simulate(request: SkillExecutionRequest): ActionSimulationResult {
        // Resolve risk level from SafetyGuard (defaults to LEVEL_3 if unknown)
        val registeredRisk = safetyGuard.riskRegistry.getEffectiveRiskLevel(request.skillName)

        val recipient = request.params["recipient"] ?: request.params["to"] ?: request.params["target"]
        val willMutate = when (registeredRisk) {
            RiskLevel.LEVEL_1 -> false
            RiskLevel.LEVEL_2 -> true
            RiskLevel.LEVEL_3 -> true
        }

        val impactDescription = when (registeredRisk) {
            RiskLevel.LEVEL_1 -> "Read-only inspection of ${request.skillName}. No system or external state modified."
            RiskLevel.LEVEL_2 -> "Reversible state modification by ${request.skillName}. Local changes can be rolled back."
            RiskLevel.LEVEL_3 -> "High-stakes or irreversible operation by ${request.skillName}. External side-effects cannot be undone automatically."
        }

        val details = buildString {
            append("Action '${request.skillName}' (${registeredRisk.name}) with parameters: ${request.params}")
            if (recipient != null) {
                append(", target recipient: '$recipient'")
            }
            if (request.guidedByProcedureId != null) {
                append(", guided by procedure: '${request.guidedByProcedureId}'")
            }
        }

        return ActionSimulationResult(
            skillName = request.skillName,
            resolvedRiskLevel = registeredRisk,
            simulatedImpact = impactDescription,
            targetRecipient = recipient,
            willMutateExternalState = willMutate,
            detailedSummary = details,
            estimatedDurationMs = if (willMutate) 250L else 50L
        )
    }
}
