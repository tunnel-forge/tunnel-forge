package io.github.evokelektrique.tunnelforge

import androidx.annotation.Keep
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground [VpnService]: TUN, notification, native tunnel thread. */
@Keep
class TunnelVpnService : VpnService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingStopSelfRunnable =
        Runnable {
            try {
                stopSelf()
            } catch (e: Exception) {
                AppLog.e(TAG, "stopSelf after tunnel", e)
            }
        }

    private val running = AtomicBoolean(false)
    private val connectedEmitted = AtomicBoolean(false)
    private var tunInterface: ParcelFileDescriptor? = null
    private var engineThread: Thread? = null
    private var localDnsServer: LocalDnsServer? = null
    private var activeServer: String = ""
    private var activeProfileName: String? = null

    private fun cancelPendingStopSelf() {
        mainHandler.removeCallbacks(pendingStopSelfRunnable)
    }

    private fun schedulePendingStopSelf() {
        mainHandler.removeCallbacks(pendingStopSelfRunnable)
        mainHandler.postDelayed(pendingStopSelfRunnable, 300L)
    }

    /**
     * Keep IKE/L2TP traffic to the VPN gateway off the TUN default route (API 33+).
     * [protect] alone is unreliable on some OEMs when the peer is on the LAN.
     */
    private fun applyExcludeRouteForVpnServer(builder: Builder, server: String) {
        try {
            val resolved = InetAddress.getByName(server)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && resolved is Inet4Address -> {
                    builder.excludeRoute(IpPrefix(resolved, 32))
                    AppLog.d(TAG, "excludeRoute IPv4/32 for VPN server ${resolved.hostAddress}")
                }
                resolved is Inet4Address ->
                    AppLog.d(
                        TAG,
                        "excludeRoute requires API 33+; using socket protect() only (device API ${Build.VERSION.SDK_INT})",
                    )
                else -> AppLog.w(TAG, "VPN server is not IPv4; excludeRoute not applied")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not resolve VPN server for excludeRoute: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        cancelPendingStopSelf()
        stopTunnelInternal()
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelPendingStopSelf()
                val attemptId = intent.getStringExtra(EXTRA_ATTEMPT_ID) ?: ""
                VpnTunnelEvents.emitEngineLog(Log.INFO, TAG, "${prefixAttempt(attemptId)}ACTION_STOP: tearing down tunnel")
                stopTunnelInternal()
                VpnTunnelEvents.emit(VpnContract.TUNNEL_STOPPED, "Stopped by app")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // Required immediately after Context.startForegroundService(); must run before any early return.
                startForegroundWithType(buildNotification(getString(R.string.vpn_notification_connecting)))
                val attemptId = intent.getStringExtra(EXTRA_ATTEMPT_ID) ?: ""
                val server = intent.getStringExtra(EXTRA_SERVER)
                if (server.isNullOrEmpty()) {
                    AppLog.e(TAG, "${prefixAttempt(attemptId)}ACTION_START missing server")
                    VpnTunnelEvents.emitEngineLog(Log.ERROR, TAG, "${prefixAttempt(attemptId)}ACTION_START rejected: missing server")
                    VpnTunnelEvents.emit(VpnContract.TUNNEL_FAILED, "Invalid tunnel arguments from the app.")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val user = intent.getStringExtra(EXTRA_USER) ?: ""
                val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
                val psk = intent.getStringExtra(EXTRA_PSK) ?: ""
                val dnsAutomatic = intent.getBooleanExtra(EXTRA_DNS_AUTOMATIC, true)
                val dnsServers = manualDnsServersFromIntent(intent)
                val tunMtu = sanitizeMtu(intent.getIntExtra(EXTRA_MTU, DEFAULT_TUN_MTU))
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME)?.trim().orEmpty()
                val routingMode = intent.getStringExtra(EXTRA_ROUTING_MODE) ?: VpnContract.ROUTING_FULL_TUNNEL
                val allowedPackages = intent.getStringArrayListExtra(EXTRA_ALLOWED_PACKAGES)
                activeServer = server
                activeProfileName = profileName.ifEmpty { null }
                VpnTunnelEvents.emitEngineLog(
                    Log.DEBUG,
                    TAG,
                    "${prefixAttempt(attemptId)}ACTION_START accepted server=$server userPresent=${user.isNotEmpty()} pskPresent=${psk.isNotEmpty()} dnsMode=${if (dnsAutomatic) "automatic" else "manual"} dns=${dnsServers.joinToString(",") { "${it.host}[${it.protocol.shortLabel}]" }} mtu=$tunMtu routing=$routingMode",
                )
                // TUN establish() can block; do not hold up onStartCommand after startForeground.
                Thread(
                    {
                        startTunnel(attemptId, server, user, password, psk, dnsAutomatic, dnsServers, tunMtu, routingMode, allowedPackages)
                    },
                    "tun-setup",
                ).start()
                return START_STICKY
            }
            else -> {
                if (intent?.action != null) {
                    AppLog.w(TAG, "Unknown action: ${intent.action}")
                }
                if (!running.get()) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
    }

    private fun startTunnel(
        attemptId: String,
        server: String,
        user: String,
        password: String,
        psk: String,
        dnsAutomatic: Boolean,
        dnsServers: List<DnsServerConfig>,
        tunMtu: Int,
        routingMode: String,
        allowedPackages: ArrayList<String>?,
    ) {
        cancelPendingStopSelf()
        if (running.getAndSet(true)) {
            AppLog.w(TAG, "${prefixAttempt(attemptId)}Tunnel already running")
            return
        }
        connectedEmitted.set(false)

        val perAppPkgs: List<String> =
            if (routingMode == VpnContract.ROUTING_PER_APP_ALLOW_LIST) {
                allowedPackages
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    .orEmpty()
            } else {
                emptyList()
            }
        if (routingMode == VpnContract.ROUTING_PER_APP_ALLOW_LIST && perAppPkgs.isEmpty()) {
            VpnTunnelEvents.emit(
                VpnContract.TUNNEL_FAILED,
                "Per-app VPN needs at least one app selected in the profile.",
            )
            VpnTunnelEvents.emitEngineLog(
                Log.ERROR,
                TAG,
                "${prefixAttempt(attemptId)}Rejected start: per-app allow-list is empty",
            )
            running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        VpnTunnelEvents.emit(VpnContract.TUNNEL_CONNECTING, "Negotiating IKE/L2TP/PPP...")
        VpnTunnelEvents.emitEngineLog(
            Log.INFO,
            TAG,
            "${prefixAttempt(attemptId)}Starting native negotiation (IKE/L2TP/PPP)",
        )
        try {
            VpnBridge.nativeSetSocketProtectionEnabled(true)
            // Phase 1: negotiate IKE+L2TP+PPP on the real network (no VPN tunnel yet).
            val negotiatedClientIp = IntArray(4)
            val negotiatedPrimaryDns = IntArray(4)
            val negotiatedSecondaryDns = IntArray(4)
            val negResult =
                VpnBridge.nativeNegotiate(
                    server,
                    user,
                    password,
                    psk,
                    tunMtu,
                    negotiatedClientIp,
                    negotiatedPrimaryDns,
                    negotiatedSecondaryDns,
                )
            VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}nativeNegotiate finished with exit code=$negResult")
            if (negResult != 0) {
                VpnTunnelEvents.emit(VpnContract.TUNNEL_FAILED, tunnelExitDetail(negResult))
                running.set(false)
                VpnTunnelEvents.emitEngineLog(Log.ERROR, TAG, "${prefixAttempt(attemptId)}Tunnel failed during negotiation code=$negResult")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            // Phase 2: establish TUN interface now that negotiation succeeded.
            VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}Starting TUN establish()")
            val tunIpv4 =
                "${negotiatedClientIp[0]}.${negotiatedClientIp[1]}.${negotiatedClientIp[2]}.${negotiatedClientIp[3]}"
            val useIpcpAddress =
                negotiatedClientIp.all { it in 0..255 } &&
                    negotiatedClientIp.any { it != 0 }
            val addressForTun = if (useIpcpAddress) tunIpv4 else TUN_LOCAL_IPV4
            VpnTunnelEvents.emitEngineLog(
                Log.DEBUG,
                TAG,
                "${prefixAttempt(attemptId)}TUN addAddress=$addressForTun (IPCP=$tunIpv4 useIpcp=$useIpcpAddress)",
            )
            val automaticDnsServers = DnsConfigSupport.negotiatedServers(negotiatedPrimaryDns, negotiatedSecondaryDns)
            val manualDnsServers =
                if (dnsAutomatic) {
                    emptyList()
                } else {
                    DnsConfigSupport.resolveUpstreamServers(dnsServers)
                }
            if ((dnsAutomatic && automaticDnsServers.isEmpty()) ||
                (!dnsAutomatic && manualDnsServers.isEmpty())
            ) {
                VpnTunnelEvents.emit(
                    VpnContract.TUNNEL_FAILED,
                    if (dnsAutomatic) {
                        "PPP negotiation did not provide any DNS servers."
                    } else {
                        "Manual DNS requires at least one DNS server."
                    },
                )
                VpnTunnelEvents.emitEngineLog(
                    Log.ERROR,
                    TAG,
                    "${prefixAttempt(attemptId)}No DNS servers available after negotiation dnsMode=${if (dnsAutomatic) "automatic" else "manual"}",
                )
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            if (!dnsAutomatic) {
                localDnsServer =
                    LocalDnsServer(
                        exchangeClient =
                            DirectDnsExchangeClient(
                                servers = manualDnsServers,
                                logger = { level, message ->
                                    VpnTunnelEvents.emitEngineLog(level, TAG, "${prefixAttempt(attemptId)}$message")
                                },
                            ),
                        logger = { level, message ->
                            VpnTunnelEvents.emitEngineLog(level, TAG, "${prefixAttempt(attemptId)}$message")
                        },
                    ).also { it.start() }
            }
            val builder = Builder()
                .setSession(getString(R.string.vpn_session_name))
                .setMtu(tunMtu)
                .addAddress(addressForTun, 32)
                .addRoute("0.0.0.0", 0)
            if (dnsAutomatic) {
                for (dnsServer in automaticDnsServers.map { it.resolvedIpv4 }) {
                    builder.addDnsServer(dnsServer)
                }
            } else {
                builder.addDnsServer(LocalDnsServer.LOCALHOST_IPV4)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.allowFamily(OsConstants.AF_INET)
                VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}TUN allowFamily(AF_INET) IPv4-only")
            }
            applyExcludeRouteForVpnServer(builder, server)
            if (routingMode == VpnContract.ROUTING_PER_APP_ALLOW_LIST) {
                VpnTunnelEvents.emitEngineLog(
                    Log.DEBUG,
                    TAG,
                    "${prefixAttempt(attemptId)}TUN per-app allow-list packages=${perAppPkgs.size}",
                )
                for (pkg in perAppPkgs) {
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (e: PackageManager.NameNotFoundException) {
                        throw IllegalArgumentException("Package not installed: $pkg")
                    }
                }
            } else {
                VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}TUN full-device routing")
            }
            tunInterface = builder.establish()
            val pfd = tunInterface ?: throw IllegalStateException("TUN establish() returned null")
            val tunFd = pfd.fd
            VpnTunnelEvents.emitEngineLog(
                Log.INFO,
                TAG,
                "${prefixAttempt(attemptId)}TUN established",
            )
            VpnTunnelEvents.emitEngineLog(
                Log.INFO,
                TAG,
                "${prefixAttempt(attemptId)}TUN established; waiting for tunnel loop readiness",
            )

            // Phase 3: run the ESP/L2TP poll loop on a background thread.
            engineThread = Thread(
                {
                    try {
                        VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}nativeStartLoop(tunFd=$tunFd) thread running")
                        val code = VpnBridge.nativeStartLoop(tunFd)
                        VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}nativeStartLoop exited with code=$code")
                        if (code != 0) {
                            VpnTunnelEvents.emit(VpnContract.TUNNEL_FAILED, tunnelExitDetail(code))
                        } else {
                            VpnTunnelEvents.emit(VpnContract.TUNNEL_STOPPED, "Tunnel closed normally")
                        }
                    } catch (t: Throwable) {
                        AppLog.e(TAG, "${prefixAttempt(attemptId)}nativeStartLoop failed", t)
                        VpnTunnelEvents.emit(VpnContract.TUNNEL_FAILED, t.message ?: "nativeStartLoop crashed")
                    } finally {
                        mainHandler.post {
                            finishTunnelUiOnMain()
                        }
                    }
                },
                "tunnel-engine",
            ).also { it.start() }
        } catch (e: Exception) {
            AppLog.e(TAG, "${prefixAttempt(attemptId)}startTunnel", e)
            VpnTunnelEvents.emitEngineLog(Log.ERROR, TAG, "${prefixAttempt(attemptId)}startTunnel exception=${e.javaClass.simpleName}:${e.message}")
            VpnTunnelEvents.emit(VpnContract.TUNNEL_FAILED, e.message ?: "startTunnel failed")
            running.set(false)
            try {
                localDnsServer?.close()
            } catch (_: Exception) {
            }
            localDnsServer = null
            try {
                tunInterface?.close()
            } catch (_: Exception) {
            }
            tunInterface = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Always release TUN + worker state. Safe when the engine thread already cleared [running]
     * (otherwise [onDestroy] would return early and leak the VPN fd).
     */
    private fun stopTunnelInternal() {
        try {
            VpnBridge.nativeStopTunnel()
        } catch (e: Exception) {
            AppLog.w(TAG, "nativeStopTunnel", e)
        }
        try {
            engineThread?.join(8000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        engineThread = null
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
        try {
            localDnsServer?.close()
        } catch (_: Exception) {
        }
        localDnsServer = null
        activeServer = ""
        activeProfileName = null
        connectedEmitted.set(false)
        running.set(false)
    }

    /** Main-thread cleanup after the native worker exits (or from [finally] on the worker). */
    private fun finishTunnelUiOnMain() {
        running.set(false)
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
        try {
            localDnsServer?.close()
        } catch (_: Exception) {
        }
        localDnsServer = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            AppLog.e(TAG, "stopForeground after tunnel", e)
        }
        activeServer = ""
        activeProfileName = null
        connectedEmitted.set(false)
        schedulePendingStopSelf()
    }

    private fun handleNativeTunnelReady(detail: String) {
        if (!running.get()) {
            AppLog.w(TAG, "Ignoring native tunnel ready after shutdown")
            return
        }
        if (!connectedEmitted.compareAndSet(false, true)) {
            return
        }
        VpnTunnelEvents.emit(VpnContract.TUNNEL_CONNECTED, detail)
        updateForegroundNotification(connectedNotificationText())
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
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pending)
            .addAction(
                0,
                getString(R.string.vpn_notification_action_disconnect),
                disconnectPendingIntent(),
            )
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

    private fun connectedNotificationText(): String {
        val label = activeProfileName?.takeIf { it.isNotEmpty() } ?: activeServer
        return if (label.isEmpty()) {
            getString(R.string.vpn_notification_connected)
        } else {
            getString(R.string.vpn_notification_connected_profile, label)
        }
    }

    private fun disconnectPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, TunnelVpnService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            this,
            REQUEST_CODE_DISCONNECT,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun tunnelExitDetail(code: Int): String =
        when (code) {
            1 ->
                "IPsec did not finish (Quick Mode / ESP not ready). " +
                    "If the server is L2TP-only (no IPsec), leave the IPsec PSK field empty. " +
                    "Plaintext L2TP is only for trusted networks."
            2 -> "L2TP handshake failed."
            3 -> "PPP negotiation failed."
            4 -> "Tunnel poll I/O error."
            10 -> "Invalid tunnel arguments from the app."
            11 -> "Proxy transport is not implemented yet."
            else -> "Tunnel engine exited with code $code"
        }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "TunnelVpnService"
        const val ACTION_START = "io.github.evokelektrique.tunnelforge.action.START"
        const val ACTION_STOP = "io.github.evokelektrique.tunnelforge.action.STOP"
        const val EXTRA_ATTEMPT_ID = "attemptId"
        const val EXTRA_SERVER = "server"
        const val EXTRA_USER = "user"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_PSK = "psk"
        const val EXTRA_DNS_AUTOMATIC = "dnsAutomatic"
        const val EXTRA_DNS_SERVER_1_HOST = "dnsServer1Host"
        const val EXTRA_DNS_SERVER_1_PROTOCOL = "dnsServer1Protocol"
        const val EXTRA_DNS_SERVER_2_HOST = "dnsServer2Host"
        const val EXTRA_DNS_SERVER_2_PROTOCOL = "dnsServer2Protocol"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_PROFILE_NAME = "profileName"
        const val EXTRA_ROUTING_MODE = "routingMode"
        const val EXTRA_ALLOWED_PACKAGES = "allowedPackages"

        private const val CHANNEL_ID = "tunnel_forge_vpn"
        private const val NOTIFICATION_ID = 7101
        private const val REQUEST_CODE_DISCONNECT = 7102

        /** When [EXTRA_MTU] is absent or invalid. */
        const val DEFAULT_TUN_MTU = 1450
        private const val MIN_TUN_MTU = 576
        private const val MAX_TUN_MTU = 1500

        private const val TUN_LOCAL_IPV4 = "10.0.0.2"

        fun sanitizeMtu(value: Int): Int = value.coerceIn(MIN_TUN_MTU, MAX_TUN_MTU)

        internal fun manualDnsServersFromIntent(intent: Intent): List<DnsServerConfig> {
            val servers =
                listOf(
                    DnsServerConfig(
                        host = intent.getStringExtra(EXTRA_DNS_SERVER_1_HOST)?.trim().orEmpty(),
                        protocol = DnsProtocol.fromWireValue(intent.getStringExtra(EXTRA_DNS_SERVER_1_PROTOCOL)),
                    ),
                    DnsServerConfig(
                        host = intent.getStringExtra(EXTRA_DNS_SERVER_2_HOST)?.trim().orEmpty(),
                        protocol = DnsProtocol.fromWireValue(intent.getStringExtra(EXTRA_DNS_SERVER_2_PROTOCOL)),
                    ),
                )
            return DnsConfigSupport.sanitize(servers)
        }

        @Volatile
        private var instance: TunnelVpnService? = null

        @JvmStatic
        fun protectSocketFd(fd: Int): Boolean {
            val svc = instance ?: return false
            return try {
                svc.protect(fd)
            } catch (e: Exception) {
                AppLog.e(TAG, "protect failed for fd=$fd", e)
                false
            }
        }

        @JvmStatic
        fun onNativeTunnelReady(detail: String?) {
            val svc = instance ?: return
            val readyDetail =
                detail
                    ?.takeIf { it.isNotBlank() }
                    ?: "TUN interface ready; tunnel loop active"
            svc.mainHandler.post { svc.handleNativeTunnelReady(readyDetail) }
        }
    }

    private fun prefixAttempt(attemptId: String): String =
        if (attemptId.isEmpty()) "" else "attempt=$attemptId "
}
