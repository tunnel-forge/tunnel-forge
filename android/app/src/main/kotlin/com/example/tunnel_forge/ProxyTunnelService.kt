package com.example.tunnel_forge

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
                shutdownActiveSession(reason = "app stop")
                emitTerminalState(VpnContract.TUNNEL_STOPPED, "Stopped by app")
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
                val dns = intent.getStringExtra(EXTRA_DNS) ?: DEFAULT_DNS
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME)?.trim().orEmpty()
                val httpEnabled = intent.getBooleanExtra(EXTRA_PROXY_HTTP_ENABLED, true)
                val socksEnabled = intent.getBooleanExtra(EXTRA_PROXY_SOCKS_ENABLED, true)
                val httpPort = sanitizePort(intent.getIntExtra(EXTRA_PROXY_HTTP_PORT, DEFAULT_HTTP_PORT), DEFAULT_HTTP_PORT)
                val socksPort = sanitizePort(intent.getIntExtra(EXTRA_PROXY_SOCKS_PORT, DEFAULT_SOCKS_PORT), DEFAULT_SOCKS_PORT)
                val proxyMtu = sanitizeMtu(intent.getIntExtra(EXTRA_MTU, DEFAULT_LINK_MTU))
                VpnTunnelEvents.emitEngineLog(
                    Log.INFO,
                    TAG,
                    "${prefixAttempt(attemptId)}ACTION_START accepted server=${server?.trim().orEmpty()} userPresent=${user.isNotEmpty()} pskPresent=${psk.isNotEmpty()} dns=$dns mtu=$proxyMtu http=${if (httpEnabled) httpPort else "off"} socks=${if (socksEnabled) socksPort else "off"}",
                )
                if (server.isNullOrBlank()) {
                    VpnTunnelEvents.emit(VpnContract.TUNNEL_FAILED, "Invalid proxy arguments from the app.")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!httpEnabled && !socksEnabled) {
                    VpnTunnelEvents.emit(VpnContract.TUNNEL_FAILED, "Enable HTTP or SOCKS5 before starting local proxy.")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                shutdownActiveSession(reason = "restart")
                connectedEmitted = false
                stopRequested = false
                terminalEventEmitted.set(false)
                worker = Thread(
                    {
                        runProxyNegotiation(
                            attemptId = attemptId,
                            server = server.trim(),
                            user = user,
                            password = password,
                            psk = psk,
                            dns = dns,
                            profileName = profileName,
                            httpEnabled = httpEnabled,
                            httpPort = httpPort,
                            socksEnabled = socksEnabled,
                            socksPort = socksPort,
                            proxyMtu = proxyMtu,
                        )
                    },
                    "proxy-negotiation",
                ).also { it.start() }
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
        dns: String,
        profileName: String,
        httpEnabled: Boolean,
        httpPort: Int,
        socksEnabled: Boolean,
        socksPort: Int,
        proxyMtu: Int,
    ) {
        val label = profileName.ifEmpty { server }
        val currentWorker = Thread.currentThread()
        var localStack: BridgeUserspaceTunnelStack? = null
        var localRuntime: ProxyServerRuntime? = null
        VpnTunnelEvents.emit(
            VpnContract.TUNNEL_CONNECTING,
            "Negotiating tunnel for local proxy (${label.ifEmpty { "profile" }})...",
        )
        VpnTunnelEvents.emitEngineLog(
            Log.INFO,
            TAG,
            "${prefixAttempt(attemptId)}Starting proxy negotiation server=$server dns=$dns mtu=$proxyMtu http=${if (httpEnabled) httpPort else "off"} socks=${if (socksEnabled) socksPort else "off"}",
        )
        try {
            VpnBridge.nativeSetSocketProtectionEnabled(false)
            val negotiatedClientIp = IntArray(4)
            val negResult = VpnBridge.nativeNegotiate(server, user, password, psk, negotiatedClientIp)
            VpnTunnelEvents.emitEngineLog(Log.INFO, TAG, "${prefixAttempt(attemptId)}nativeNegotiate finished with exit code=$negResult")
            if (negResult != 0) {
                emitTerminalState(VpnContract.TUNNEL_FAILED, tunnelExitDetail(negResult))
                return
            }
            VpnTunnelEvents.emitEngineLog(
                Log.INFO,
                TAG,
                "${prefixAttempt(attemptId)}Negotiated tunnel clientIpv4=${negotiatedClientIp.joinToString(".")}; awaiting proxy dataplane",
            )
            VpnTunnelEvents.emit(
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
                        emitTerminal = ::emitTerminalState,
                        onLoopCrash = { throwable ->
                            Log.e(TAG, "${prefixAttempt(attemptId)}nativeStartProxyLoop failed", throwable)
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
                    dnsServer = dns,
                    linkMtu = proxyMtu,
                    logger = { level, message ->
                        VpnTunnelEvents.emitEngineLog(level, TAG, "${prefixAttempt(attemptId)}$message")
                    },
                )
            VpnTunnelEvents.emitEngineLog(Log.INFO, TAG, "${prefixAttempt(attemptId)}waiting for proxy packet bridge readiness")
            localStack = stack
            if (!stack.waitUntilReady()) {
                emitTerminalState(VpnContract.TUNNEL_FAILED, "Local proxy bridge did not become ready")
                try {
                    VpnBridge.nativeStopTunnel()
                } catch (_: Exception) {
                }
                loopThread.join(5000)
                return
            }
            VpnTunnelEvents.emitEngineLog(Log.INFO, TAG, "${prefixAttempt(attemptId)}proxy packet bridge ready; starting listeners")
            val transport = BridgeProxyTransport(stack)
            val runtime = ProxyServerRuntime(
                config = ProxyRuntimeConfig(
                    httpEnabled = httpEnabled,
                    httpPort = httpPort,
                    socksEnabled = socksEnabled,
                    socksPort = socksPort,
                ),
                logger = { message ->
                    VpnTunnelEvents.emitEngineLog(Log.INFO, TAG, "${prefixAttempt(attemptId)}$message")
                },
                transport = transport,
            )
            localRuntime = runtime
            synchronized(sessionLock) {
                if (worker !== currentWorker) {
                    throw IllegalStateException("Proxy worker was replaced before listeners became ready.")
                }
                packetBridge = bridge
                userspaceStack = stack
                proxyTransport = transport
                proxyRuntime = runtime
            }
            runtime.start()
            VpnTunnelEvents.emitEngineLog(
                Log.INFO,
                TAG,
                "${prefixAttempt(attemptId)}proxy ready endpoints=${runtime.endpointSummary()} clientIpv4=${negotiatedClientIp.joinToString(".")} dns=$dns mode=ipv4-hostname-via-dns",
            )
            connectedEmitted = true
            updateForegroundNotification(runtime.endpointSummary())
            VpnTunnelEvents.emit(
                VpnContract.TUNNEL_CONNECTED,
                "Tunnel bridge active. ${runtime.endpointSummary()}. Hostnames resolve through tunneled DNS; IPv6 destinations are still unsupported.",
            )
            loopThread.join()
        } catch (t: Throwable) {
            Log.e(TAG, "${prefixAttempt(attemptId)}runProxyNegotiation failed", t)
            emitTerminalState(VpnContract.TUNNEL_FAILED, t.message ?: "proxy startup failed")
        } finally {
            localStack?.stop()
            localRuntime?.stop()
            VpnTunnelEvents.emitEngineLog(Log.INFO, TAG, "${prefixAttempt(attemptId)}proxy worker finalizing")
            var shouldStopService = false
            synchronized(sessionLock) {
                if (worker === currentWorker) {
                    worker = null
                    userspaceStack = null
                    proxyRuntime = null
                    proxyTransport = null
                    packetBridge = null
                    shouldStopService = true
                }
            }
            if (shouldStopService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun shutdownActiveSession(reason: String) {
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
                }
            }
        if (!active.hasState()) return
        VpnTunnelEvents.emitEngineLog(Log.INFO, TAG, "Stopping previous proxy session reason=$reason")
        active.stack?.stop()
        active.runtime?.stop()
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
    }

    private fun emitTerminalState(state: String, detail: String) {
        if (!terminalEventEmitted.compareAndSet(false, true)) return
        VpnTunnelEvents.emit(state, detail)
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
            1 -> "IPsec did not finish (Quick Mode / ESP not ready)."
            2 -> "L2TP handshake failed."
            3 -> "PPP negotiation failed."
            4 -> "Tunnel poll I/O error."
            10 -> "Invalid proxy arguments from the app."
            11 -> "Proxy transport is not implemented yet."
            else -> "Tunnel engine exited with code $code"
        }

    companion object {
        private const val TAG = "ProxyTunnelService"
        const val ACTION_START = "com.example.tunnel_forge.action.PROXY_START"
        const val ACTION_STOP = "com.example.tunnel_forge.action.PROXY_STOP"
        const val EXTRA_ATTEMPT_ID = "attemptId"
        const val EXTRA_SERVER = "server"
        const val EXTRA_USER = "user"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_PSK = "psk"
        const val EXTRA_DNS = "dns"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_PROFILE_NAME = "profileName"
        const val EXTRA_PROXY_HTTP_ENABLED = "proxyHttpEnabled"
        const val EXTRA_PROXY_HTTP_PORT = "proxyHttpPort"
        const val EXTRA_PROXY_SOCKS_ENABLED = "proxySocksEnabled"
        const val EXTRA_PROXY_SOCKS_PORT = "proxySocksPort"
        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_LINK_MTU = TunnelVpnService.DEFAULT_TUN_MTU
        private const val DEFAULT_DNS = "8.8.8.8"
        internal const val WORKER_JOIN_TIMEOUT_MS = 5_000L

        private const val CHANNEL_ID = "tunnel_forge_proxy"
        private const val NOTIFICATION_ID = 7111

        private fun sanitizePort(value: Int, fallback: Int): Int = if (value in 1..65535) value else fallback
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
                logger(Log.INFO, "Previous proxy worker joined")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            logger(Log.WARN, "Interrupted while waiting for previous proxy worker to stop")
        }
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
        emitTerminal: (String, String) -> Unit,
        onLoopCrash: (Throwable) -> Unit,
    ) {
        try {
            emitEngineLog(Log.INFO, "${prefixAttempt(attemptId)}nativeStartProxyLoop thread running")
            val code = startLoop()
            emitEngineLog(Log.INFO, "${prefixAttempt(attemptId)}nativeStartProxyLoop exited with code=$code")
            if (code != 0) {
                emitTerminal(VpnContract.TUNNEL_FAILED, detailForCode(code))
            } else if (isStopRequested()) {
                // ACTION_STOP already emitted the terminal state for user-initiated disconnects.
            } else if (isConnected()) {
                emitTerminal(VpnContract.TUNNEL_FAILED, "Local proxy connection was lost.")
            } else {
                emitTerminal(VpnContract.TUNNEL_FAILED, "Local proxy bridge stopped before becoming ready")
            }
        } catch (t: Throwable) {
            onLoopCrash(t)
            emitTerminal(VpnContract.TUNNEL_FAILED, t.message ?: "nativeStartProxyLoop crashed")
        }
    }

    private fun prefixAttempt(attemptId: String): String = if (attemptId.isEmpty()) "" else "attempt=$attemptId "
}
