package com.rupleide.netfix

import com.rupleide.netfix.core.compat.CompatFeature
import com.rupleide.netfix.core.compat.CompatibilityManager
import com.rupleide.netfix.core.dpibypass.VideoFastFail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFastFailTest {

    @Test
    fun wifiTimeoutOnlyAppliesDuringConnectionSetup() {
        assertEquals(
            listOf("-T", "1.5:0:3:1"),
            VideoFastFail.argsFor(arrayOf("ciadpi", "-r5+s", "-s25+s"), mobile = false)
        )
    }

    @Test
    fun mobileGetsLongerSetupTimeout() {
        assertEquals(
            listOf("-T", "2.5:0:3:1"),
            VideoFastFail.argsFor(arrayOf("ciadpi", "-r5+s"), mobile = true)
        )
    }

    @Test
    fun ownTimeoutInStrategyIsKept() {
        for (args in listOf(
            arrayOf("ciadpi", "-T", "3", "-r5+s"),
            arrayOf("ciadpi", "--timeout", "3"),
            arrayOf("ciadpi", "--timeout=3"),
            arrayOf("ciadpi", "-T3"),
            arrayOf("ciadpi", "-T3", "--quic-giveup", "900")
        )) {
            assertEquals(emptyList<String>(), VideoFastFail.argsFor(args, mobile = false))
        }
    }

    @Test
    fun neverAddsPersistentVideoTimeoutOrForgedQuicReply() {
        val args = VideoFastFail.argsFor(arrayOf("ciadpi", "-a1"), mobile = false)
        assertFalse("--keep-timeout-suffix" in args)
        assertFalse("--quic-giveup" in args)
        // A user-specified QUIC option is left alone; it is not duplicated.
        assertEquals(listOf("-T", "1.5:0:3:1"), VideoFastFail.argsFor(arrayOf("ciadpi", "--quic-giveup=900"), mobile = false))
    }

    @Test
    fun offWhenKeepingPreviousVersion() {
        val prefs = InMemoryPreferences()
        CompatibilityManager.adoptLatestForFreshInstall(prefs)
        assertTrue(CompatibilityManager.videoFastFailEnabled(prefs))
        CompatibilityManager.setBehaviorVersion(prefs, "1.0.4")
        assertFalse(CompatibilityManager.videoFastFailEnabled(prefs))
        assertFalse(CompatibilityManager.featureEnabled(prefs, CompatFeature.VideoFastFail))
    }
}
