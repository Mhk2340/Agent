package inference

import android.content.Context
import androidx.work.Operation
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

fun interface PanicResetBenchmarkWorkCanceller {
    /** Cancels every queued/running benchmark item and its periodic starvation monitor. */
    fun cancelForPanicReset()
}

/** Production WorkManager cancellation for GEN-EDGE-1; all operations are awaited before return. */
class WorkManagerPanicResetBenchmarkWorkCanceller(
    context: Context,
    private val operationTimeoutSeconds: Long = 10L
) : PanicResetBenchmarkWorkCanceller {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun cancelForPanicReset() {
        val failedOperations = mutableListOf<String>()
        cancel("benchmark-tag") {
            workManager.cancelAllWorkByTag(HARDWARE_BENCHMARK_WORK_TAG)
        }?.let(failedOperations::add)
        cancel("starvation-monitor-tag") {
            workManager.cancelAllWorkByTag(HARDWARE_BENCHMARK_STARVATION_WORK_TAG)
        }?.let(failedOperations::add)
        cancel("starvation-monitor-unique-work") {
            workManager.cancelUniqueWork(HARDWARE_BENCHMARK_STARVATION_WORK_NAME)
        }?.let(failedOperations::add)
        if (failedOperations.isNotEmpty()) {
            throw IllegalStateException("Benchmark WorkManager cancellation incomplete")
        }
    }

    private fun cancel(name: String, operation: () -> Operation): String? = try {
        operation().result.get(operationTimeoutSeconds, TimeUnit.SECONDS)
        null
    } catch (_: Exception) {
        // Do not propagate WorkManager exception messages or causes into security/reset logs.
        name
    }
}
