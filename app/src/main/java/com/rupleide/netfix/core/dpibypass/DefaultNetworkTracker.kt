package com.rupleide.netfix.core.dpibypass

/** Tracks default-network changes without treating the first callback as a change.
 *
 * Do not forget the old network on loss: Android can report loss before the new
 * default becomes available, and that transition still requires a reconnect.
 */
internal class DefaultNetworkTracker<T : Any> {
    @Volatile
    private var current: T? = null

    fun onAvailable(network: T): Boolean {
        val previous = current
        current = network
        return previous != null && previous != network
    }

    fun isCurrent(network: T): Boolean = current == network
}
