package com.rupleide.netfix.core.dpibypass

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rupleide.netfix.BuildConfig
import com.rupleide.netfix.MainActivity
import com.rupleide.netfix.core.debug.AppDebugManager
import com.rupleide.netfix.data.Mode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class VideoTransportSmokeTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun waitForProxy(port: Int) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                Socket().use { it.connect(InetSocketAddress(loopback, port), 200) }
                return
            } catch (_: Exception) {
                Thread.sleep(25)
            }
        }
        throw AssertionError("SOCKS proxy did not start")
    }

    @Test
    fun nativeProxyTransfersAFullVideoSizedChunk() {
        val expected = ByteArray(512 * 1024) { (it % 251).toByte() }
        val server = ServerSocket(0, 1, loopback).apply { soTimeout = 10_000 }
        val serverError = AtomicReference<Throwable?>()
        val serverThread = thread(name = "video-test-server") {
            try {
                server.accept().use { client ->
                    client.soTimeout = 10_000
                    assertEquals(42, client.getInputStream().read())
                    client.getOutputStream().write(expected)
                    client.getOutputStream().flush()
                }
            } catch (e: Throwable) {
                serverError.set(e)
            }
        }
        val proxyPort = ServerSocket(0, 1, loopback).use { it.localPort }
        val proxy = ByeDpiProxy()
        val args = arrayOf("ciadpi", "--ip", "127.0.0.1", "--port", proxyPort.toString())
        val proxyThread = thread(name = "video-test-proxy") {
            proxy.startProxy(ByeDpiProxyCmdPreferences(args + VideoFastFail.argsFor(args, mobile = false)))
        }
        try {
            waitForProxy(proxyPort)
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(loopback, proxyPort))).use { client ->
                client.soTimeout = 10_000
                client.connect(InetSocketAddress(loopback, server.localPort), 5_000)
                client.getOutputStream().write(42)
                val received = ByteArray(expected.size)
                var offset = 0
                while (offset < received.size) {
                    val count = client.getInputStream().read(received, offset, received.size - offset)
                    if (count < 0) break
                    offset += count
                }
                assertEquals(expected.size, offset)
                assertArrayEquals(expected, received)
            }
            serverThread.join(5_000)
            serverError.get()?.let { throw AssertionError("Test server failed", it) }
        } finally {
            server.close()
            proxy.stopProxy()
            serverThread.join(5_000)
            proxyThread.join(5_000)
        }
        assertFalse("Native proxy failed to stop", proxyThread.isAlive)
    }

    @Test
    fun appLaunchesAndTunnelLibraryLoads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        if (BuildConfig.APPLICATION_ID.endsWith(".debug")) {
            val label = context.packageManager.getApplicationLabel(context.applicationInfo).toString()
            assertEquals("NetFix Mobile (тест)", label)
        }
        Class.forName(TProxyService::class.java.name)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(Lifecycle.State.RESUMED, activity.state)
        }
    }

    @Test
    fun vpnStaysUpAndDoesNotRecreateItsOwnTunnel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun shell(command: String): String =
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        fun waitUntil(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return
                SystemClock.sleep(50)
            }
            throw AssertionError("$message\n${AppDebugManager.getLogs().take(30).joinToString("\n")}")
        }

        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        assertNull("VPN consent was not granted by the emulator", VpnService.prepare(context))
        val prefs = context.getPreferences()
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        prefs.edit()
            .putBoolean("byedpi_root_mode", false)
            .putBoolean("econom_mode", true)
            .putBoolean("byedpi_enable_cmd_settings", true)
            .putString("byedpi_cmd_args", "-o1")
            .remove("byedpi_cmd_args_wifi")
            .remove("byedpi_cmd_args_mobile")
            .putString("byedpi_proxy_port", port.toString())
            .putStringSet("selected_apps", setOf("com.android.settings"))
            .putInt("apps_defaults_version", 3)
            .putBoolean("compat_cf_unfreeze", false)
            .putBoolean("compat_secure_dns", false)
            .apply()
        prefs.setAppListMode(AppListMode.SelectedAppsOnly)
        AppDebugManager.setEnabled(context, true)
        AppDebugManager.clear()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue("VPN service was not started", ServiceManager.start(context, Mode.VPN))
                waitUntil("VPN failed to connect") { ByeDpiVpnService.isConnected }
                // The reported failure recreated the VPN roughly every two seconds.
                SystemClock.sleep(7_000)
                val beforeStop = AppDebugManager.getLogs()
                assertTrue("VPN disconnected while idle", ByeDpiVpnService.isConnected)
                assertEquals(
                    "VPN recreated its tunnel while the physical network was unchanged:\n${beforeStop.take(40).joinToString("\n")}",
                    1,
                    beforeStop.count { "ByeDpiVpnService: Tun2Socks started." in it }
                )
                assertEquals(0, beforeStop.count { "ByeDpiVpnService: Stopping tun2socks" in it })
                ServiceManager.stop(context)
                waitUntil("VPN failed to stop") { !ByeDpiVpnService.isAlive && !ByeDpiVpnService.isConnected }
                SystemClock.sleep(2_500)
                assertFalse("VPN restarted after Stop", ByeDpiVpnService.isAlive || ByeDpiVpnService.isConnected)
                assertEquals(1, AppDebugManager.getLogs().count { "ByeDpiVpnService: Tun2Socks started." in it })
            }
        } finally {
            ServiceManager.stop(context)
            AppDebugManager.setEnabled(context, false)
        }
    }

    @Test
    fun recoveredDnsAndCloudflareHelpersRunOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Exercise the reconstructed classes on ART, not merely the JVM compiler.
        CloudflareUnfreeze.nextAction(context)
        CloudflareUnfreeze.extraArgs(context)
        try {
            assertNotNull(SecureDnsRelay.start(context))
            assertTrue(SecureDnsRelay.isRunning)
        } finally {
            SecureDnsRelay.stop()
        }
        assertFalse(SecureDnsRelay.isRunning)
    }
}
