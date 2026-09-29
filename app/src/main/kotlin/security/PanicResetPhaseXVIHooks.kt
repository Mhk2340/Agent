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

class DefaultPanicResetPhaseXVIHooks : PanicResetPhaseXVIHooks {
    @Volatile
    private var jobsCancelledCount: Int = 0
    @Volatile
    private var workspacePurged: Boolean = false

    override fun cancelActiveJobs(): Int {
        runtime.AgentLogger.w(
            "PanicResetPhaseXVIHooks",
            "cancelActiveJobs called"
        )
        jobsCancelledCount = 0
        return jobsCancelledCount
    }

    override fun purgeActiveSharedWorkspace(): Boolean {
        runtime.AgentLogger.w(
            "PanicResetPhaseXVIHooks",
            "purgeActiveSharedWorkspace called"
        )
        workspacePurged = true
        return true
    }

    fun wasWorkspacePurged(): Boolean = workspacePurged
}
