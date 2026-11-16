package inference

import java.io.File

/** Reads per-CPU maximum frequencies; no device-specific CPU IDs or core counts are assumed. */
internal class CpuMaxFrequencyReader(
    private val cpuRoot: File = File("/sys/devices/system/cpu")
) {
    fun readStrongCoreIds(): List<Int>? {
        val entries = runCatching { cpuRoot.listFiles()?.toList() }.getOrNull() ?: return null
        val cpuDirectories = entries.mapNotNull { entry ->
            cpuIdFrom(entry.name)?.let { cpuId -> cpuId to entry }
        }
        if (cpuDirectories.isEmpty() || cpuDirectories.any { !it.second.isDirectory }) return null

        val readings = mutableListOf<CpuFrequency>()
        for ((cpuId, directory) in cpuDirectories) {
            val frequency = runCatching {
                File(directory, "cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
            }.getOrNull()?.takeIf { it >= MIN_VALID_CPUINFO_MAX_FREQ_KHZ } ?: return null
            readings += CpuFrequency(cpuId, frequency)
        }

        val highestFrequency = readings.maxOfOrNull(CpuFrequency::maxFrequencyKhz) ?: return null
        val strongCoreIds = readings.asSequence()
            .filter { it.maxFrequencyKhz == highestFrequency }
            .map(CpuFrequency::cpuId)
            .sorted()
            .toList()
        return strongCoreIds.takeIf { it.isNotEmpty() }
    }

    private fun cpuIdFrom(directoryName: String): Int? {
        if (!directoryName.startsWith(CPU_DIRECTORY_PREFIX)) return null
        val suffix = directoryName.removePrefix(CPU_DIRECTORY_PREFIX)
        if (suffix.isEmpty() || suffix.any { it !in ASCII_DIGIT_RANGE }) return null
        return suffix.toIntOrNull()
    }

    private data class CpuFrequency(val cpuId: Int, val maxFrequencyKhz: Long)

    private companion object {
        const val CPU_DIRECTORY_PREFIX = "cpu"
        val ASCII_DIGIT_RANGE = '0'..'9'
        // PROVISIONAL - pending GEN-PERF-1
        const val MIN_VALID_CPUINFO_MAX_FREQ_KHZ = 1L
    }
}
