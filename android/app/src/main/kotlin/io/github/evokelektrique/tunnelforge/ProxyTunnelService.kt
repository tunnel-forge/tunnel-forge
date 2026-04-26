package io.github.evokelektrique.tunnelforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service for proxy-only mode.
 *
 * This reuses the real native negotiation path without VpnService socket protection. The userspace
 * TCP/IP proxy dataplane is still pending, so this service keeps only the native packet bridge
 * alive for now.
 */
class ProxyTunnelService : Service() {
    private val sessionLock = Any()
    private var worker: Thread? = null
    private var packetBridge: ProxyPacketBridge? = null
    private var proxyRuntime: ProxyServerRuntime? = null
    private var proxyTransport: BridgeProxyTransport? = null
    private var userspaceStack: BridgeUserspaceTunnelStack? = null
    private var activeProxyConfig: ProxyRuntimeConfig? = null
    private var activeAttemptId: String = ""
    @Volatile
    private var connectedEmitted = false
    @Volatile
    private var stopRequested = false
    private val terminalEventEmitted = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdownActiveSession(reason = "service destroy")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                VpnTunnelEvents.emitEngineLog(Log.INFO, TAG, "ACTION_STOP: tearing down proxy service")
                val requestedAttemptId = intent.getStringExtra(EXTRA_ATTEMPT_ID) ?: ""
                val stoppedAttemptId = currentAttemptId()
                if (VpnStopAttemptPolicy.shouldIgnoreStopRequest(requestedAttemptId, stoppedAttemptId)) {
                    VpnTunnelEvents.emitEngineLog(
                        Log.DEBUG,
                        TAG,
                        "${prefixAttempt(requestedAttemptId)}Ignoring stale proxy stop activeAttempt=$stoppedAttemptId",
                    )
                    return START_NOT_STICKY
                }
                val hadActiveSession = shutdownActiveSession(reason = "app stop")
                if (ProxyTunnelServiceStopPolicy.shouldEmitStoppedOnActionStop(hadActiveSession)) {
                    emitTerminalState(VpnContract.TUNNEL_STOPPED, "Stopped by app", stoppedAttemptId)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForegroundWithType(buildNotification("Starting local proxy..."))
                val attemptId = intent.getStringExtra(EXTRA_ATTEMPT_ID) ?: ""
                val server = intent.getStringExtra(EXTRA_SERVER)
                val user = intent.getStringExtra(EXTRA_USER) ?: ""
                val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
                val psk = intent.getStringExtra(EXTRA_PSK) ?: ""
                val dnsAutomatic = intent.getBooleanExtra(EXTRA_DNS_AUTOMATIC, true)
                val dnsServers = TunnelVpnService.manualDnsServersFromIntent(intent)
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME)?.trim().orEmpty()
                val httpPort = sanitizePort(intent.getIntExtra(EXTRA_PROXY_HTTP_PORT, DEFAULT_HTTP_PORT), DEFAULT_HTTP_PORT)
                val socksPort = sanitizePort(intent.getIntExtra(EXTRA_PROXY_SOCKS_PORT, DEFAULT_SOCKS_PORT), DEFAULT_SOCKS_PORT)
                val allowLanConnections = intent.getBooleanExtra(EXTRA_PROXY_ALLOW_LAN, false)
                val proxyMtu = sanitizeMtu(intent.getIntExtra(EXTRA_MTU, DEFAULT_LINK_MTU))
                VpnTunnelEvents.emitEngineLog(
                    Log.DEBUG,
                    TAG,
                    "${prefixAttempt(attemptId)}ACTION_START accepted server=${server?.trim().orEmpty()} userPresent=${user.isNotEmpty()} pskPresent=${psk.isNotEmpty()} dnsMode=${if (dnsAutomatic) "automatic" else "manual"} dns=${dnsServers.joinToString(",") { "${it.host}[${it.protocol.shortLabel}]" }} mtu=$proxyMtu http=$httpPort socks=$socksPort lan=${if (allowLanConnections) "on" else "off"}",
                )
                if (server.isNullOrBlank()) {
                    VpnTunnelEvents.emit(VpnContract.TUNNEL_FAILED, "Invalid proxy arguments from the app.", attemptId)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (httpPort == socksPort) {
                    VpnTunnelEvents.emit(VpnContract.TUNNEL_FAILED, "HTTP and SOCKS5 ports must differ.", attemptId)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val proxyConfig =
                    ProxyRuntimeConfig(
                        httpEnabled = true,
                        httpPort = httpPort,
                        socksEnabled = true,
                        socksPort = socksPort,
                        allowLanConnections = allowLanConnections,
                    )
                shutdownActiveSession(reason = "restart")
                connectedEmitted = false
                stopRequested = false
                terminalEventEmitted.set(false)
                activeProxyConfig = proxyConfig
                val startupThread = Thread(
                    {
                        runProxyNegotiation(
                            attemptId = attemptId,
                            server = server.trim(),
                            user = user,
                            password = password,
                            psk = psk,
                            dnsAutomatic = dnsAutomatic,
                            dnsServers = dnsServers,
                            profileName = profileName,
                            proxyConfig = proxyConfig,
                            proxyMtu = proxyMtu,
                        )
                    },
                    "proxy-negotiation",
                )
                synchronized(sessionLock) {
                    activeAttemptId = attemptId
                    worker = startupThread
                }
                startupThread.start()
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    private fun runProxyNegotiation(
        attemptId: String,
        server: String,
        user: String,
        password: String,
        psk: String,
        dnsAutomatic: Boolean,
        dnsServers: List<DnsServerConfig>,
        profileName: String,
        proxyConfig: ProxyRuntimeConfig,
        proxyMtu: Int,
    ) {
        val label = profileName.ifEmpty { server }
        val currentWorker = Thread.currentThread()
        var localStack: BridgeUserspaceTunnelStack? = null
        var localRuntime: ProxyServerRuntime? = null
        emitAttemptState(
            attemptId,
            VpnContract.TUNNEL_CONNECTING,
            "Negotiating tunnel for local proxy (${label.ifEmpty { "profile" }})...",
        )
        VpnTunnelEvents.emitEngineLog(
            Log.INFO,
            TAG,
            "${prefixAttempt(attemptId)}Starting proxy negotiation",
        )
        try {
            VpnBridge.nativeSetSocketProtectionEnabled(false)
            val negotiatedClientIp = IntArray(4)
            val negotiatedPrimaryDns = IntArray(4)
            val negotiatedSecondaryDns = IntArray(4)
            val negResult =
                VpnBridge.nativeNegotiate(
                    server,
                    user,
                    password,
                    psk,
                    proxyMtu,
                    negotiatedClientIp,
                    negotiatedPrimaryDns,
                    negotiatedSecondaryDns,
                )
            VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}nativeNegotiate finished with exit code=$negResult")
            if (negResult == TunnelVpnService.DEFAULT_NATIVE_EXIT_STOPPED && stopRequested) {
                VpnTunnelEvents.emitEngineLog(
                    Log.INFO,
                    TAG,
                    "${prefixAttempt(attemptId)}Proxy negotiation canceled before dataplane startup",
                )
                return
            }
            if (negResult != 0) {
                emitAttemptTerminalState(attemptId, VpnContract.TUNNEL_FAILED, tunnelExitDetail(negResult))
                return
            }
            val effectiveDnsServers =
                if (dnsAutomatic) {
                    DnsConfigSupport.negotiatedServers(negotiatedPrimaryDns, negotiatedSecondaryDns)
                } else {
                    DnsConfigSupport.resolveUpstreamServers(dnsServers)
                }
            if (effectiveDnsServers.isEmpty()) {
                emitAttemptTerminalState(
                    attemptId,
                    VpnContract.TUNNEL_FAILED,
                    if (dnsAutomatic) {
                        "PPP negotiation did not provide any DNS servers."
                    } else {
                        "Manual DNS requires at least one DNS server."
                    },
                )
                return
            }
            VpnTunnelEvents.emitEngineLog(
                Log.DEBUG,
                TAG,
                "${prefixAttempt(attemptId)}Negotiated tunnel clientIpv4=${negotiatedClientIp.joinToString(".")}; awaiting proxy dataplane",
            )
            emitAttemptState(
                attemptId,
                VpnContract.TUNNEL_CONNECTING,
                "Tunnel negotiated. Starting native local-proxy bridge...",
            )
            val loopThread = Thread(
                {
                    ProxyTunnelLoopRunner.run(
                        attemptId = attemptId,
                        emitEngineLog = { level, message ->
                            VpnTunnelEvents.emitEngineLog(level, TAG, message)
                        },
                        startLoop = { VpnBridge.nativeStartProxyLoop() },
                        detailForCode = ::tunnelExitDetail,
                        isStopRequested = { stopRequested },
                        isConnected = { connectedEmitted },
                        emitTerminal = { terminalAttemptId, state, detail ->
                            emitAttemptTerminalState(terminalAttemptId, state, detail)
                        },
                        onLoopCrash = { throwable ->
                            AppLog.e(TAG, "${prefixAttempt(attemptId)}nativeStartProxyLoop failed", throwable)
                        },
                    )
                },
                "proxy-loop",
            )
            loopThread.start()
            val bridge = ProxyPacketBridge()
            val stack =
                BridgeUserspaceTunnelStack(
                    bridge = bridge,
                    clientIpv4 = negotiatedClientIp.joinToString("."),
                    dnsServers = effectiveDnsServers,
                    linkMtu = proxyMtu,
                    connectTimeoutMs = PROXY_ONLY_CONNECT_TIMEOUT_MS,
                    synRetransmitDelaysMs = PROXY_ONLY_SYN_RETRANSMIT_DELAYS_MS,
                    maxTcpSessions = PROXY_ONLY_MAX_TCP_SESSIONS,
                    maxPendingTcpConnects = PROXY_ONLY_MAX_PENDING_TCP_CONNECTS,
                    synPacingIntervalMs = PROXY_ONLY_SYN_PACING_INTERVAL_MS,
                    tcpFinDrainTimeoutMs = PROXY_ONLY_TCP_FIN_DRAIN_TIMEOUT_MS,
                    logger = { level, message ->
                        VpnTunnelEvents.emitEngineLog(level, TAG, "${prefixAttempt(attemptId)}$message")
                    },
                )
            VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}waiting for proxy packet bridge readiness")
            localStack = stack
            if (!stack.waitUntilReady()) {
                if (!ProxyTunnelServiceStopPolicy.shouldFailBridgeReadiness(stopRequested)) {
                    VpnTunnelEvents.emitEngineLog(
                        Log.DEBUG,
                        TAG,
                        "${prefixAttempt(attemptId)}proxy bridge readiness wait ended after stop request",
                    )
                } else {
                    emitAttemptTerminalState(attemptId, VpnContract.TUNNEL_FAILED, "Local proxy bridge did not become ready")
                }
                try {
                    VpnBridge.nativeStopTunnel()
                } catch (_: Exception) {
                }
                loopThread.join(5000)
                return
            }
            VpnTunnelEvents.emitEngineLog(Log.INFO, TAG, "${prefixAttempt(attemptId)}Proxy packet bridge ready")
            val transport = BridgeProxyTransport(stack)
            val exposure =
                LanProxyAddressResolver(this).resolve(
                    httpPort = proxyConfig.httpPort,
                    socksPort = proxyConfig.socksPort,
                    lanRequested = proxyConfig.allowLanConnections,
                )
            val runtime = ProxyServerRuntime(
                config =
                    proxyConfig.copy(
                        exposure = exposure,
                        maxConcurrentClients = PROXY_ONLY_MAX_CONCURRENT_CLIENTS,
                    ),
                levelLogger = { level, message ->
                    VpnTunnelEvents.emitEngineLog(level, TAG, "${prefixAttempt(attemptId)}$message")
                },
                transport = transport,
                connectTimeoutMs = PROXY_ONLY_CONNECT_TIMEOUT_MS,
                connectResponseTimeoutMs = PROXY_ONLY_CONNECT_RESPONSE_TIMEOUT_MS,
            )
            localRuntime = runtime
            var workerReplaced = false
            synchronized(sessionLock) {
                if (worker !== currentWorker) {
                    workerReplaced = true
                } else {
                    packetBridge = bridge
                    userspaceStack = stack
                    proxyTransport = transport
                    proxyRuntime = runtime
                }
            }
            if (workerReplaced) {
                if (ProxyTunnelServiceStopPolicy.shouldSuppressStartupFailure(
                        stopRequested = stopRequested,
                        throwable = IllegalStateException(ProxyTunnelServiceStopPolicy.WORKER_REPLACED_MESSAGE),
                    )
                ) {
                    VpnTunnelEvents.emitEngineLog(
                        Log.DEBUG,
                        TAG,
                        "${prefixAttempt(attemptId)}proxy listeners were discarded after stop request",
                    )
                    return
                }
                throw IllegalStateException(ProxyTunnelServiceStopPolicy.WORKER_REPLACED_MESSAGE)
            }
            runtime.start()
            VpnTunnelEvents.emitProxyExposureChanged(exposure)
            exposure.warning?.let { warning ->
                VpnTunnelEvents.emitEngineLog(Log.WARN, TAG, "${prefixAttempt(attemptId)}$warning")
            }
            VpnTunnelEvents.emitEngineLog(
                Log.INFO,
                TAG,
                "${prefixAttempt(attemptId)}Proxy ready ${runtime.endpointSummary()}",
            )
            connectedEmitted = true
            updateForegroundNotification(runtime.endpointSummary())
            emitAttemptState(
                attemptId,
                VpnContract.TUNNEL_CONNECTED,
                "Tunnel bridge active. ${runtime.endpointSummary()}. Hostnames resolve through tunneled DNS; IPv6 destinations are still unsupported.",
            )
            loopThread.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (ProxyTunnelServiceStopPolicy.shouldSuppressStartupFailure(stopRequested, e)) {
                VpnTunnelEvents.emitEngineLog(
                    Log.DEBUG,
                    TAG,
                    "${prefixAttempt(attemptId)}proxy worker interrupted during shutdown",
                )
            } else {
                AppLog.e(TAG, "${prefixAttempt(attemptId)}runProxyNegotiation failed", e)
                emitAttemptTerminalState(attemptId, VpnContract.TUNNEL_FAILED, e.message ?: "proxy startup interrupted")
            }
        } catch (t: Throwable) {
            if (ProxyTunnelServiceStopPolicy.shouldSuppressStartupFailure(stopRequested, t)) {
                VpnTunnelEvents.emitEngineLog(
                    Log.DEBUG,
                    TAG,
                    "${prefixAttempt(attemptId)}proxy startup ended during shutdown: ${t.javaClass.simpleName}",
                )
            } else {
                AppLog.e(TAG, "${prefixAttempt(attemptId)}runProxyNegotiation failed", t)
                emitAttemptTerminalState(attemptId, VpnContract.TUNNEL_FAILED, t.message ?: "proxy startup failed")
            }
        } finally {
            localStack?.stop()
            localRuntime?.stop()
            VpnTunnelEvents.emitProxyExposureChanged(
                ProxyExposureInfo.inactive(
                    httpPort = proxyConfig.httpPort,
                    socksPort = proxyConfig.socksPort,
                    lanRequested = proxyConfig.allowLanConnections,
                ),
            )
            VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}proxy worker finalizing")
            var shouldStopService = false
            synchronized(sessionLock) {
                if (worker === currentWorker) {
                    worker = null
                    userspaceStack = null
                    proxyRuntime = null
                    proxyTransport = null
                    packetBridge = null
                    activeProxyConfig = null
                    if (activeAttemptId == attemptId) {
                        activeAttemptId = ""
                    }
                    shouldStopService = true
                }
            }
            if (shouldStopService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun shutdownActiveSession(reason: String): Boolean {
        val active =
            synchronized(sessionLock) {
                stopRequested = true
                ActiveProxySession(
                    worker = worker,
                    stack = userspaceStack,
                    runtime = proxyRuntime,
                    hasTransport = proxyTransport != null,
                    hasBridge = packetBridge != null,
                ).also {
                    worker = null
                    userspaceStack = null
                    proxyRuntime = null
                    proxyTransport = null
                    packetBridge = null
                    activeAttemptId = ""
                }
            }
        val config = activeProxyConfig
        activeProxyConfig = null
        if (!active.hasState()) {
            if (config != null) {
                VpnTunnelEvents.emitProxyExposureChanged(
                    ProxyExposureInfo.inactive(
                        httpPort = config.httpPort,
                        socksPort = config.socksPort,
                        lanRequested = config.allowLanConnections,
                    ),
                )
            }
            return false
        }
        VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "Stopping previous proxy session reason=$reason")
        active.stack?.stop()
        active.runtime?.stop()
        if (config != null) {
            VpnTunnelEvents.emitProxyExposureChanged(
                ProxyExposureInfo.inactive(
                    httpPort = config.httpPort,
                    socksPort = config.socksPort,
                    lanRequested = config.allowLanConnections,
                ),
            )
        }
        ProxyTunnelServiceStopper.stopPreviousWorker(
            worker = active.worker,
            onStopNativeTunnel = {
                try {
                    VpnBridge.nativeStopTunnel()
                } catch (_: Exception) {
                }
            },
            logger = { level, message ->
                VpnTunnelEvents.emitEngineLog(level, TAG, message)
            },
            joinTimeoutMs = WORKER_JOIN_TIMEOUT_MS,
        )
        return true
    }

    private fun currentAttemptId(): String =
        synchronized(sessionLock) { activeAttemptId }

    private fun shouldHandleAttempt(attemptId: String, stage: String): Boolean {
        val currentAttemptId = currentAttemptId()
        val matches = attemptId.isEmpty() || attemptId == currentAttemptId
        if (!matches) {
            VpnTunnelEvents.emitEngineLog(
                Log.DEBUG,
                TAG,
                "${prefixAttempt(attemptId)}Ignoring stale proxy attempt stage=$stage activeAttempt=$currentAttemptId",
            )
        }
        return matches
    }

    private fun emitAttemptState(attemptId: String, state: String, detail: String): Boolean {
        if (!shouldHandleAttempt(attemptId, "state:$state")) return false
        VpnTunnelEvents.emit(state, detail, attemptId)
        return true
    }

    private fun emitAttemptTerminalState(attemptId: String, state: String, detail: String): Boolean {
        if (!shouldHandleAttempt(attemptId, "state:$state")) return false
        emitTerminalState(state, detail, attemptId)
        return true
    }

    private fun emitTerminalState(state: String, detail: String, attemptId: String) {
        if (!terminalEventEmitted.compareAndSet(false, true)) return
        VpnTunnelEvents.emit(state, detail, attemptId)
    }

    private fun buildNotification(text: String): Notification {
        createChannelIfNeeded()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tunnel Forge proxy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(text: String) {
        startForegroundWithType(buildNotification(text))
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Tunnel Forge proxy",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun tunnelExitDetail(code: Int): String =
        when (code) {
            1 -> "IPsec negotiation failed. Check the PSK and server settings."
            2 -> "L2TP handshake failed."
            3 -> "PPP negotiation failed."
            4 -> "Tunnel poll I/O error."
            10 -> "Invalid proxy arguments from the app."
            11 -> "Proxy transport is not implemented yet."
            else -> "Tunnel engine exited with code $code"
        }

    companion object {
        private const val TAG = "ProxyTunnelService"
        const val ACTION_START = "io.github.evokelektrique.tunnelforge.action.PROXY_START"
        const val ACTION_STOP = "io.github.evokelektrique.tunnelforge.action.PROXY_STOP"
        const val EXTRA_ATTEMPT_ID = "attemptId"
        const val EXTRA_SERVER = "server"
        const val EXTRA_USER = "user"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_PSK = "psk"
        const val EXTRA_DNS_AUTOMATIC = "dnsAutomatic"
        const val EXTRA_DNS_SERVER_1_HOST = TunnelVpnService.EXTRA_DNS_SERVER_1_HOST
        const val EXTRA_DNS_SERVER_1_PROTOCOL = TunnelVpnService.EXTRA_DNS_SERVER_1_PROTOCOL
        const val EXTRA_DNS_SERVER_2_HOST = TunnelVpnService.EXTRA_DNS_SERVER_2_HOST
        const val EXTRA_DNS_SERVER_2_PROTOCOL = TunnelVpnService.EXTRA_DNS_SERVER_2_PROTOCOL
        const val EXTRA_MTU = "mtu"
        const val EXTRA_PROFILE_NAME = "profileName"
        const val EXTRA_PROXY_HTTP_PORT = "proxyHttpPort"
        const val EXTRA_PROXY_SOCKS_PORT = "proxySocksPort"
        const val EXTRA_PROXY_ALLOW_LAN = "proxyAllowLan"
        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_LINK_MTU = TunnelVpnService.DEFAULT_TUN_MTU
        internal const val WORKER_JOIN_TIMEOUT_MS = 5_000L
        internal val PROXY_ONLY_MAX_CONCURRENT_CLIENTS: Int? = null
        internal val PROXY_ONLY_MAX_TCP_SESSIONS: Int? = null
        internal val PROXY_ONLY_MAX_PENDING_TCP_CONNECTS: Int? = null
        internal const val PROXY_ONLY_CONNECT_TIMEOUT_MS = 60_000L
        internal const val PROXY_ONLY_CONNECT_RESPONSE_TIMEOUT_MS = 25_000L
        internal val PROXY_ONLY_SYN_RETRANSMIT_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L)
        internal const val PROXY_ONLY_SYN_PACING_INTERVAL_MS = 20L
        internal const val PROXY_ONLY_TCP_FIN_DRAIN_TIMEOUT_MS = 5_000L

        private const val CHANNEL_ID = "tunnel_forge_proxy"
        private const val NOTIFICATION_ID = 7111

        internal fun sanitizePort(value: Int, fallback: Int): Int = if (value in 1..65535) value else fallback
        private fun sanitizeMtu(value: Int): Int = TunnelVpnService.sanitizeMtu(value)

        private fun prefixAttempt(attemptId: String): String = if (attemptId.isEmpty()) "" else "attempt=$attemptId "
    }
}

private data class ActiveProxySession(
    val worker: Thread?,
    val stack: BridgeUserspaceTunnelStack?,
    val runtime: ProxyServerRuntime?,
    val hasTransport: Boolean,
    val hasBridge: Boolean,
) {
    fun hasState(): Boolean = worker != null || stack != null || runtime != null || hasTransport || hasBridge
}

internal object ProxyTunnelServiceStopper {
    fun stopPreviousWorker(
        worker: Thread?,
        onStopNativeTunnel: () -> Unit,
        logger: (Int, String) -> Unit,
        joinTimeoutMs: Long,
    ) {
        onStopNativeTunnel()
        if (worker == null) return
        worker.interrupt()
        try {
            worker.join(joinTimeoutMs)
            if (worker.isAlive) {
                logger(Log.WARN, "Previous proxy worker did not exit within ${joinTimeoutMs}ms")
            } else {
                logger(Log.DEBUG, "Previous proxy worker joined")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            logger(Log.WARN, "Interrupted while waiting for previous proxy worker to stop")
        }
    }
}

internal object ProxyTunnelServiceStopPolicy {
    const val WORKER_REPLACED_MESSAGE = "Proxy worker was replaced before listeners became ready."

    fun shouldEmitStoppedOnActionStop(hadActiveSession: Boolean): Boolean = hadActiveSession

    fun shouldFailBridgeReadiness(stopRequested: Boolean): Boolean = !stopRequested

    fun shouldSuppressStartupFailure(stopRequested: Boolean, throwable: Throwable): Boolean {
        if (!stopRequested) return false
        return throwable is InterruptedException ||
            (throwable is IllegalStateException && throwable.message == WORKER_REPLACED_MESSAGE)
    }
}

internal object ProxyTunnelLoopRunner {
    fun run(
        attemptId: String,
        emitEngineLog: (Int, String) -> Unit,
        startLoop: () -> Int,
        detailForCode: (Int) -> String,
        isStopRequested: () -> Boolean,
        isConnected: () -> Boolean,
        emitTerminal: (String, String, String) -> Unit,
        onLoopCrash: (Throwable) -> Unit,
    ) {
        try {
            emitEngineLog(Log.DEBUG, "${prefixAttempt(attemptId)}nativeStartProxyLoop thread running")
            val code = startLoop()
            emitEngineLog(Log.DEBUG, "${prefixAttempt(attemptId)}nativeStartProxyLoop exited with code=$code")
            if (code != 0) {
                emitTerminal(attemptId, VpnContract.TUNNEL_FAILED, detailForCode(code))
            } else if (isStopRequested()) {
                // ACTION_STOP already emitted the terminal state for user-initiated disconnects.
            } else if (isConnected()) {
                emitTerminal(attemptId, VpnContract.TUNNEL_FAILED, "Local proxy connection was lost.")
            } else {
                emitTerminal(attemptId, VpnContract.TUNNEL_FAILED, "Local proxy bridge stopped before becoming ready")
            }
        } catch (t: Throwable) {
            onLoopCrash(t)
            emitTerminal(attemptId, VpnContract.TUNNEL_FAILED, t.message ?: "nativeStartProxyLoop crashed")
        }
    }

    private fun prefixAttempt(attemptId: String): String = if (attemptId.isEmpty()) "" else "attempt=$attemptId "
}
