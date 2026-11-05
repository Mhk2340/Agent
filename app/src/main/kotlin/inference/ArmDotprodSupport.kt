package inference

import java.io.File

/** True only when every exposed ARM CPU feature line contains the compiled instruction set. */
internal fun hasArmDotprodAndFp16(cpuInfo: String): Boolean {
    val featureSets = cpuInfo.lineSequence().mapNotNull { line ->
        val key = line.substringBefore(':').trim()
        if (!line.contains(':') || !key.equals("Features", ignoreCase = true)) {
            null
        } else {
            line.substringAfter(':')
                .trim()
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
                .toSet()
        }
    }.toList()

    return featureSets.isNotEmpty() && featureSets.all { features ->
        REQUIRED_ARM_CPUINFO_FLAGS.all { requiredFlag ->
            features.any { it.equals(requiredFlag, ignoreCase = true) }
        }
    }
}

/** Runs the loader only after the required CPU feature was confirmed; errors fail closed. */
internal fun loadNativeLibraryAfterArmDotprodCheck(
    cpuInfoReader: () -> String = { File(ARM_CPUINFO_PATH).readText() },
    libraryLoader: () -> Unit
) {
    val cpuInfo = runCatching(cpuInfoReader).getOrElse {
        throw UnsupportedArmCpuFeatureException()
    }
    if (!hasArmDotprodAndFp16(cpuInfo)) throw UnsupportedArmCpuFeatureException()
    libraryLoader()
}

internal class UnsupportedArmCpuFeatureException :
    IllegalStateException("The local CPU runtime is unavailable")

private const val ARM_CPUINFO_PATH = "/proc/cpuinfo"
private val REQUIRED_ARM_CPUINFO_FLAGS = setOf("asimddp", "asimdhp")
