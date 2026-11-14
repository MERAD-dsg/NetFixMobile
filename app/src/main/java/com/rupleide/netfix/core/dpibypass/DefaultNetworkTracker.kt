package com.rupleide.netfix.core.dpibypass

/** Tracks changes of the best physical network; the first callback is a baseline.
 *
 * Call this only with non-VPN networks from a best-match/request callback, never
 * with every available network. Retain the old network on loss so a later
 * replacement is detected even when Android delivers onLost first.
 */
internal class DefaultNetworkTracker<T : Any> {
    @Volatile
    private var current: T? = null

    fun onAvailable(network: T): Boolean {
        val previous = current
        current = network
        return previous != null && previous != network
    }

    fun onCapabilities(network: T, isVpn: Boolean, hasInternet: Boolean): Boolean {
        if (isVpn || !hasInternet) return false
        return onAvailable(network)
    }

    fun isCurrent(network: T): Boolean = current == network
}
