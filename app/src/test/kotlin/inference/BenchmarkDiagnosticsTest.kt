package inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@Config(sdk = [28], manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class BenchmarkDiagnosticsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun ringIsDurableBoundedAndSanitizesNonFingerprintText() {
        val prefs = context.getSharedPreferences("diag-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val store = BenchmarkDiagnosticsStore(prefs)
        repeat(BenchmarkDiagnosticsStore.MAX_EVENTS + 7) { index ->
            store.record(
                BenchmarkDiagnosticEvent(
                    timestampEpochMs = index.toLong(),
                    type = BenchmarkDiagnosticType.WORKER_EXECUTION,
                    fingerprintHash = "MARKER_PRIVATE_USER_TEXT",
                    result = BenchmarkDiagnosticResult.NOT_MEASURED,
                    reason = BenchmarkDiagnosticReason.PROFILE_INVALID
                )
            )
        }

        assertEquals(BenchmarkDiagnosticsStore.MAX_EVENTS, store.readEvents().size)
        assertTrue(store.readEvents().all { it.fingerprintHash == null })
        assertFalse(store.renderTextReport().contains("MARKER_PRIVATE_USER_TEXT"))
        assertFalse(store.renderTextReport().contains("prompt"))
        assertEquals(store.readEvents(), BenchmarkDiagnosticsStore(prefs).readEvents())
    }

    @Test
    fun queuedWorkBeyondProvisionalAgeEmitsOnlyOneStarvationEventUntilRecovery() {
        val store = BenchmarkDiagnosticsStore(
            context.getSharedPreferences("starved-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        )
        val fingerprint = "a".repeat(64)
        val dayMs = 24L * 60L * 60L * 1000L
        store.noteEnqueued(fingerprint, 1_000L, validResultExists = false)
        store.checkStarvation(fingerprint, 1_000L + 6L * dayMs, validResultExists = false)
        assertFalse(store.readEvents().any { it.type == BenchmarkDiagnosticType.BENCHMARK_STARVED })

        store.checkStarvation(fingerprint, 1_000L + 8L * dayMs, validResultExists = false)
        store.checkStarvation(fingerprint, 1_000L + 9L * dayMs, validResultExists = false)
        assertEquals(1, store.readEvents().count { it.type == BenchmarkDiagnosticType.BENCHMARK_STARVED })

        store.checkStarvation(fingerprint, 1_000L + 10L * dayMs, validResultExists = true)
        store.noteEnqueued(fingerprint, 1_000L + 11L * dayMs, validResultExists = false)
        assertEquals(1, store.readEvents().count { it.type == BenchmarkDiagnosticType.BENCHMARK_STARVED })
    }
}
