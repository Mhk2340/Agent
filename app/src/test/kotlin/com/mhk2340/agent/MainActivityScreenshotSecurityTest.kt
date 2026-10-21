package com.mhk2340.agent

import android.app.Activity
import android.view.WindowManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityScreenshotSecurityTest {
    @Test
    fun debugBuildLeavesScreenshotsAllowed() {
        assertTrue("The unit-test variant must use the debug BuildConfig", BuildConfig.DEBUG)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        applySecureWindowFlag(activity.window)

        val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
        assertFalse(
            "BuildConfig.DEBUG must leave FLAG_SECURE unset",
            (activity.window.attributes.flags and secureFlag) == secureFlag
        )
    }

    @Test
    fun releaseBuildKeepsScreenshotProtection() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        applySecureWindowFlag(activity.window, isDebugBuild = false)

        val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
        assertTrue(
            "Non-debug builds must keep FLAG_SECURE set",
            (activity.window.attributes.flags and secureFlag) == secureFlag
        )
    }
}
