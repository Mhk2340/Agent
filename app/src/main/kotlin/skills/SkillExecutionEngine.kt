package skills

import safety.GuardDecision
import safety.RiskLevel
import safety.SafetyGuard
import runtime.AgentLogger
import runtime.Identity
import security.AppLockAccess

/**
 * GEN-328 — Skill Execution Engine
 *
 * MANDATORY ARCHITECTURAL INVARIANTS:
 * 1. Strictly Non-Bypassable Guard: Every execution MUST pass through SafetyGuard.evaluate()
 *    prior to invocation. No shortcuts or bypasses exist.
 * 2. Simulation for all risk levels: GEN-350 simulation runs before execution.
 * 3. Fail-Closed Audit Logging: If writing to GEN-NEW-AUDIT fails, execution is aborted.
 * 4. Idempotency Key Reservation Pattern (GEN-353/354):
 *    - Bounded retry with reservation to prevent duplicate side effects.
 *    - Query relies STRICTLY on idempotency key and status.
 */
class SkillExecutionEngine(
    val safetyGuard: SafetyGuard,
    val auditLog: ActionAuditLog,
    private val appLockAccess: AppLockAccess,
    val simulator: ActionSimulator = ActionSimulator(safetyGuard),
    val promptGenerator: ConfirmationPromptGenerator = ConfirmationPromptGenerator(),
    val rateLimiter: ActionRateLimiter = ActionRateLimiter(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val securityEpochProvider: (() -> Long)? = null,
    private val isSecurityLockdownActive: () -> Boolean = { false }
) {
    // Registry of actual executable skill handlers
    private val handlers = mutableMapOf<String, (Map<String, String>) -> String>()

    private fun lockdownFailure(request: SkillExecutionRequest, reason: String) = SkillExecutionResult(
        actionId = request.actionId,
        skillName = request.skillName,
        status = ExecutionStatus.FAILED,
        output = null,
        failureReason = reason,
        executionCostMs = 0L
    )

    private fun isLockedFailClosed(): Boolean = try {
        isSecurityLockdownActive()
    } catch (_: Exception) {
        true
    }

    private fun isAppLockLockedFailClosed(): Boolean = try {
        !appLockAccess.isAppUnlocked()
    } catch (_: Exception) {
        true
    }

    private fun securityEpochFailure(request: SkillExecutionRequest, riskLevel: RiskLevel): String? {
        val epochReader = securityEpochProvider ?: return null
        val currentEpoch = try {
            epochReader()
        } catch (_: Exception) {
            return "Security epoch unavailable; action rejected"
        }
        val key = request.idempotencyKey
        if (riskLevel != RiskLevel.LEVEL_1 && key == null) {
            return "Epoch-bound idempotency key required; action rejected"
        }
        if (key != null && !EpochBoundIdempotencyKey.belongsToEpoch(key, currentEpoch)) {
            return "Idempotency key is invalid for the current reset epoch; action rejected"
        }
        return null
    }

    fun registerHandler(skillName: String, handler: (Map<String, String>) -> String) {
        handlers[skillName] = handler
    }

    /**
     * Executes a skill request through the full mandatory security pipeline.
     */
    fun executeSkill(request: SkillExecutionRequest): SkillExecutionResult {
        val startTime = clock()
        val auditId = Identity.nextId("aud")
        if (isAppLockLockedFailClosed()) {
            return lockdownFailure(request, "App Lock is locked; action rejected")
        }
        if (isLockedFailClosed()) {
            return lockdownFailure(request, "Agent security lockdown is active; action rejected")
        }

        // 1. Dry-run Simulation (GEN-350) - Mandatory for ALL risk levels
        val simulation = simulator.simulate(request)
        securityEpochFailure(request, simulation.resolvedRiskLevel)?.let { reason ->
            return lockdownFailure(request, reason)
        }

        // 2. Idempotency Key Check (Reservation Pattern for Level 2/3)
        if (request.idempotencyKey != null && simulation.resolvedRiskLevel != RiskLevel.LEVEL_1) {
            val existing = auditLog.reserveIdempotencyKey(
                key = request.idempotencyKey,
                actionId = request.actionId,
                skillName = request.skillName
            )
            if (existing != null) {
                // Key already in-flight or completed
                if (existing.executionStatus == "SUCCEEDED") {
                    return SkillExecutionResult(
                        actionId = request.actionId,
                        skillName = request.skillName,
                        status = ExecutionStatus.IDEMPOTENT_SKIPPED,
                        output = existing.output,
                        failureReason = null,
                        executionCostMs = 0L
                    )
                } else if (existing.executionStatus == "IN_FLIGHT") {
                    return SkillExecutionResult(
                        actionId = request.actionId,
                        skillName = request.skillName,
                        status = ExecutionStatus.IDEMPOTENT_SKIPPED,
                        output = null,
                        failureReason = "Action with idempotency key is already IN_FLIGHT",
                        executionCostMs = 0L
                    )
                }
            }
        }

        // 3. Safety Guard Mandatory Evaluation (GEN-NEW-GUARD)
        val initiatorStr = request.initiator.name.lowercase()
        val guardDecision = safetyGuard.evaluate(
            actionName = request.skillName,
            initiator = initiatorStr,
            userConfirmed = request.userConfirmed
        )

        // Handle Guard Decisions
        when (guardDecision) {
            is GuardDecision.Rejected -> {
                val cost = clock() - startTime
                val auditRecord = ActionAuditRecord(
                    auditId = auditId,
                    actionId = request.actionId,
                    skillName = request.skillName,
                    riskLevel = simulation.resolvedRiskLevel.value,
                    initiator = initiatorStr,
                    params = request.params,
                    confirmationPrompt = null,
                    userConfirmed = request.userConfirmed,
                    executionStatus = "REJECTED_BY_GUARD",
                    output = null,
                    failureReason = guardDecision.reason,
                    costMs = cost,
                    idempotencyKey = request.idempotencyKey,
                    guidedByProcedureId = request.guidedByProcedureId,
                    origin = request.origin,
                    timestamp = startTime
                )
                auditLog.recordAction(auditRecord)

                return SkillExecutionResult(
                    actionId = request.actionId,
                    skillName = request.skillName,
                    status = ExecutionStatus.REJECTED_BY_GUARD,
                    output = null,
                    failureReason = guardDecision.reason,
                    executionCostMs = cost
                )
            }

            is GuardDecision.RequiresConfirmation -> {
                val cost = clock() - startTime
                val prompt = promptGenerator.generatePrompt(request, simulation)

                val auditRecord = ActionAuditRecord(
                    auditId = auditId,
                    actionId = request.actionId,
                    skillName = request.skillName,
                    riskLevel = simulation.resolvedRiskLevel.value,
                    initiator = initiatorStr,
                    params = request.params,
                    confirmationPrompt = prompt.promptText,
                    userConfirmed = false,
                    executionStatus = "REQUIRES_CONFIRMATION",
                    output = null,
                    failureReason = guardDecision.reason,
                    costMs = cost,
                    idempotencyKey = request.idempotencyKey,
                    guidedByProcedureId = request.guidedByProcedureId,
                    origin = request.origin,
                    timestamp = startTime
                )
                auditLog.recordAction(auditRecord)

                return SkillExecutionResult(
                    actionId = request.actionId,
                    skillName = request.skillName,
                    status = ExecutionStatus.REQUIRES_CONFIRMATION,
                    output = prompt.promptText,
                    failureReason = guardDecision.reason,
                    executionCostMs = cost
                )
            }

            is GuardDecision.Approved -> {
                // Continue to Rate Limiter & Execution
            }
        }

        // 4. Rate Limiter Check (GEN-NEW-RATE)
        val rateCheck = rateLimiter.checkAndRecord(simulation.resolvedRiskLevel)
        if (!rateCheck.allowed) {
            val cost = clock() - startTime
            val reason = "Rate limit exceeded for Level 3 actions: max ${rateCheck.maxAllowed} per hour"
            val auditRecord = ActionAuditRecord(
                auditId = auditId,
                actionId = request.actionId,
                skillName = request.skillName,
                riskLevel = simulation.resolvedRiskLevel.value,
                initiator = initiatorStr,
                params = request.params,
                confirmationPrompt = null,
                userConfirmed = request.userConfirmed,
                executionStatus = "FAILED",
                output = null,
                failureReason = reason,
                costMs = cost,
                idempotencyKey = request.idempotencyKey,
                guidedByProcedureId = request.guidedByProcedureId,
                origin = request.origin,
                timestamp = startTime
            )
            auditLog.recordAction(auditRecord)

            return SkillExecutionResult(
                actionId = request.actionId,
                skillName = request.skillName,
                status = ExecutionStatus.FAILED,
                output = null,
                failureReason = reason,
                executionCostMs = cost
            )
        }

        // Recheck immediately before the irreversible bridge; App Lock or reset may have changed
        // while the guard/rate/audit stages were running.
        if (isAppLockLockedFailClosed()) {
            return lockdownFailure(request, "App Lock is locked; action rejected")
        }
        if (isLockedFailClosed()) {
            return lockdownFailure(request, "Agent security lockdown is active; action rejected")
        }
        securityEpochFailure(request, simulation.resolvedRiskLevel)?.let { reason ->
            return lockdownFailure(request, reason)
        }

        // 5. Execution Bridge Invocation
        val handler = handlers[request.skillName]
        if (handler == null) {
            val cost = clock() - startTime
            val reason = "No executable handler registered for skill '${request.skillName}'"
            val auditRecord = ActionAuditRecord(
                auditId = auditId,
                actionId = request.actionId,
                skillName = request.skillName,
                riskLevel = simulation.resolvedRiskLevel.value,
                initiator = initiatorStr,
                params = request.params,
                confirmationPrompt = null,
                userConfirmed = request.userConfirmed,
                executionStatus = "FAILED",
                output = null,
                failureReason = reason,
                costMs = cost,
                idempotencyKey = request.idempotencyKey,
                guidedByProcedureId = request.guidedByProcedureId,
                origin = request.origin,
                timestamp = startTime
            )
            auditLog.recordAction(auditRecord)

            return SkillExecutionResult(
                actionId = request.actionId,
                skillName = request.skillName,
                status = ExecutionStatus.FAILED,
                output = null,
                failureReason = reason,
                executionCostMs = cost
            )
        }

        val resultOutput: String?
        val failureReason: String?
        val executionStatus: ExecutionStatus

        val runResult = runCatching { handler(request.params) }
        if (runResult.isSuccess) {
            resultOutput = runResult.getOrNull()
            failureReason = null
            executionStatus = ExecutionStatus.SUCCEEDED
        } else {
            resultOutput = null
            failureReason = runResult.exceptionOrNull()?.message ?: "Execution failed with exception"
            executionStatus = ExecutionStatus.FAILED
        }

        val cost = clock() - startTime

        // 6. Action Audit Log Persistence (GEN-NEW-AUDIT) - Fail-closed requirement
        val finalRecord = ActionAuditRecord(
            auditId = auditId,
            actionId = request.actionId,
            skillName = request.skillName,
            riskLevel = simulation.resolvedRiskLevel.value,
            initiator = initiatorStr,
            params = request.params,
            confirmationPrompt = null,
            userConfirmed = request.userConfirmed,
            executionStatus = executionStatus.name,
            output = resultOutput,
            failureReason = failureReason,
            costMs = cost,
            idempotencyKey = request.idempotencyKey,
            guidedByProcedureId = request.guidedByProcedureId,
            origin = request.origin,
            timestamp = startTime
        )

        val writeResult = auditLog.recordAction(finalRecord)
        if (writeResult.isFailure) {
            // Fail-closed invariant: Action without log is strictly prohibited
            return SkillExecutionResult(
                actionId = request.actionId,
                skillName = request.skillName,
                status = ExecutionStatus.FAILED,
                output = null,
                failureReason = "Fail-Closed Invariant Violation: Audit log write failed",
                executionCostMs = cost
            )
        }

        return SkillExecutionResult(
            actionId = request.actionId,
            skillName = request.skillName,
            status = executionStatus,
            output = resultOutput,
            failureReason = failureReason,
            executionCostMs = cost
        )
    }
}
