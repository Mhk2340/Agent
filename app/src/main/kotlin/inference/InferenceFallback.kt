package inference

import android.app.ActivityManager
import android.content.Context
import contracts.InferenceRequest
import contracts.InferenceResponse

/** Backend-local telemetry allows canaries to compare like-for-like prefill latency. */
data class InferenceDelegateOutput(
    val response: InferenceResponse,
    val prefillLatencyMs: Long? = null
)

/** Existing InferenceProvider contract stays unchanged; tests inject fake delegate backends here. */
fun interface InferenceDelegateExecutor {
    fun execute(delegate: HardwareDelegate, request: InferenceRequest): Result<InferenceDelegateOutput>
}

/** Optional typed GPU error for backends; any non-memory-pressure GPU executor failure falls back persistently. */
class GpuDelegateExecutionException(
    val failureCode: GpuDelegateFailureCode,
    cause: Throwable? = null
) : RuntimeException("GPU delegate execution failed: $failureCode", cause)

enum class GpuDelegateFailureCode {
    INITIALIZATION,
    EXECUTION,
    DEVICE_LOST,
    UNSUPPORTED_OPERATION
}

fun interface MemoryPressureSignalProvider {
    fun isMemoryPressureActive(): Boolean
}

object NoMemoryPressureSignal : MemoryPressureSignalProvider {
    override fun isMemoryPressureActive(): Boolean = false
}

class AndroidMemoryPressureSignalProvider(context: Context) : MemoryPressureSignalProvider {
    private val activityManager = context.applicationContext
        .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    override fun isMemoryPressureActive(): Boolean {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.lowMemory
    }
}
