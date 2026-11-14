package com.rupleide.netfix.core.dpibypass

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rupleide.netfix.MainActivity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        Class.forName(TProxyService::class.java.name)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(Lifecycle.State.RESUMED, activity.state)
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
