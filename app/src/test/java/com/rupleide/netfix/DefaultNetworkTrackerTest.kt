package com.rupleide.netfix

import com.rupleide.netfix.core.dpibypass.DefaultNetworkTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkTrackerTest {
    @Test
    fun onlyAnActualDefaultNetworkChangeRequiresRestart() {
        val tracker = DefaultNetworkTracker<String>()
        assertFalse(tracker.onAvailable("wifi"))
        assertFalse(tracker.onAvailable("wifi"))
        assertTrue(tracker.onAvailable("mobile"))
        assertFalse(tracker.onAvailable("mobile"))
        assertTrue(tracker.isCurrent("mobile"))
        assertFalse(tracker.isCurrent("wifi"))
    }

    @Test
    fun reconnectForSupersededNetworkCanBeDiscarded() {
        val tracker = DefaultNetworkTracker<String>()
        tracker.onAvailable("wifi")
        assertTrue(tracker.onAvailable("mobile"))
        assertTrue(tracker.onAvailable("ethernet"))
        assertFalse(tracker.isCurrent("mobile"))
        assertTrue(tracker.isCurrent("ethernet"))
    }

    @Test
    fun ownVpnAppearingAndDisappearingDoesNotStartAReconnectLoop() {
        val tracker = DefaultNetworkTracker<String>()
        assertFalse(tracker.onCapabilities("mobile", isVpn = false, hasInternet = true))
        repeat(4) { index ->
            // A recreated VPN has a different Network identity each time.
            assertFalse(tracker.onCapabilities("own-vpn-$index", isVpn = true, hasInternet = true))
            assertFalse(tracker.onCapabilities("mobile", isVpn = false, hasInternet = true))
            assertTrue(tracker.isCurrent("mobile"))
        }
    }

    @Test
    fun aRealPhysicalHandoverStillReconnectsOnce() {
        val tracker = DefaultNetworkTracker<String>()
        assertFalse(tracker.onCapabilities("mobile", isVpn = false, hasInternet = true))
        assertFalse(tracker.onCapabilities("own-vpn", isVpn = true, hasInternet = true))
        assertTrue(tracker.onCapabilities("wifi", isVpn = false, hasInternet = true))
        assertFalse(tracker.onCapabilities("wifi", isVpn = false, hasInternet = true))
        assertFalse(tracker.isCurrent("mobile"))
        assertTrue(tracker.isCurrent("wifi"))
    }

    @Test
    fun networkWithoutInternetCannotSupersedeThePhysicalDefault() {
        val tracker = DefaultNetworkTracker<String>()
        tracker.onCapabilities("mobile", isVpn = false, hasInternet = true)
        assertFalse(tracker.onCapabilities("local-only", isVpn = false, hasInternet = false))
        assertTrue(tracker.isCurrent("mobile"))
    }
}
