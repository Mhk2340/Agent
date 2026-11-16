package inference

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CpuMaxFrequencyReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun returnsEveryCpuTiedAtTheHighestReportedMaxFrequency() {
        val cpuRoot = temporaryFolder.newFolder("cpu-root")
        writeCpuFrequency(cpuRoot, FIXTURE_CPU_A, LOWER_FREQUENCY_FIXTURE_KHZ)
        writeCpuFrequency(cpuRoot, FIXTURE_CPU_B, HIGHEST_FREQUENCY_FIXTURE_KHZ)
        writeCpuFrequency(cpuRoot, FIXTURE_CPU_C, MIDDLE_FREQUENCY_FIXTURE_KHZ)
        writeCpuFrequency(cpuRoot, FIXTURE_CPU_D, HIGHEST_FREQUENCY_FIXTURE_KHZ)

        val result = CpuMaxFrequencyReader(cpuRoot).readStrongCoreIds()

        assertEquals(listOf(FIXTURE_CPU_B, FIXTURE_CPU_D), result)
    }

    @Test
    fun missingPerCpuMaxFrequencyRejectsPinnedCoreDiscoveryInsteadOfGuessing() {
        val cpuRoot = temporaryFolder.newFolder("cpu-root-missing")
        writeCpuFrequency(cpuRoot, FIXTURE_CPU_A, LOWER_FREQUENCY_FIXTURE_KHZ)
        File(cpuRoot, "cpu$FIXTURE_CPU_B/cpufreq").mkdirs()

        val result = CpuMaxFrequencyReader(cpuRoot).readStrongCoreIds()

        assertEquals(null, result)
    }

    @Test
    fun malformedFrequencyDataIsRejected() {
        val cpuRoot = temporaryFolder.newFolder("cpu-root-malformed")
        writeCpuFrequency(cpuRoot, FIXTURE_CPU_A, LOWER_FREQUENCY_FIXTURE_KHZ)
        writeCpuFrequency(cpuRoot, FIXTURE_CPU_B, MALFORMED_FREQUENCY_FIXTURE_KHZ)

        val result = CpuMaxFrequencyReader(cpuRoot).readStrongCoreIds()

        assertEquals(null, result)
    }

    private fun writeCpuFrequency(cpuRoot: File, cpuId: Int, frequencyKhz: String) {
        val frequencyFile = File(cpuRoot, "cpu$cpuId/cpufreq/cpuinfo_max_freq")
        check(frequencyFile.parentFile?.mkdirs() == true)
        frequencyFile.writeText(frequencyKhz)
    }

    private companion object {
        // PROVISIONAL - pending GEN-PERF-1: arbitrary fixture IDs, not assignments for a target device.
        const val FIXTURE_CPU_A = 4
        // PROVISIONAL - pending GEN-PERF-1: arbitrary fixture IDs, not assignments for a target device.
        const val FIXTURE_CPU_B = 12
        // PROVISIONAL - pending GEN-PERF-1: arbitrary fixture IDs, not assignments for a target device.
        const val FIXTURE_CPU_C = 21
        // PROVISIONAL - pending GEN-PERF-1: arbitrary fixture IDs, not assignments for a target device.
        const val FIXTURE_CPU_D = 35
        // PROVISIONAL - pending GEN-PERF-1: sample sysfs values in kHz used only by this parser test.
        const val LOWER_FREQUENCY_FIXTURE_KHZ = "1000000"
        // PROVISIONAL - pending GEN-PERF-1: sample sysfs values in kHz used only by this parser test.
        const val MIDDLE_FREQUENCY_FIXTURE_KHZ = "1500000"
        // PROVISIONAL - pending GEN-PERF-1: sample sysfs values in kHz used only by this parser test.
        const val HIGHEST_FREQUENCY_FIXTURE_KHZ = "2000000"
        // PROVISIONAL - pending GEN-PERF-1
        const val MALFORMED_FREQUENCY_FIXTURE_KHZ = "not-a-frequency"
    }
}
