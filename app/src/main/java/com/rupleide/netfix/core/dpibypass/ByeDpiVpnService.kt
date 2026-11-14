package com.rupleide.netfix.core.dpibypass

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService.SERVICE_INTERFACE
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.rupleide.netfix.core.debug.AppDebugManager as Log
import androidx.lifecycle.lifecycleScope
import com.rupleide.netfix.MainActivity
import com.rupleide.netfix.R
import com.rupleide.netfix.core.compat.CompatibilityManager
import com.rupleide.netfix.data.AppStatus
import com.rupleide.netfix.data.Mode
import com.rupleide.netfix.data.ServiceStatus
import com.rupleide.netfix.data.Sender
import com.rupleide.netfix.data.START_ACTION
import com.rupleide.netfix.data.STOP_ACTION
import com.rupleide.netfix.data.RESUME_ACTION
import com.rupleide.netfix.data.PAUSE_ACTION
import com.rupleide.netfix.data.STARTED_BROADCAST
import com.rupleide.netfix.data.STOPPED_BROADCAST
import com.rupleide.netfix.data.FAILED_BROADCAST
import com.rupleide.netfix.data.SENDER
import com.rupleide.netfix.data.setStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class ByeDpiVpnService : LifecycleVpnService() {
    private val byeDpiProxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var activeProxyPort: Int?
        get() = liveProxyPort
        set(value) {
            liveProxyPort = value
        }
    private val mutex = Mutex()
    private var wakeLock = null as android.os.PowerManager.WakeLock?
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var shouldRun = false
    private val isRunning: Boolean
        get() = status == ServiceStatus.Connected

    companion object {
        private val TAG: String = ByeDpiVpnService::class.java.simpleName

        @Volatile
        var liveProxyPort: Int? = null
            private set
        private const val FOREGROUND_SERVICE_ID: Int = NETFIX_NOTIFICATION_ID
        private const val PAUSE_NOTIFICATION_ID: Int = 3
        private const val CLOUDFLARE_CHECK_DELAY_MS = 3_000L
        private const val CLOUDFLARE_PERIODIC_CHECK_MS = 45L * 60 * 1000
        private const val NETWORK_SWITCH_SETTLE_MS = 500L
        private const val NOTIFICATION_CHANNEL_ID: String = NETFIX_NOTIFICATION_CHANNEL_ID
        private const val VPN_MTU = 1500
        @Volatile
        private var status: ServiceStatus = ServiceStatus.Disconnected

        val isConnected: Boolean
            get() = status == ServiceStatus.Connected

        @Volatile
        var isAlive: Boolean = false
            private set
    }

    private fun acquireWakeLock() {
    }

    private fun releaseWakeLock() {
    }

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name
        )
    }

    private fun registerPhysicalNetworkMonitor() {
        if (networkCallback != null) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            private val networks = DefaultNetworkTracker<Network>()
            private var bestNetwork: Network? = null

            override fun onAvailable(network: Network) {
                bestNetwork = network
            }

            override fun onLost(network: Network) {
                if (bestNetwork == network) bestNetwork = null
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (bestNetwork != network) return
                // A default-network callback can report our own VPN, even when
                // NetFix itself is excluded from the tunnel. Only consider a
                // physical network, and wait for its capabilities before using it.
                if (!networks.onCapabilities(
                        network,
                        isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                        hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ) || !isRunning || !shouldRun) return
                val kind = networkKindOf(capabilities)
                val registration = this
                lifecycleScope.launch {
                    // Coalesce short-lived handovers; never restart for an
                    // obsolete callback or after the user pressed Stop/Pause.
                    delay(NETWORK_SWITCH_SETTLE_MS)
                    var restarted = false
                    mutex.withLock {
                        if (networkCallback !== registration || bestNetwork != network || !networks.isCurrent(network) || !isRunning || !shouldRun) return@withLock
                        try {
                            Log.i(TAG, "Physical network changed; reconnecting VPN")
                            stopProxy()
                            stopTun2Socks()
                            if (networkCallback !== registration || !shouldRun) return@withLock
                            // If another handover occurred while stopping, restore
                            // the tunnel rather than leaving a "connected" service
                            // without one; the newer callback can reconcile again.
                            val activeKind = if (bestNetwork == network && networks.isCurrent(network)) kind else currentNetworkKind()
                            switchStrategyForNetwork(activeKind)
                            startProxy()
                            startTun2Socks()
                            restarted = true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Network reconnect failed", e)
                            shouldRun = false
                            stopTun2Socks()
                            stopProxy()
                            updateStatus(ServiceStatus.Failed)
                            unregisterPhysicalNetworkMonitor()
                            stopSelf()
                        }
                    }
                    if (restarted && shouldRun) checkCloudflareFreeze()
                }
            }
        }
        try {
            val handler = Handler(Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Observe only the best physical network, not every standby network.
                connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
            } else {
                // Before API 31 this tracks one best non-VPN match. Release the
                // request as soon as the VPN is paused or stopped.
                connectivity.requestNetwork(request, callback, handler)
            }
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the physical network", e)
        }
    }

    private fun unregisterPhysicalNetworkMonitor() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop watching the physical network", e)
        }
    }

    override fun onDestroy() {
        shouldRun = false
        unregisterPhysicalNetworkMonitor()
        super.onDestroy()
        isAlive = false
        com.rupleide.netfix.core.tgproxy.TgProxyController.onOwnVpnStateChanged()
        tunFd?.close()
        releaseWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForeground()

        return when (val action = intent?.action) {
            START_ACTION -> {
                shouldRun = true
                lifecycleScope.launch {
                    start()
                }
                START_NOT_STICKY
            }

            STOP_ACTION -> {
                shouldRun = false
                unregisterPhysicalNetworkMonitor()
                getSharedPreferences(packageName + "_preferences", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("service_enabled", false)
                    .apply()
                lifecycleScope.launch {
                    stop()
                }
                START_NOT_STICKY
            }

            RESUME_ACTION -> {
                shouldRun = true
                lifecycleScope.launch {
                    if (prepare(this@ByeDpiVpnService) == null) {
                        start()
                    }
                }
                START_NOT_STICKY
            }

            PAUSE_ACTION -> {
                shouldRun = false
                unregisterPhysicalNetworkMonitor()
                lifecycleScope.launch {
                    pause()
                }
                START_NOT_STICKY
            }

            SERVICE_INTERFACE -> {
                Log.i(TAG, "Started by Android")
                shouldRun = true

                lifecycleScope.launch {
                    start()
                }

                START_NOT_STICKY
            }

            else -> {
                shouldRun = false
                unregisterPhysicalNetworkMonitor()
                Log.w(TAG, "Unknown action: $action")
                lifecycleScope.launch {
                    stop()
                }
                START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        shouldRun = false
        unregisterPhysicalNetworkMonitor()
        Log.i(TAG, "VPN revoked")
        val tgRunning = com.rupleide.netfix.core.tgproxy.TgProxyController.isRunning
        if (tgRunning) {
            com.rupleide.netfix.core.tgproxy.TgProxyService.startInForeground(this, openTelegram = false, proxyOnly = true)
        }
        com.rupleide.netfix.core.tgproxy.TgProxyReminder.notifyVpnReplaced(this, tgRunning)
        getSharedPreferences(packageName + "_preferences", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("service_enabled", false)
            .apply()
        lifecycleScope.launch { stop() }
    }

    private fun switchStrategyForNetwork(kind: NetworkKind?) {
        if (applyStrategyForNetwork(kind)) {
            Log.i(TAG, "Сеть: ${kind?.displayName}, применяю сохранённую для неё стратегию")
        }
    }

    private fun switchStrategyForCurrentNetwork() = switchStrategyForNetwork(currentNetworkKind())

    private suspend fun start() {
        if (!shouldRun) return
        Log.i(TAG, "Starting")
        registerPhysicalNetworkMonitor()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(PAUSE_NOTIFICATION_ID)
        com.rupleide.netfix.core.tgproxy.TgProxyReminder.clearVpnReplaced(this)

        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "VPN already connected")
            updateStatus(ServiceStatus.Connected)
            return
        }

        try {
            val prefs = getSharedPreferences(packageName + "_preferences", android.content.Context.MODE_PRIVATE)
            val economMode = prefs.getBoolean("econom_mode", false)
            if (!economMode) {
                acquireWakeLock()
                com.rupleide.netfix.service.WatchdogWorker.schedulePeriodicWork(this)
            }
            mutex.withLock {
                if (!shouldRun) return@withLock
                switchStrategyForCurrentNetwork()
                startProxy()
                startTun2Socks()
                updateStatus(ServiceStatus.Connected)
            }
            if (isRunning && shouldRun) checkCloudflareFreeze()
        } catch (e: Exception) {
            shouldRun = false
            unregisterPhysicalNetworkMonitor()
            Log.e(TAG, "Failed to start VPN", e)
            // Startup can fail after the proxy has started but before the VPN is
            // marked connected. Tear down those partial resources first.
            mutex.withLock {
                stopTun2Socks()
                stopProxy()
            }
            updateStatus(ServiceStatus.Failed)
            stop()
        }
    }

    private var cloudflareCheckJob: kotlinx.coroutines.Job? = null

    private fun checkCloudflareFreeze() {
        if (!CloudflareUnfreeze.isEnabled(this)) return
        cloudflareCheckJob?.cancel()
        cloudflareCheckJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(CLOUDFLARE_CHECK_DELAY_MS)
            while (status == ServiceStatus.Connected) {
                if (applyCloudflareDecision()) return@launch
                delay(CLOUDFLARE_PERIODIC_CHECK_MS)
            }
        }
    }

    private suspend fun applyCloudflareDecision(): Boolean {
        if (status != ServiceStatus.Connected || StrategyTestManager.isTesting || StrategyTestManager.isVerifying) return false
        val port = activeProxyPort ?: return false
        val service = this@ByeDpiVpnService
        when (CloudflareUnfreeze.nextAction(service)) {
            CloudflareUnfreezeAction.Keep -> Unit
            CloudflareUnfreezeAction.Recheck -> if (!CloudflareUnfreeze.stillWorks(service, port) && status == ServiceStatus.Connected) {
                CloudflareUnfreeze.forget(service)
                ServiceManager.restart(service, Mode.VPN)
                return true
            }
            CloudflareUnfreezeAction.Detect -> {
                val detection = CloudflareUnfreeze.detect(service, port)
                Log.i(TAG, "Cloudflare check: ${detection.result} ${detection.ip ?: ""}")
                if (detection.result == CloudflareUnfreezeResult.Found && status == ServiceStatus.Connected) {
                    ServiceManager.restart(service, Mode.VPN)
                    return true
                }
            }
        }
        return false
    }

    private fun startForeground() {
        val notification: Notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun pause() {
        Log.i(TAG, "Pausing")

        releaseWakeLock()
        com.rupleide.netfix.service.WatchdogWorker.cancelPeriodicWork(this)

        if (status == ServiceStatus.Connected) {
            mutex.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        stopProxy()
                        stopTun2Socks()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pause VPN", e)
                }
            }
        }

        updateStatus(ServiceStatus.Disconnected)

        val pausedNotification = createPauseNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.service_paused_text,
            ByeDpiVpnService::class.java,
            createStopAllPendingIntent(this)
        )
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(FOREGROUND_SERVICE_ID, pausedNotification)
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping")

        releaseWakeLock()
        com.rupleide.netfix.service.WatchdogWorker.cancelPeriodicWork(this)

        if (status != ServiceStatus.Connected) {
            Log.w(TAG, "VPN not connected")
            updateStatus(ServiceStatus.Disconnected)
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            return
        }

        mutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    stopProxy()
                    stopTun2Socks()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop VPN", e)
            }
        }

        updateStatus(ServiceStatus.Disconnected)
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun startProxy() {
        Log.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            Log.w(TAG, "Proxy fields not null")
            throw IllegalStateException("Proxy fields not null")
        }

        val preferences = getByeDpiPreferences()
        val (proxyIp, configuredPort) = getPreferences().getProxyIpAndPort()
        val requestedPort = configuredPort.toIntOrNull() ?: ProxyPortResolver.DEFAULT_PROXY_PORT
        val resolvedPort = if (CompatibilityManager.freeProxyPortEnabled(getPreferences())) {
            ProxyPortResolver.resolve(proxyIp, requestedPort)
        } else {
            requestedPort
        }
        if (resolvedPort != requestedPort) {
            Log.w(TAG, "Port $requestedPort is busy, using $resolvedPort")
        }
        activeProxyPort = resolvedPort
        val unfreezeArgs = CloudflareUnfreeze.extraArgs(this)
        if (unfreezeArgs.isNotEmpty()) Log.i(TAG, "Cloudflare: ${CloudflareUnfreeze.savedIp(this)}")
        val secureDnsArgs = SecureDnsRelay.extraArgs(this)
        val passthroughArgs = if (secureDnsArgs.isNotEmpty()) SecureDnsRelay.passthroughArgs(this) else emptyList()
        val strategyArgs = when (preferences) {
            is ByeDpiProxyCmdPreferences -> preferences.args
            is ByeDpiProxyUIPreferences -> preferences.uiargs
        }
        val fastFailArgs = VideoFastFail.extraArgs(this, strategyArgs)
        if (fastFailArgs.isNotEmpty()) Log.i(TAG, "Video fast fail: ${fastFailArgs.joinToString(" ")}")

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = byeDpiProxy.startProxyTakingOver(preferences, resolvedPort, unfreezeArgs + secureDnsArgs + fastFailArgs, passthroughArgs)

            delay(500)

            if (code != 0) {
                Log.e(TAG, "Proxy stopped with code $code")
                updateStatus(ServiceStatus.Failed)
                stopTun2Socks()
                stopSelf()
            }
        }

        Log.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy() {
        Log.i(TAG, "Stopping proxy")

        if (proxyJob == null) {
            SecureDnsRelay.stop()
            activeProxyPort = null
            Log.w(TAG, "Proxy already disconnected")
            return
        }

        try {
            byeDpiProxy.stopProxy()
            SecureDnsRelay.stop()
            proxyJob?.cancel()

            val completed = withTimeoutOrNull(2000) {
                proxyJob?.join()
                true
            }

            if (completed == null) {
                Log.w(TAG, "proxy not finish in time, cancelling...")
                byeDpiProxy.jniForceClose()
            }

            proxyJob = null
            activeProxyPort = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close proxyJob", e)
        }

        Log.i(TAG, "Proxy stopped")
    }

    private fun startTun2Socks() {
        Log.i(TAG, "Starting tun2socks")

        if (tunFd != null) {
            Log.w(TAG, "VPN field not null")
            throw IllegalStateException("VPN field not null")
        }

        val sharedPreferences = getPreferences()
        val (ip, configuredPort) = sharedPreferences.getProxyIpAndPort()
        val port = activeProxyPort?.toString() ?: configuredPort

        val dnsIps = resolveTunnelDnsServers()
        val ipv6 = sharedPreferences.getBoolean("ipv6_enable", false)

        val tun2socksConfig = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $VPN_MTU")

            appendLine("misc:")
            appendLine("  task-stack-size: 81920")

            appendLine("socks5:")
            appendLine("  address: $ip")
            appendLine("  port: $port")
            appendLine("  udp: udp")
        }

        val configPath = try {
            File.createTempFile("config", "tmp", cacheDir).apply {
                writeText(tun2socksConfig)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create config file", e)
            throw e
        }

        val isSmartTv = sharedPreferences.getBoolean("is_smart_tv", false)

        val fd = createBuilder(dnsIps, ipv6).establish()
            ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd

        TProxyService.TProxyStartService(configPath.absolutePath, fd.fd, isSmartTv)

        Log.i(TAG, "Tun2Socks started. ip: $ip port: $port")
    }

    private fun stopTun2Socks() {
        Log.i(TAG, "Stopping tun2socks")

        if (tunFd == null) {
            Log.w(TAG, "VPN field is null, skipping")
            return
        }

        try {
            TProxyService.TProxyStopService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop TProxyService", e)
        }

        try {
            File(cacheDir, "config.tmp").delete()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to delete config file", e)
        }

        try {
            tunFd?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close tunFd", e)
        } finally {
            tunFd = null
        }

        Log.i(TAG, "Tun2socks stopped")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences(), this)

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "VPN status changed from $status to $newStatus")

        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running

                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> {
                    proxyJob = null
                    AppStatus.Halted
                }
            },
            Mode.VPN
        )

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        )
        intent.putExtra(SENDER, Sender.VPN.ordinal)
        com.rupleide.netfix.service.NetFixTileService.requestRefresh(this)
        sendBroadcast(intent.setPackage(packageName))
    }

    private fun createNotification(): Notification = createNetFixNotification(this)

    private fun createNotificationPause() {
        val notification = createPauseNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.service_paused_text,
            ByeDpiVpnService::class.java
        )

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(PAUSE_NOTIFICATION_ID, notification)
    }

    private fun resolveTunnelDnsServers(): List<String> {
        val isPrivateDnsConfigured = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val activeNetwork = connectivityManager.activeNetwork
                val linkProperties = activeNetwork?.let { connectivityManager.getLinkProperties(it) }
                !linkProperties?.privateDnsServerName.isNullOrBlank()
            } catch (e: SecurityException) {
                false
            }
        } else {
            false
        }
        val servers = CompatibilityManager.tunnelDnsServers(getPreferences(), isPrivateDnsConfigured)
        if (!isPrivateDnsConfigured && SecureDnsRelay.isRunning) {
            return listOf(SecureDnsRelay.VIRTUAL_DNS)
        }
        return servers
    }

    private fun createBuilder(dnsIps: List<String>, ipv6: Boolean): Builder {
        Log.d(TAG, "DNS: $dnsIps")
        val builder = Builder()
        val preferences = getPreferences()
        builder.setSession("NetFix")
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )

        builder.setMtu(VPN_MTU)
            .addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)

        if (ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }

        dnsIps.forEach { ip ->
            builder.addDnsServer(ip)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        when (preferences.appListMode()) {
            AppListMode.SelectedAppsOnly -> {
                var addedApps = 0
                for (packageName in CompatibilityManager.vpnAppsFor(preferences, preferences.selectedAppsWithDefaults())) {
                    // The proxy's own sockets must stay outside the VPN to avoid a
                    // routing loop, even if NetFix was accidentally selected.
                    if (packageName == applicationContext.packageName) continue
                    try {
                        builder.addAllowedApplication(packageName)
                        addedApps++
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.d(TAG, "Selected app is not installed: $packageName")
                    }
                }
                // No allowed apps means Android routes ALL apps, including NetFix.
                if (addedApps == 0) throw IllegalStateException("No installed apps selected for the VPN")
            }
            AppListMode.AllTraffic -> {
                builder.addDisallowedApplication(applicationContext.packageName)
            }
        }

        return builder
    }
}
