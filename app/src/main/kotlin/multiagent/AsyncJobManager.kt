package multiagent

import reliability.SliEvent
import reliability.SliEventSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GEN-MA-3 — Async Job Manager
 *
 * Requirements:
 * 1. Executes long-running creative and analytical jobs in background.
 * 2. Job states: QUEUED -> RUNNING -> AWAITING_CONFIRMATION -> COMPLETED | FAILED | CANCELLED
 * 3. Budget Limits (Anti-runaway):
 *    - max_llm_calls_per_job = 30
 *    - max_job_duration_minutes = 45
 *    - max_retries_per_role = 2
 * 4. SliEventSink Integration:
 *    Reports SliEvent.BackgroundJob (Tier 2 SLO: completion rate > 85%)
 *    Reports SliEvent.ProviderCall (Tier 2 SLO: provider failure rate < 5%)
 * 5. Integrated with Panic Reset: All active jobs cancelled immediately.
 */
enum class JobStatus {
    QUEUED,
    RUNNING,
    AWAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class AsyncJob(
    val jobId: String,
    val title: String,
    val initialUserPrompt: String,
    var status: JobStatus = JobStatus.QUEUED,
    var currentRoleIndex: Int = 0,
    var totalRoles: Int = 3,
    var llmCallCount: Int = 0,
    val createdAtMs: Long,
    var completedAtMs: Long? = null,
    var cancellationReason: String? = null
)

class AsyncJobManager(
    private val sliEventSink: SliEventSink? = null,
    // Budget ceilings
    val maxLlmCallsPerJob: Int = 30,
    val maxJobDurationMs: Long = 45L * 60 * 1000,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val jobs = ConcurrentHashMap<String, AsyncJob>()
    private val executionQueue = ConcurrentLinkedQueue<String>()

    fun submitJob(jobId: String, title: String, initialUserPrompt: String): AsyncJob {
        val job = AsyncJob(
            jobId = jobId,
            title = title,
            initialUserPrompt = initialUserPrompt,
            status = JobStatus.QUEUED,
            createdAtMs = clock()
        )
        jobs[jobId] = job
        executionQueue.add(jobId)
        return job
    }

    fun startNextJob(): AsyncJob? {
        val nextId = executionQueue.poll() ?: return null
        val job = jobs[nextId] ?: return null
        job.status = JobStatus.RUNNING
        return job
    }

    fun recordLlmCall(jobId: String, success: Boolean, isProviderFailure: Boolean = false) {
        val job = jobs[jobId] ?: return
        job.llmCallCount++

        // Report Provider Call SLI event to Phase XI Health Monitoring
        sliEventSink?.recordEvent(
            SliEvent.ProviderCall(
                timestampMs = clock(),
                isFailed = isProviderFailure || !success
            )
        )

        if (job.llmCallCount >= maxLlmCallsPerJob) {
            failJob(jobId, "Budget exceeded: reached max LLM calls ceiling ($maxLlmCallsPerJob)")
        }
    }

    fun completeJob(jobId: String): Boolean {
        val job = jobs[jobId] ?: return false
        if (job.status != JobStatus.RUNNING && job.status != JobStatus.AWAITING_CONFIRMATION) return false
        job.status = JobStatus.COMPLETED
        job.completedAtMs = clock()

        // Report Background Job completion SLI event
        sliEventSink?.recordEvent(
            SliEvent.BackgroundJob(
                timestampMs = clock(),
                isSuccessful = true
            )
        )
        return true
    }

    fun failJob(jobId: String, reason: String): Boolean {
        val job = jobs[jobId] ?: return false
        job.status = JobStatus.FAILED
        job.completedAtMs = clock()
        job.cancellationReason = reason

        // Report Background Job completion SLI event
        sliEventSink?.recordEvent(
            SliEvent.BackgroundJob(
                timestampMs = clock(),
                isSuccessful = false
            )
        )
        return true
    }

    fun cancelJob(jobId: String, reason: String): Boolean {
        val job = jobs[jobId] ?: return false
        job.status = JobStatus.CANCELLED
        job.completedAtMs = clock()
        job.cancellationReason = reason
        return true
    }

    /**
     * Panic Reset Hook implementation: Cancels all active (QUEUED, RUNNING, AWAITING_CONFIRMATION) jobs.
     */
    fun cancelAllActiveJobs(reason: String = "Panic Reset invoked"): Int {
        var cancelledCount = 0
        for (job in jobs.values) {
            if (job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING || job.status == JobStatus.AWAITING_CONFIRMATION) {
                job.status = JobStatus.CANCELLED
                job.completedAtMs = clock()
                job.cancellationReason = reason
                cancelledCount++
            }
        }
        executionQueue.clear()
        runtime.AgentLogger.w("AsyncJobManager", "Cancelled $cancelledCount active jobs due to Panic Reset.")
        return cancelledCount
    }

    fun getJob(jobId: String): AsyncJob? = jobs[jobId]
    fun getAllJobs(): List<AsyncJob> = jobs.values.toList()
}
