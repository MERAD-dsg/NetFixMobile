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
}
