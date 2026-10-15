package security

import contracts.InferenceProvider

/**
 * Phase XVI Multi-Agent & External AI Collaboration Panic Reset Hooks.
 * Requirements in GEN-SEC-3:
 * 1. Cancel any active Async Job in JobManager (GEN-MA-3) to prevent unauthorized API quota consumption.
 * 2. Purge active Shared Workspace (GEN-MA-8) intermediate data simultaneously with memory wipe.
 *
 * Implemented by RealPanicResetPhaseXVIHooks in multiagent package.
 */
interface PanicResetPhaseXVIHooks {
    fun cancelActiveJobs(): Int
    fun purgeActiveSharedWorkspace(): Boolean
}

/**
 * TEST ONLY — explicit no-op stub. This class does not cancel jobs or purge any workspace and
 * MUST NOT be wired into a production AgentContainer or production RealSecurityLockdownEnforcer.
 */
class DefaultPanicResetPhaseXVIHooks : PanicResetPhaseXVIHooks {
    @Volatile
    private var jobsCancelledCount: Int = 0
    @Volatile
    private var workspacePurged: Boolean = false

    override fun cancelActiveJobs(): Int {
        runtime.AgentLogger.w(
            "PanicResetPhaseXVIHooks",
            "TEST-ONLY NO-OP: no AsyncJobManager was supplied; no jobs were cancelled."
        )
        jobsCancelledCount = 0
        return jobsCancelledCount
    }

    override fun purgeActiveSharedWorkspace(): Boolean {
        runtime.AgentLogger.w(
            "PanicResetPhaseXVIHooks",
            "TEST-ONLY NO-OP: no SharedWorkspaceManager was supplied; no workspace was purged."
        )
        workspacePurged = false
        return false
    }

    fun wasWorkspacePurged(): Boolean = workspacePurged
}
