package inference

import contracts.InferenceError
import contracts.InferenceRequest
import contracts.InferenceResponse
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps production llama.cpp calls and affinity changes on one private CPU thread. UI/shared
 * executors only wait for results; sched_setaffinity is never called on those caller threads.
 */
internal class DedicatedCpuInferenceDispatcher(
    private val backend: LlamaCppInferenceBackend,
    private val strongCoreIdsProvider: () -> List<Int>? = {
        CpuMaxFrequencyReader().readStrongCoreIds()
    },
    private val reportGenericFallback: (String) -> Unit = {}
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val workerThread = AtomicReference<Thread?>(null)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(
        namedDaemonThreadFactory(INFERENCE_THREAD_NAME, workerThread)
    )
    private val eligibilityWatcher: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        namedDaemonThreadFactory(ELIGIBILITY_THREAD_NAME)
    )

    // Accessed only from the private inference worker.
    private var activeBackendProfile: BackendProfile? = null
    private var lastOriginalMask: List<Int>? = null

    fun generate(
        request: InferenceRequest,
        isStillAllowed: () -> Boolean = { true }
    ): Result<InferenceResponse> {
        if (closed.get()) return failure()
        if (Thread.currentThread() === workerThread.get()) {
            return generateOnInferenceThread(request, isStillAllowed)
        }

        val future = try {
            executor.submit<Result<InferenceResponse>> {
                generateOnInferenceThread(request, isStillAllowed)
            }
        } catch (_: RejectedExecutionException) {
            return failure()
        }
        val watcher = try {
            eligibilityWatcher.scheduleWithFixedDelay(
                {
                    if (closed.get() || !isAllowedSafely(isStillAllowed)) {
                        backend.requestAbortGeneration()
                        future.cancel(true)
                    }
                },
                ELIGIBILITY_POLL_MILLIS,
                ELIGIBILITY_POLL_MILLIS,
                TimeUnit.MILLISECONDS
            )
        } catch (_: RejectedExecutionException) {
            future.cancel(true)
            backend.requestAbortGeneration()
            return failure()
        }

        return try {
            future.get()
        } catch (_: InterruptedException) {
            future.cancel(true)
            backend.requestAbortGeneration()
            Thread.currentThread().interrupt()
            failure()
        } catch (_: ExecutionException) {
            failure()
        } catch (_: CancellationException) {
            failure()
        } finally {
            watcher.cancel(false)
        }
    }

    /** Unloads on the same worker, after any active request has aborted and reset its affinity. */
    fun unloadModel() {
        if (closed.get()) return
        backend.requestAbortGeneration()
        if (Thread.currentThread() === workerThread.get()) {
            restoreCurrentWorkerMaskAndClose()
            return
        }
        runWorkerActionAndWait(::restoreCurrentWorkerMaskAndClose)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        backend.requestAbortGeneration()
        eligibilityWatcher.shutdownNow()
        if (Thread.currentThread() === workerThread.get()) {
            restoreCurrentWorkerMaskAndClose()
            executor.shutdown()
            return
        }
        // Queue unload behind the active request; its finally block restores the saved mask first.
        runWorkerActionAndWait(::restoreCurrentWorkerMaskAndClose, allowWhenClosed = true)
        executor.shutdown()
    }

    private fun generateOnInferenceThread(
        request: InferenceRequest,
        isStillAllowed: () -> Boolean
    ): Result<InferenceResponse> {
        val currentMask = readMask()
        val originalMask = lastOriginalMask ?: currentMask
        if (lastOriginalMask != null && currentMask != lastOriginalMask && !restoreMask(lastOriginalMask!!)) {
            reportRestoreFailureSafely()
            return failure()
        }
        val strongCoreIds = runCatching(strongCoreIdsProvider)
            .getOrNull()
            ?.distinct()
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }

        var result = failure()
        var affinityRestored = true
        try {
            if (isAllowedSafely(isStillAllowed) && !closed.get()) {
                val targetCores = strongCoreIds?.takeIf { originalMask != null }
                val pinnedProfile = targetCores?.let { BackendProfile(it.size, it) }
                if (pinnedProfile != null) {
                    // Any old context is unloaded while the worker still has its baseline mask.
                    ensureBackendProfile(pinnedProfile)
                    val setSucceeded = backend.setThreadAffinity(targetCores)
                    val effectiveMask = readMask()
                    if (setSucceeded && effectiveMask == targetCores) {
                        result = backend.generate(request)
                        if (!isAllowedSafely(isStillAllowed)) result = failure()
                    } else {
                        reportFallbackSafely()
                        if (originalMask == null || !restoreMask(originalMask)) {
                            reportRestoreFailureSafely()
                        } else {
                            result = runFallback(request, originalMask, isStillAllowed)
                        }
                    }
                } else {
                    reportFallbackSafely()
                    result = runFallback(request, originalMask, isStillAllowed)
                }
            }
        } catch (_: Throwable) {
            result = failure()
        } finally {
            if (originalMask != null) {
                affinityRestored = readMask() == originalMask || restoreMask(originalMask)
                lastOriginalMask = originalMask
            }
            val abortOrUnloadRequested = closed.get() ||
                Thread.currentThread().isInterrupted ||
                !isAllowedSafely(isStillAllowed)
            if (abortOrUnloadRequested) {
                runCatching { backend.close() }
                activeBackendProfile = null
            }
        }

        if (!affinityRestored) {
            reportRestoreFailureSafely()
            return failure()
        }
        return result
    }

    private fun runFallback(
        request: InferenceRequest,
        originalMask: List<Int>?,
        isStillAllowed: () -> Boolean
    ): Result<InferenceResponse> {
        // The H-4 fallback stays CPU-only and uses two threads on the actual original mask.
        val fallbackProfile = BackendProfile(FALLBACK_N_THREADS, originalMask)
        ensureBackendProfile(fallbackProfile)
        val actualFallbackMask = readMask()
        if (originalMask != null && actualFallbackMask != originalMask && !restoreMask(originalMask)) {
            reportRestoreFailureSafely()
            return failure()
        }
        if (!isAllowedSafely(isStillAllowed) || closed.get()) return failure()
        val generated = backend.generate(request)
        return if (isAllowedSafely(isStillAllowed)) generated else failure()
    }

    private fun ensureBackendProfile(profile: BackendProfile) {
        if (activeBackendProfile != profile) {
            // Called only before pinning or after restoring the baseline mask.
            backend.close()
            backend.configureThreadCounts(profile.nThreads, profile.nThreads)
            activeBackendProfile = profile
        } else {
            backend.configureThreadCounts(profile.nThreads, profile.nThreads)
        }
    }

    private fun restoreCurrentWorkerMaskAndClose() {
        // A request's finally normally restored this; retain and re-verify the last saved baseline
        // as a final unload/lock/Panic Reset safeguard without ever resetting to a pinned target.
        lastOriginalMask?.let { baseline ->
            if (readMask() != baseline && !restoreMask(baseline)) reportRestoreFailureSafely()
        }
        backend.close()
        activeBackendProfile = null
    }

    private fun runWorkerActionAndWait(action: () -> Unit, allowWhenClosed: Boolean = false) {
        if (closed.get() && !allowWhenClosed) return
        val future: Future<*> = try {
            executor.submit(action)
        } catch (_: RejectedExecutionException) {
            return
        }
        var interrupted = false
        try {
            while (true) {
                try {
                    future.get()
                    return
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (_: ExecutionException) {
                    return
                } catch (_: CancellationException) {
                    return
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun readMask(): List<Int>? = backend.currentThreadAffinity()
        ?.distinct()
        ?.sorted()
        ?.takeIf { it.isNotEmpty() }

    private fun restoreMask(originalMask: List<Int>): Boolean {
        if (readMask() == originalMask) return true
        backend.setThreadAffinity(originalMask)
        return readMask() == originalMask
    }

    private fun isAllowedSafely(check: () -> Boolean): Boolean =
        runCatching(check).getOrDefault(false)

    private fun reportFallbackSafely() {
        runCatching { reportGenericFallback(GENERIC_FALLBACK_MESSAGE) }
    }

    private fun reportRestoreFailureSafely() {
        runCatching { reportGenericFallback(GENERIC_RESTORE_FAILURE_MESSAGE) }
    }

    private fun failure(): Result<InferenceResponse> = Result.failure(
        AgentModelException(InferenceError.Unavailable(GENERIC_FAILURE_MESSAGE))
    )

    private data class BackendProfile(val nThreads: Int, val affinityMask: List<Int>?)

    private companion object {
        const val INFERENCE_THREAD_NAME = "agent-dedicated-cpu-inference"
        const val ELIGIBILITY_THREAD_NAME = "agent-cpu-inference-watchdog"
        // PROVISIONAL - pending GEN-PERF-1
        const val FALLBACK_N_THREADS = 2
        // PROVISIONAL - pending GEN-PERF-1
        const val ELIGIBILITY_POLL_MILLIS = 100L
        const val GENERIC_FALLBACK_MESSAGE = "CPU affinity unavailable; using the two-thread CPU fallback"
        const val GENERIC_RESTORE_FAILURE_MESSAGE = "CPU affinity reset could not be verified"
        const val GENERIC_FAILURE_MESSAGE = "Local CPU inference failed"

        fun namedDaemonThreadFactory(
            name: String,
            reference: AtomicReference<Thread?>? = null
        ): ThreadFactory = ThreadFactory { task ->
            Thread(task, name).apply {
                isDaemon = true
                reference?.set(this)
            }
        }
    }
}
