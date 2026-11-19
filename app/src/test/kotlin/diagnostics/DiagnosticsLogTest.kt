package diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.file.Files

class DiagnosticsLogTest {
    @Test
    fun retainsOnlyTheNewestEventsAndSupportsLevelFiltering() {
        assertEquals(500, DiagnosticsLog.DEFAULT_CAPACITY)
        val log = DiagnosticsLog(capacity = 2)
        log.record(DiagnosticLevel.INFO, DiagnosticComponent.APP, DiagnosticCode.APP_STARTED)
        log.record(DiagnosticLevel.WARNING, DiagnosticComponent.CHAT_STORE, DiagnosticCode.CHAT_STORE_FALLBACK)
        log.record(
            DiagnosticLevel.ERROR,
            DiagnosticComponent.NATIVE,
            DiagnosticCode.NATIVE_LOAD_FAILED,
            DiagnosticField.flag(DiagnosticFieldKey.SUCCESS, false)
        )

        assertEquals(2, log.size())
        val all = log.snapshot()
        assertFalse(all.any { it.contains(DiagnosticCode.APP_STARTED.wireCode) })
        assertTrue(all.any { it.contains(DiagnosticCode.CHAT_STORE_FALLBACK.wireCode) })
        assertEquals(1, log.snapshot(level = DiagnosticLevel.ERROR).size)
        val thresholdLog = DiagnosticsLog()
        thresholdLog.record(DiagnosticLevel.INFO, DiagnosticComponent.APP, DiagnosticCode.APP_STARTED)
        thresholdLog.record(DiagnosticLevel.WARNING, DiagnosticComponent.CHAT_STORE, DiagnosticCode.CHAT_STORE_FALLBACK)
        val warningAndAbove = thresholdLog.exportReport(minimumLevel = DiagnosticLevel.WARNING)
        assertTrue(warningAndAbove.contains(DiagnosticCode.CHAT_STORE_FALLBACK.wireCode))
        assertFalse(warningAndAbove.contains(DiagnosticCode.APP_STARTED.wireCode))
    }

    @Test
    fun recordsExceptionClassChainButNeverMessagesOrFreeText() {
        val log = DiagnosticsLog()
        val secret = "private-prompt-must-never-appear"
        val failure = IllegalStateException(secret, IllegalArgumentException("nested-$secret"))
        log.recordFailure(
            DiagnosticLevel.ERROR,
            DiagnosticComponent.CHAT_STORE,
            DiagnosticCode.CHAT_STORE_CRYPTO_FAILURE,
            failure,
            DiagnosticField.number(DiagnosticFieldKey.BYTE_COUNT, 13L)
        )

        val report = log.snapshot().single()
        assertTrue(report.contains(IllegalStateException::class.java.name))
        assertTrue(report.contains(IllegalArgumentException::class.java.name))
        assertTrue(report.contains("BYTE_COUNT=13"))
        assertFalse(report.contains(secret))
        assertFalse(report.contains("nested-"))
    }

    @Test
    fun uncaughtFailureStoresOnlyItsClassAndAtMostTenSanitizedFrames() {
        val log = DiagnosticsLog()
        val secret = "private-prompt-do-not-log"
        val failure = IllegalStateException(secret).apply {
            stackTrace = Array(10) { index ->
                val className = if (index == 0) "com.safe.Frame0" else "com.safe.${"A".repeat(140)}$index"
                val methodName = if (index == 0) "run0" else "${"m".repeat(110)}$index"
                StackTraceElement(className, methodName, "$secret.kt", index + 1)
            }
        }

        log.recordUncaughtException(failure)
        val report = log.snapshot().single()

        assertTrue(report.contains(IllegalStateException::class.java.name))
        assertTrue(report.contains("com.safe.Frame0.run0:1"))
        assertEquals(10, Regex("STACK_FRAME_\\d+=").findAll(report).count())
        assertFalse(report.contains(secret))
        assertFalse(report.contains(".kt"))
    }

    @Test
    fun debugPersistenceRotatesWithin256KiBAndClearRemovesFiles() {
        val directory = Files.createTempDirectory("diagnostics-test").toFile()
        val persistence = AndroidDiagnosticsPersistence(directory)
        val log = DiagnosticsLog(capacity = 500, persistence = persistence)

        val largeStack = IllegalStateException().apply {
            stackTrace = Array(10) { index ->
                StackTraceElement("com.safe.${"A".repeat(140)}$index", "${"m".repeat(110)}$index", "ignored.kt", index + 1)
            }
        }
        repeat(100) { log.recordUncaughtException(largeStack) }
        val files = directory.listFiles().orEmpty().filter { it.isFile }
        assertTrue(files.isNotEmpty())
        assertTrue(files.all { it.length() <= 128L * 1024L })
        assertTrue(files.sumOf { it.length() } <= 256L * 1024L)

        log.clear()
        assertEquals(0, log.size())
        assertFalse(directory.exists())
    }

    @Test
    fun publicDiagnosticInputApiHasNoFreeFormStringParameter() {
        val publicTypes = listOf(DiagnosticsLog::class.java, DiagnosticField::class.java)
        publicTypes.forEach { type ->
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) }
                .forEach { method ->
                    assertFalse(
                        "${type.simpleName}.${method.name} must not accept caller text",
                        method.parameterTypes.any { it == String::class.java || it == CharSequence::class.java }
                    )
                }
        }
    }
}
