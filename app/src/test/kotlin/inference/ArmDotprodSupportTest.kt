package inference

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmDotprodSupportTest {
    @Test
    fun requiredFeaturesAreAcceptedOnlyWhenEveryFeaturesLineReportsDotprodAndFp16() {
        val cpuInfo = """
            Features : fp asimddp asimdhp neon
            Features : asimddp asimdhp aes
        """.trimIndent()

        assertTrue(hasArmDotprodAndFp16(cpuInfo))
    }

    @Test
    fun requiredFeaturesAreRejectedWhenEitherFlagIsMissingOrAbsentFromAnyFeaturesLine() {
        assertFalse(hasArmDotprodAndFp16("Features : fp neon aes"))
        assertFalse(hasArmDotprodAndFp16("Features : fp asimddp neon"))
        assertFalse(
            hasArmDotprodAndFp16(
                "Features : fp asimddp asimdhp neon\nFeatures : fp asimdhp"
            )
        )
        assertFalse(hasArmDotprodAndFp16("Hardware: ARM64"))
    }

    @Test
    fun libraryLoaderRunsOnlyAfterFeatureCheckAndUnreadableCpuInfoFailsClosed() {
        var loaded = false

        val unsupported = runCatching {
            loadNativeLibraryAfterArmDotprodCheck(
                cpuInfoReader = { "Features : fp neon" },
                libraryLoader = { loaded = true }
            )
        }.exceptionOrNull()
        assertTrue(unsupported is UnsupportedArmCpuFeatureException)
        assertFalse(loaded)

        val unreadable = runCatching {
            loadNativeLibraryAfterArmDotprodCheck(
                cpuInfoReader = { error("unavailable") },
                libraryLoader = { loaded = true }
            )
        }.exceptionOrNull()
        assertTrue(unreadable is UnsupportedArmCpuFeatureException)
        assertFalse(loaded)

        loadNativeLibraryAfterArmDotprodCheck(
            cpuInfoReader = { "Features : fp asimddp asimdhp neon" },
            libraryLoader = { loaded = true }
        )
        assertTrue(loaded)
    }
}
