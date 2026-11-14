package com.rupleide.netfix.core.dpibypass

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.rupleide.netfix.core.compat.CompatibilityManager

object VideoFastFail {
    const val WIFI_ACK_TIMEOUT_SECONDS = "1.5"
    const val MOBILE_ACK_TIMEOUT_SECONDS = "2.5"

    fun extraArgs(context: Context, strategyArgs: Array<String>): List<String> {
        if (!CompatibilityManager.videoFastFailEnabled(context.getPreferences())) return emptyList()
        return argsFor(strategyArgs, isMobileNetwork(context))
    }

    fun argsFor(strategyArgs: Array<String>, mobile: Boolean): List<String> {
        if (strategyArgs.any { it == "-T" || it.startsWith("--timeout=") || it == "--timeout" || (it.startsWith("-T") && it.length > 2) }) {
            return emptyList()
        }
        val seconds = if (mobile) MOBILE_ACK_TIMEOUT_SECONDS else WIFI_ACK_TIMEOUT_SECONDS
        // Limit the timeout to connection setup: ByeDPI clears it after the first
        // response. Keeping TCP_USER_TIMEOUT for the entire googlevideo stream can
        // terminate an otherwise healthy download after a brief loss of ACKs.
        // Do not inject a synthetic QUIC version-negotiation response either; some
        // players treat it as a failed connection rather than falling back to TCP.
        return listOf("-T", "$seconds:0:3:1")
    }

    private fun isMobileNetwork(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.allNetworks
            .mapNotNull { manager.getNetworkCapabilities(it) }
            .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            .none { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
    }.getOrDefault(true)
}
