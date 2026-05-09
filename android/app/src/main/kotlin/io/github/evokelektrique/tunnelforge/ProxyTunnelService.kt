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
 * This reuses the real native negotiation path without VpnService socket protection. TCP proxy
 * sessions are carried by a gVisor netstack connected to the native packet bridge so this mode can
 * coexist with another app's Android VPN/TUN.
 */
class ProxyTunnelService : Service() {
    private val sessionLock = Any()
    private var worker: Thread? = null
    private var proxyRuntime: LocalProxyRuntime? = null
    private var proxyTransport: ProxyTransport? = null
    private var proxyLoopThread: Thread? = null
    private var activeProxyConfig: ProxyRuntimeConfig? = null
    private var activeAttemptId: String = ""
    @Volatile
    private var connectedEmitted = false
    @Volatile
    private var stopRequested = false
    private val terminalEventEmitted = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
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
                RuntimeEnvironmentInfo.emit(this, TAG, prefixAttempt(attemptId), mode = VpnContract.MODE_PROXY_ONLY)
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
        val nativeOwner = NativeTunnelOwner(VpnContract.MODE_PROXY_ONLY, attemptId)
        var nativeOwnerAcquired = false
        var localRuntime: LocalProxyRuntime? = null
        var localLoopThread: Thread? = null
        var localGvisorOutboundThread: Thread? = null
        var localGvisorInboundThread: Thread? = null
        var localPacketBridge: ProxyPacketBridge? = null
        var localGvisorStarted = false
        var effectiveProxyConfig = proxyConfig
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
            nativeOwnerAcquired =
                NativeTunnelSessions.shared.acquire(
                    nativeOwner,
                    reason = "proxy negotiation start",
                )
            if (!nativeOwnerAcquired) {
                emitAttemptTerminalState(attemptId, VpnContract.TUNNEL_FAILED, "Tunnel engine is still stopping; try again.")
                return
            }
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
                "${prefixAttempt(attemptId)}Negotiated tunnel clientIpv4=${negotiatedClientIp.joinToString(".")}; awaiting native packet bridge",
            )
            emitAttemptState(
                attemptId,
                VpnContract.TUNNEL_CONNECTING,
                "Tunnel negotiated. Starting gVisor local-proxy TCP stack...",
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
            localLoopThread = loopThread
            synchronized(sessionLock) {
                if (worker === currentWorker) {
                    proxyLoopThread = loopThread
                }
            }
            /*
             * Proxy-only dataplane boot order is strict: native PPP bridge first, then gVisor,
             * then DNS/pump/listener wiring. Starting listeners earlier would accept clients
             * before there is a packet path to the negotiated tunnel.
             */
            loopThread.start()
            VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}waiting for native packet bridge readiness")
            if (!waitUntilProxyPacketBridgeActive()) {
                if (!ProxyTunnelServiceStopPolicy.shouldFailBridgeReadiness(stopRequested)) {
                    VpnTunnelEvents.emitEngineLog(
                        Log.DEBUG,
                        TAG,
                        "${prefixAttempt(attemptId)}packet bridge readiness wait ended after stop request",
                    )
                } else {
                    emitAttemptTerminalState(attemptId, VpnContract.TUNNEL_FAILED, "Native proxy packet bridge did not become ready")
                }
                NativeTunnelSessions.shared.stopOwner(nativeOwner, reason = "proxy packet bridge readiness failed")
                loopThread.join(5000)
                return
            }
            val gvisorStartResult = VpnBridge.nativeGvisorStart(negotiatedClientIp, proxyMtu)
            if (gvisorStartResult != 0) {
                emitAttemptTerminalState(
                    attemptId,
                    VpnContract.TUNNEL_FAILED,
                    "gVisor local proxy TCP stack did not start: code $gvisorStartResult",
                )
                NativeTunnelSessions.shared.stopOwner(nativeOwner, reason = "gVisor startup failed")
                loopThread.join(5000)
                return
            }
            localGvisorStarted = true
            VpnTunnelEvents.emitEngineLog(Log.INFO, TAG, "${prefixAttempt(attemptId)}gVisor proxy dataplane ready")
            val packetBridge = ProxyPacketBridge()
            if (!packetBridge.waitUntilActive(timeoutMs = 1000, pollIntervalMs = 25)) {
                emitAttemptTerminalState(attemptId, VpnContract.TUNNEL_FAILED, "Native proxy packet bridge became unavailable before gVisor startup completed")
                NativeTunnelSessions.shared.stopOwner(nativeOwner, reason = "proxy packet bridge unavailable after gVisor startup")
                loopThread.join(5000)
                return
            }
            localPacketBridge = packetBridge
            val udpDnsServers = effectiveDnsServers.filter { it.protocol == DnsProtocol.dnsOverUdp }
            /*
             * Proxy-only hostname resolution must stay inside the tunnel. Without a UDP DNS
             * server from negotiation, hostname targets fail closed instead of using Android DNS.
             */
            val dnsResolver =
                if (udpDnsServers.isNotEmpty()) {
                    TunneledDnsResolver(
                        bridge = packetBridge,
                        clientIpv4 = negotiatedClientIp.joinToString("."),
                        dnsServers = udpDnsServers,
                        openTunneledSocket = {
                            throw java.io.IOException("Only DNS-over-UDP is supported in proxy-only gVisor resolver.")
                        },
                        logger = { level, message ->
                            VpnTunnelEvents.emitEngineLog(level, TAG, "${prefixAttempt(attemptId)}$message")
                        },
                    )
                } else {
                    VpnTunnelEvents.emitEngineLog(
                        Log.WARN,
                        TAG,
                        "${prefixAttempt(attemptId)}No UDP DNS server available for tunneled proxy hostname resolution; hostname targets will fail closed instead of using Android DNS",
                    )
                    null
            }
            /*
             * Pumps connect packet-level gVisor to packet-level PPP. The transport below handles
             * stream sessions, while these threads keep raw IP packets flowing in both directions.
             */
            localGvisorOutboundThread = startGvisorOutboundPump(attemptId, loopThread, packetBridge)
            localGvisorInboundThread = startGvisorInboundPump(attemptId, loopThread, dnsResolver)
            val transport =
                GvisorProxyTransport(
                    tunneledResolver = dnsResolver?.let { resolver -> { host -> resolver.resolve(host) } },
                    requireTunneledDns = true,
                    logger = { level, message ->
                        VpnTunnelEvents.emitEngineLog(level, TAG, "${prefixAttempt(attemptId)}$message")
                    },
                )
            val exposure =
                LanProxyAddressResolver(this).resolve(
                    httpPort = proxyConfig.httpPort,
                    socksPort = proxyConfig.socksPort,
                    lanRequested = proxyConfig.allowLanConnections,
                )
            val portSelection =
                ProxyPortAllocator.choosePreferredThenRandom(
                    requestedHttpPort = proxyConfig.httpPort,
                    requestedSocksPort = proxyConfig.socksPort,
                    bindHosts = exposure.listenerBindAddresses(),
                )
            if (portSelection == null) {
                emitAttemptTerminalState(
                    attemptId,
                    VpnContract.TUNNEL_FAILED,
                    "Couldn't start proxy listeners: no available HTTP/SOCKS5 ports after ${ProxyPortAllocator.RANDOM_CHECKS} random checks.",
                )
                NativeTunnelSessions.shared.stopOwner(nativeOwner, reason = "proxy listener ports unavailable")
                loopThread.join(5000)
                return
            }
            effectiveProxyConfig =
                proxyConfig.copy(
                    httpPort = portSelection.httpPort,
                    socksPort = portSelection.socksPort,
                )
            val effectiveExposure =
                exposure.copy(
                    httpPort = portSelection.httpPort,
                    socksPort = portSelection.socksPort,
                )
            if (portSelection.randomFallbackUsed) {
                VpnTunnelEvents.emitEngineLog(
                    Log.WARN,
                    TAG,
                    "${prefixAttempt(attemptId)}Default proxy ports unavailable; selected random fallback ports requestedHttp=${proxyConfig.httpPort} requestedSocks=${proxyConfig.socksPort} http=${portSelection.httpPort} socks=${portSelection.socksPort}",
                )
            } else if (portSelection.differsFrom(proxyConfig.httpPort, proxyConfig.socksPort)) {
                VpnTunnelEvents.emitEngineLog(
                    Log.WARN,
                    TAG,
                    "${prefixAttempt(attemptId)}Proxy listener ports changed requestedHttp=${proxyConfig.httpPort} requestedSocks=${proxyConfig.socksPort} http=${portSelection.httpPort} socks=${portSelection.socksPort}",
                )
            }
            val runtime = NettyProxyServerRuntime(
                config =
                    effectiveProxyConfig.copy(
                        exposure = effectiveExposure,
                        maxConcurrentClients = PROXY_ONLY_MAX_CONCURRENT_CLIENTS,
                        suppressUpstreamHttpErrors = true,
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
                    proxyTransport = transport
                    proxyRuntime = runtime
                    proxyLoopThread = loopThread
                    activeProxyConfig = effectiveProxyConfig
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
            VpnTunnelEvents.emitProxyExposureChanged(effectiveExposure)
            effectiveExposure.warning?.let { warning ->
                VpnTunnelEvents.emitEngineLog(Log.WARN, TAG, "${prefixAttempt(attemptId)}$warning")
            }
            VpnTunnelEvents.emitEngineLog(
                Log.INFO,
                TAG,
                "${prefixAttempt(attemptId)}Proxy ready ${runtime.endpointSummary()}",
            )
            connectedEmitted = true
            updateForegroundNotification(runtime.endpointSummary())
            val changedPortNotice =
                if (portSelection.differsFrom(proxyConfig.httpPort, proxyConfig.socksPort)) {
                    "Proxy ports changed: ${runtime.endpointSummary()}"
                } else {
                    null
                }
            emitAttemptState(
                attemptId,
                VpnContract.TUNNEL_CONNECTED,
                changedPortNotice
                    ?: "gVisor TCP stack active. ${runtime.endpointSummary()}. Hostnames resolve before tunneled connect; IPv6 destinations are still unsupported.",
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
            /*
             * Tear down consumers before producers: stop listeners, stop packet pumps/bridge,
             * stop gVisor, then release the native tunnel owner.
             */
            localRuntime?.stop()
            localGvisorOutboundThread?.interrupt()
            localGvisorInboundThread?.interrupt()
            localPacketBridge?.stop()
            if (localGvisorStarted) {
                VpnBridge.nativeGvisorStop()
            }
            joinQuietly(localGvisorOutboundThread, 1000)
            joinQuietly(localGvisorInboundThread, 1000)
            if (nativeOwnerAcquired) {
                NativeTunnelSessions.shared.stopOwner(nativeOwner, reason = "proxy worker cleanup")
            }
            localLoopThread?.let { loop ->
                if (loop !== Thread.currentThread() && loop.isAlive) {
                    try {
                        loop.join(ProxyTunnelServiceStopper.LOOP_JOIN_TIMEOUT_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
            VpnTunnelEvents.emitProxyExposureChanged(
                ProxyExposureInfo.inactive(
                    httpPort = effectiveProxyConfig.httpPort,
                    socksPort = effectiveProxyConfig.socksPort,
                    lanRequested = effectiveProxyConfig.allowLanConnections,
                ),
            )
            VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}proxy worker finalizing")
            var shouldStopService = false
            synchronized(sessionLock) {
                if (worker === currentWorker) {
                    worker = null
                    proxyRuntime = null
                    proxyTransport = null
                    proxyLoopThread = null
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
            if (nativeOwnerAcquired) {
                NativeTunnelSessions.shared.release(nativeOwner, reason = "proxy worker finalizing")
            }
        }
    }

    private fun shutdownActiveSession(reason: String): Boolean {
        val active =
            synchronized(sessionLock) {
                stopRequested = true
                ActiveProxySession(
                    attemptId = activeAttemptId,
                    worker = worker,
                    runtime = proxyRuntime,
                    loopThread = proxyLoopThread,
                    hasTransport = proxyTransport != null,
                ).also {
                    worker = null
                    proxyRuntime = null
                    proxyTransport = null
                    proxyLoopThread = null
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
            loopThread = active.loopThread,
            onStopNativeTunnel = {
                NativeTunnelSessions.shared.stopOwner(active.nativeOwner(), reason = "proxy service stop")
            },
            logger = { level, message ->
                VpnTunnelEvents.emitEngineLog(level, TAG, message)
            },
            joinTimeoutMs = WORKER_JOIN_TIMEOUT_MS,
        )
        if (active.worker == null) {
            active.nativeOwner()?.let {
                NativeTunnelSessions.shared.release(it, reason = "proxy service stopped")
            }
        }
        return true
    }

    private fun currentAttemptId(): String =
        synchronized(sessionLock) { activeAttemptId }

    private fun waitUntilProxyPacketBridgeActive(timeoutMs: Long = 4000, pollIntervalMs: Long = 100): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (VpnBridge.nativeIsProxyPacketBridgeActive()) return true
            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return VpnBridge.nativeIsProxyPacketBridgeActive()
    }

    private fun startGvisorOutboundPump(
        attemptId: String,
        loopThread: Thread,
        packetBridge: ProxyPacketBridge,
    ): Thread =
        /*
         * Outbound means "from gVisor toward the negotiated PPP tunnel". These packets are read
         * from the native gVisor queue and handed to the native packet bridge for encapsulation.
         */
        Thread(
            {
                while (!stopRequested && loopThread.isAlive && !Thread.currentThread().isInterrupted) {
                    try {
                        val packet = VpnBridge.nativeGvisorReadOutbound(PROXY_PACKET_MAX_BYTES, 50)
                        if (packet == null || packet.isEmpty()) continue
                        logGvisorPacketEvent(attemptId, "out", packet)
                        if (!packetBridge.queueOutboundPacket(packet)) {
                            VpnTunnelEvents.emitEngineLog(
                                Log.WARN,
                                TAG,
                                "${prefixAttempt(attemptId)}gVisor outbound packet enqueue failed len=${packet.size}",
                            )
                        }
                    } catch (t: Throwable) {
                        if (stopRequested || Thread.currentThread().isInterrupted) break
                        VpnTunnelEvents.emitEngineLog(
                            Log.WARN,
                            TAG,
                            "${prefixAttempt(attemptId)}gVisor outbound pump error: ${t.message ?: t.javaClass.simpleName}",
                        )
                    }
                }
            },
            "gvisor-outbound-pump",
        ).also {
            it.isDaemon = true
            it.start()
        }

    private fun startGvisorInboundPump(
        attemptId: String,
        loopThread: Thread,
        dnsResolver: TunneledDnsResolver?,
    ): Thread =
        /*
         * Inbound means "from PPP toward gVisor". DNS replies are offered to the tunneled resolver
         * before TCP packets are injected into gVisor.
         */
        Thread(
            {
                while (!stopRequested && loopThread.isAlive && !Thread.currentThread().isInterrupted) {
                    try {
                        val packet = VpnBridge.nativeReadProxyInboundPacket(PROXY_PACKET_MAX_BYTES)
                        if (packet == null || packet.isEmpty()) continue
                        if (dnsResolver != null && handleDnsInboundPacket(dnsResolver, packet)) {
                            continue
                        }
                        logGvisorPacketEvent(attemptId, "in", packet)
                        val rc = VpnBridge.nativeGvisorInjectInbound(packet)
                        if (rc != 0) {
                            VpnTunnelEvents.emitEngineLog(
                                Log.WARN,
                                TAG,
                                "${prefixAttempt(attemptId)}gVisor inbound packet inject failed rc=$rc len=${packet.size}",
                            )
                        }
                    } catch (t: Throwable) {
                        if (stopRequested || Thread.currentThread().isInterrupted) break
                        VpnTunnelEvents.emitEngineLog(
                            Log.WARN,
                            TAG,
                            "${prefixAttempt(attemptId)}gVisor inbound pump error: ${t.message ?: t.javaClass.simpleName}",
                        )
                    }
                }
            },
            "gvisor-inbound-pump",
        ).also {
            it.isDaemon = true
            it.start()
        }

    private fun handleDnsInboundPacket(dnsResolver: TunneledDnsResolver, packet: ByteArray): Boolean {
        val ipv4 = IpPacketParser.parseIpv4(packet) ?: return false
        if (ipv4.protocol != IpPacketParser.IP_PROTOCOL_UDP) return false
        val udp = IpPacketParser.parseUdp(packet, ipv4) ?: return false
        return dnsResolver.handleInboundPacket(ipv4, udp, packet)
    }

    private fun logGvisorPacketEvent(attemptId: String, direction: String, packet: ByteArray) {
        val ipv4 = IpPacketParser.parseIpv4(packet)
        if (ipv4 == null) {
            if (packet.isNotEmpty() && ((packet[0].toInt() ushr 4) and 0x0f) == 4) {
                VpnTunnelEvents.emitEngineLog(
                    Log.WARN,
                    TAG,
                    "${prefixAttempt(attemptId)}gVisor packet $direction malformed-ipv4 len=${packet.size} first=${packet.first().toInt() and 0xff}",
                )
            }
            return
        }
        if (ipv4.protocol != IpPacketParser.IP_PROTOCOL_TCP) return
        val tcp = IpPacketParser.parseTcp(packet, ipv4)
        if (tcp == null) {
            VpnTunnelEvents.emitEngineLog(
                Log.WARN,
                TAG,
                "${prefixAttempt(attemptId)}gVisor packet $direction malformed-tcp ipTotal=${ipv4.totalLength} packetLen=${packet.size}",
            )
        }
    }

    private fun joinQuietly(thread: Thread?, timeoutMs: Long) {
        if (thread == null || thread === Thread.currentThread() || !thread.isAlive) return
        try {
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

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
        internal const val PROXY_ONLY_CONNECT_RESPONSE_TIMEOUT_MS = 30_000L
        internal const val PROXY_PACKET_MAX_BYTES = 65_535
        internal val PROXY_ONLY_SYN_RETRANSMIT_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L)
        internal const val PROXY_ONLY_SYN_PACING_INTERVAL_MS = 0L
        internal const val PROXY_ONLY_TCP_FIN_DRAIN_TIMEOUT_MS = 5_000L

        private const val CHANNEL_ID = "tunnel_forge_proxy"
        private const val NOTIFICATION_ID = 7111

        internal fun sanitizePort(value: Int, fallback: Int): Int = if (value in 1..65535) value else fallback
        private fun sanitizeMtu(value: Int): Int = TunnelVpnService.sanitizeMtu(value)

        private fun prefixAttempt(attemptId: String): String = if (attemptId.isEmpty()) "" else "attempt=$attemptId "

        @Volatile
        private var instance: ProxyTunnelService? = null

        @JvmStatic
        fun stopActiveSessionForModeSwitch(reason: String): Boolean {
            val svc = instance ?: return false
            val hadActiveSession = svc.shutdownActiveSession(reason = "mode switch: $reason")
            if (!hadActiveSession) return false
            VpnTunnelEvents.emitEngineLog(
                Log.DEBUG,
                TAG,
                "Stopped proxy service before mode switch reason=$reason",
            )
            try {
                svc.stopForeground(STOP_FOREGROUND_REMOVE)
                svc.stopSelf()
            } catch (e: Exception) {
                AppLog.w(TAG, "stopActiveSessionForModeSwitch", e)
            }
            return true
        }

        @JvmStatic
        fun runtimeSnapshot(): Map<String, Any?>? {
            val svc = instance ?: return null
            val active =
                synchronized(svc.sessionLock) {
                    svc.worker != null ||
                        svc.proxyRuntime != null ||
                        svc.proxyTransport != null ||
                        svc.proxyLoopThread != null
                }
            if (!active) return null
            val attemptId = svc.currentAttemptId()
            val connected = svc.connectedEmitted
            val exposure =
                svc.proxyRuntime?.exposureInfo()
                    ?: svc.activeProxyConfig?.let {
                        ProxyExposureInfo.loopback(
                            httpPort = it.httpPort,
                            socksPort = it.socksPort,
                            lanRequested = it.allowLanConnections,
                            active = connected,
                        )
                    }
            return RuntimeStateSnapshot.tunnel(
                state = if (connected) VpnContract.TUNNEL_CONNECTED else VpnContract.TUNNEL_CONNECTING,
                detail = if (connected) "Local proxy listeners are active." else "Restoring active local proxy session...",
                attemptId = attemptId,
                connectionMode = VpnContract.MODE_PROXY_ONLY,
                proxyExposure = exposure,
            )
        }
    }
}

private data class ActiveProxySession(
    val attemptId: String,
    val worker: Thread?,
    val runtime: LocalProxyRuntime?,
    val loopThread: Thread?,
    val hasTransport: Boolean,
) {
    fun hasState(): Boolean = worker != null || runtime != null || loopThread != null || hasTransport

    fun nativeOwner(): NativeTunnelOwner? =
        attemptId.takeIf { it.isNotEmpty() }?.let {
            NativeTunnelOwner(VpnContract.MODE_PROXY_ONLY, it)
        }
}

internal object ProxyTunnelServiceStopper {
    internal const val LOOP_JOIN_TIMEOUT_MS = 5_000L

    fun stopPreviousWorker(
        worker: Thread?,
        loopThread: Thread? = null,
        onStopNativeTunnel: () -> Unit,
        logger: (Int, String) -> Unit,
        joinTimeoutMs: Long,
    ) {
        onStopNativeTunnel()
        joinThread(
            thread = loopThread,
            threadLabel = "proxy loop",
            logger = logger,
            joinTimeoutMs = LOOP_JOIN_TIMEOUT_MS,
        )
        if (worker == null) return
        worker.interrupt()
        joinThread(
            thread = worker,
            threadLabel = "proxy worker",
            logger = logger,
            joinTimeoutMs = joinTimeoutMs,
        )
    }

    private fun joinThread(
        thread: Thread?,
        threadLabel: String,
        logger: (Int, String) -> Unit,
        joinTimeoutMs: Long,
    ) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(joinTimeoutMs)
            if (thread.isAlive) {
                logger(Log.WARN, "Previous $threadLabel did not exit within ${joinTimeoutMs}ms")
            } else {
                logger(Log.DEBUG, "Previous $threadLabel joined")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            logger(Log.WARN, "Interrupted while waiting for previous $threadLabel to stop")
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
                emitTerminal(attemptId, VpnContract.TUNNEL_FAILED, "Native local proxy TCP stack stopped before becoming ready")
            }
        } catch (t: Throwable) {
            onLoopCrash(t)
            emitTerminal(attemptId, VpnContract.TUNNEL_FAILED, t.message ?: "nativeStartProxyLoop crashed")
        }
    }

    private fun prefixAttempt(attemptId: String): String = if (attemptId.isEmpty()) "" else "attempt=$attemptId "
}
