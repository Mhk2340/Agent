package multiagent

import security.PanicResetPhaseXVIHooks

/**
 * Real Phase XVI Implementation of PanicResetPhaseXVIHooks.
 * Integrates directly with AsyncJobManager (GEN-MA-3) and SharedWorkspaceManager (GEN-MA-8).
 * Completely replaces the provisional stub from Phase XIII.
 */
class RealPanicResetPhaseXVIHooks(
    private val jobManager: AsyncJobManager,
    private val workspaceManager: SharedWorkspaceManager,
    private val purgeLocalChatHistoryAction: () -> Boolean = { true }
) : PanicResetPhaseXVIHooks {

    override fun cancelActiveJobs(): Int {
        val count = jobManager.cancelAllActiveJobs("Panic Reset: Device security emergency")
        runtime.AgentLogger.w(
            "RealPanicResetHooks",
            "Cancelled $count active Async Jobs in JobManager (GEN-MA-3)."
        )
        return count
    }

    override fun purgeActiveSharedWorkspace(): Boolean {
        workspaceManager.purgeAllActiveWorkspaces()
        runtime.AgentLogger.w(
            "RealPanicResetHooks",
            "Purged all active Shared Workspaces (GEN-MA-8)."
        )
        return true
    }

    override fun purgeLocalChatHistory(): Boolean = purgeLocalChatHistoryAction()
}
