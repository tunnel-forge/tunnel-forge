package io.github.evokelektrique.tunnelforge

import android.Manifest
import android.app.Activity
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.content.IntentCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream

class MainActivity : FlutterActivity() {

    private var vpnPermissionResult: MethodChannel.Result? = null
    private var notificationPermissionResult: MethodChannel.Result? = null
    private var pendingConnectIntent: Intent? = null
    private var profileTransferChannel: MethodChannel? = null
    private val pendingProfileTransfers = mutableListOf<Map<String, String?>>()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val vpnChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VpnContract.METHOD_CHANNEL)
        val appInfoChannel =
            MethodChannel(
                flutterEngine.dartExecutor.binaryMessenger,
                AppInfoContract.METHOD_CHANNEL,
            )
        profileTransferChannel =
            MethodChannel(
                flutterEngine.dartExecutor.binaryMessenger,
                ProfileTransferContract.METHOD_CHANNEL,
            ).also { channel ->
                channel.setMethodCallHandler { call, result ->
                    when (call.method) {
                        ProfileTransferContract.CONSUME_PENDING_TRANSFERS -> {
                            val pending = ArrayList(pendingProfileTransfers)
                            pendingProfileTransfers.clear()
                            result.success(pending)
                        }
                        else -> result.notImplemented()
                    }
                }
            }
        appInfoChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                AppInfoContract.GET_INSTALLED_VERSION -> {
                    try {
                        result.success(installedVersionInfo())
                    } catch (e: Exception) {
                        AppLog.e(TAG, "getInstalledVersion failed", e)
                        result.error("app_info_failed", e.message, null)
                    }
                }
                else -> result.notImplemented()
            }
        }
        handleProfileTransferIntent(intent, deliverImmediately = false)
        VpnTunnelEvents.attach(vpnChannel)
        vpnChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                VpnContract.LIST_VPN_CANDIDATE_APPS -> {
                    try {
                        result.success(installedAppsForVpnPicker())
                    } catch (e: Exception) {
                        AppLog.e(TAG, "listVpnCandidateApps failed", e)
                        result.error("list_apps_failed", e.message, null)
                    }
                }
                VpnContract.GET_APP_ICON -> {
                    val pkg = call.arguments as? String
                    if (pkg.isNullOrBlank()) {
                        result.error("bad_args", "package name required", null)
                        return@setMethodCallHandler
                    }
                    try {
                        val drawable = packageManager.getApplicationIcon(pkg)
                        val sizePx = (40 * resources.displayMetrics.density).toInt().coerceIn(32, 128)
                        val png = drawableToPngBytes(drawable, sizePx)
                        result.success(png)
                    } catch (e: Exception) {
                        AppLog.w(TAG, "getAppIcon failed for $pkg: ${e.message}")
                        result.success(null)
                    }
                }
                VpnContract.PREPARE_VPN -> {
                    AppLog.d(TAG, "vpn_call prepareVpn start")
                    val intent = VpnService.prepare(this)
                    if (intent == null) {
                        AppLog.d(TAG, "vpn_call prepareVpn result granted=true (already)")
                        result.success(true)
                    } else {
                        vpnPermissionResult = result
                        AppLog.d(TAG, "vpn_call prepareVpn result needs_ui=true")
                        startActivityForResult(intent, REQUEST_VPN_PERMISSION)
                    }
                }
                VpnContract.CONNECT -> {
                    val args = call.arguments as? Map<*, *>
                    if (args == null) {
                        AppLog.e(TAG, "vpn_call connect rejected bad_args (non-map)")
                        result.error("bad_args", "Expected map", null)
                        return@setMethodCallHandler
                    }
                    val attemptId = args[VpnContract.ARG_ATTEMPT_ID] as? String ?: ""
                    val server = args[VpnContract.ARG_SERVER] as? String
                    if (server.isNullOrBlank()) {
                        AppLog.e(TAG, "vpn_call connect rejected bad_args (server required) attempt=$attemptId")
                        result.error("bad_args", "server required", null)
                        return@setMethodCallHandler
                    }
                    val user = args[VpnContract.ARG_USER] as? String ?: ""
                    val password = args[VpnContract.ARG_PASSWORD] as? String ?: ""
                    val psk = args[VpnContract.ARG_PSK] as? String ?: ""
                    val dnsAutomatic = args[VpnContract.ARG_DNS_AUTOMATIC] as? Boolean ?: true
                    val dnsServers = parseDnsServers(args[VpnContract.ARG_DNS_SERVERS])
                    val mtuRaw = args[VpnContract.ARG_MTU]
                    val mtu = TunnelVpnService.sanitizeMtu(
                        when (mtuRaw) {
                            is Int -> mtuRaw
                            is Long -> mtuRaw.toInt()
                            is Number -> mtuRaw.toInt()
                            else -> TunnelVpnService.DEFAULT_TUN_MTU
                        },
                    )
                    val profileName = (args[VpnContract.ARG_PROFILE_NAME] as? String)?.trim().orEmpty()
                    val connectionModeRaw = args[VpnContract.ARG_CONNECTION_MODE] as? String
                    val connectionMode =
                        if (connectionModeRaw == VpnContract.MODE_PROXY_ONLY) {
                            VpnContract.MODE_PROXY_ONLY
                        } else {
                            VpnContract.MODE_VPN_TUNNEL
                        }
                    val splitTunnelEnabled =
                        args[VpnContract.ARG_SPLIT_TUNNEL_ENABLED] as? Boolean ?: false
                    val splitTunnelModeRaw = args[VpnContract.ARG_SPLIT_TUNNEL_MODE] as? String
                    val splitTunnelMode =
                        if (splitTunnelModeRaw == VpnContract.SPLIT_TUNNEL_MODE_EXCLUSIVE) {
                            VpnContract.SPLIT_TUNNEL_MODE_EXCLUSIVE
                        } else {
                            VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE
                        }
                    val inclusivePackages = mutableListOf<String>()
                    val inclusiveRaw = args[VpnContract.ARG_SPLIT_TUNNEL_INCLUSIVE_PACKAGES]
                    if (inclusiveRaw is List<*>) {
                        for (e in inclusiveRaw) {
                            val s = e as? String
                            if (!s.isNullOrBlank()) inclusivePackages.add(s.trim())
                        }
                    }
                    val exclusivePackages = mutableListOf<String>()
                    val exclusiveRaw = args[VpnContract.ARG_SPLIT_TUNNEL_EXCLUSIVE_PACKAGES]
                    if (exclusiveRaw is List<*>) {
                        for (e in exclusiveRaw) {
                            val s = e as? String
                            if (!s.isNullOrBlank()) exclusivePackages.add(s.trim())
                        }
                    }
                    val proxyHttpPort = sanitizePort(args[VpnContract.ARG_PROXY_HTTP_PORT], ProxyTunnelService.DEFAULT_HTTP_PORT)
                    val proxySocksPort = sanitizePort(args[VpnContract.ARG_PROXY_SOCKS_PORT], ProxyTunnelService.DEFAULT_SOCKS_PORT)
                    val proxyAllowLan = args[VpnContract.ARG_PROXY_ALLOW_LAN] as? Boolean ?: false
                    if (proxyHttpPort == proxySocksPort) {
                        AppLog.e(TAG, "vpn_call connect rejected bad_args (duplicate proxy ports) attempt=$attemptId")
                        result.error("bad_args", "HTTP and SOCKS5 ports must differ", null)
                        return@setMethodCallHandler
                    }
                    val serverTrim = server.trim()
                    val hasScheme = serverTrim.contains("://")
                    val hasPort = serverTrim.count { it == ':' } == 1 && !serverTrim.contains("::")
                    if (serverTrim != server) {
                        AppLog.w(TAG, "vpn_call connect server had leading/trailing whitespace attempt=$attemptId")
                    }
                    if (hasScheme) {
                        AppLog.w(TAG, "vpn_call connect server looks like URL; expected host only attempt=$attemptId server=$serverTrim")
                    }
                    if (hasPort) {
                        AppLog.w(TAG, "vpn_call connect server includes port; expected host only attempt=$attemptId server=$serverTrim")
                    }
                    if (connectionMode == VpnContract.MODE_VPN_TUNNEL) {
                        val prep = VpnService.prepare(this)
                        if (prep != null) {
                            AppLog.e(TAG, "vpn_call connect rejected vpn_permission attempt=$attemptId")
                            result.error("vpn_permission", "VPN permission not granted", null)
                            return@setMethodCallHandler
                        }
                    }
                    AppLog.d(
                        TAG,
                        "vpn_call connect start attempt=$attemptId server=$serverTrim userPresent=${user.isNotEmpty()} pskPresent=${psk.isNotEmpty()} dnsMode=${if (dnsAutomatic) "automatic" else "manual"} dns=${dnsServers.joinToString(",") { "${it.host}[${it.protocol.shortLabel}]" }} mtu=$mtu mode=$connectionMode splitTunnelEnabled=$splitTunnelEnabled splitTunnelMode=$splitTunnelMode inclusiveApps=${inclusivePackages.size} exclusiveApps=${exclusivePackages.size} http=$proxyHttpPort socks=$proxySocksPort lan=$proxyAllowLan",
                    )
                    val cleanupTargets = VpnConnectModeSwitchPolicy.cleanupTargetsForStart(connectionMode)
                    if (cleanupTargets.stopVpn) {
                        TunnelVpnService.stopActiveSessionForModeSwitch("starting $connectionMode")
                    }
                    if (cleanupTargets.stopProxy) {
                        ProxyTunnelService.stopActiveSessionForModeSwitch("starting $connectionMode")
                    }
                    val intent =
                        if (connectionMode == VpnContract.MODE_PROXY_ONLY) {
                            Intent(this, ProxyTunnelService::class.java).apply {
                                action = ProxyTunnelService.ACTION_START
                                putExtra(ProxyTunnelService.EXTRA_ATTEMPT_ID, attemptId)
                                putExtra(ProxyTunnelService.EXTRA_SERVER, serverTrim)
                                putExtra(ProxyTunnelService.EXTRA_USER, user)
                                putExtra(ProxyTunnelService.EXTRA_PASSWORD, password)
                                putExtra(ProxyTunnelService.EXTRA_PSK, psk)
                                putExtra(ProxyTunnelService.EXTRA_DNS_AUTOMATIC, dnsAutomatic)
                                putDnsServerExtras(this, dnsServers)
                                putExtra(ProxyTunnelService.EXTRA_MTU, mtu)
                                putExtra(ProxyTunnelService.EXTRA_PROFILE_NAME, profileName)
                                putExtra(ProxyTunnelService.EXTRA_PROXY_HTTP_PORT, proxyHttpPort)
                                putExtra(ProxyTunnelService.EXTRA_PROXY_SOCKS_PORT, proxySocksPort)
                                putExtra(ProxyTunnelService.EXTRA_PROXY_ALLOW_LAN, proxyAllowLan)
                            }
                        } else {
                            Intent(this, TunnelVpnService::class.java).apply {
                                action = TunnelVpnService.ACTION_START
                                putExtra(TunnelVpnService.EXTRA_ATTEMPT_ID, attemptId)
                                putExtra(TunnelVpnService.EXTRA_SERVER, serverTrim)
                                putExtra(TunnelVpnService.EXTRA_USER, user)
                                putExtra(TunnelVpnService.EXTRA_PASSWORD, password)
                                putExtra(TunnelVpnService.EXTRA_PSK, psk)
                                putExtra(TunnelVpnService.EXTRA_DNS_AUTOMATIC, dnsAutomatic)
                                putDnsServerExtras(this, dnsServers)
                                putExtra(TunnelVpnService.EXTRA_MTU, mtu)
                                putExtra(TunnelVpnService.EXTRA_PROFILE_NAME, profileName)
                                putExtra(TunnelVpnService.EXTRA_SPLIT_TUNNEL_ENABLED, splitTunnelEnabled)
                                putExtra(TunnelVpnService.EXTRA_SPLIT_TUNNEL_MODE, splitTunnelMode)
                                putExtra(TunnelVpnService.EXTRA_PROXY_HTTP_PORT, proxyHttpPort)
                                putExtra(TunnelVpnService.EXTRA_PROXY_SOCKS_PORT, proxySocksPort)
                                putExtra(TunnelVpnService.EXTRA_PROXY_ALLOW_LAN, proxyAllowLan)
                                putStringArrayListExtra(
                                    TunnelVpnService.EXTRA_SPLIT_TUNNEL_INCLUSIVE_PACKAGES,
                                    ArrayList(inclusivePackages),
                                )
                                putStringArrayListExtra(
                                    TunnelVpnService.EXTRA_SPLIT_TUNNEL_EXCLUSIVE_PACKAGES,
                                    ArrayList(exclusivePackages),
                                )
                            }
                        }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationsPermission()) {
                        if (notificationPermissionResult != null) {
                            result.error("notification_permission_pending", "Notification permission request already in progress", null)
                            return@setMethodCallHandler
                        }
                        notificationPermissionResult = result
                        pendingConnectIntent = intent
                        AppLog.d(TAG, "vpn_call connect requesting POST_NOTIFICATIONS attempt=$attemptId")
                        requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_POST_NOTIFICATIONS,
                        )
                        return@setMethodCallHandler
                    }
                    dispatchConnectIntent(intent, result, "vpn_call connect")
                }
                VpnContract.SET_LOG_LEVEL -> {
                    val args = call.arguments as? Map<*, *>
                    val rawLevel = args?.get(VpnContract.ARG_LOG_LEVEL) as? String
                    EngineLogPolicy.update(EngineLogLevel.fromWireValue(rawLevel))
                    result.success(null)
                }
                VpnContract.GET_RUNTIME_STATE -> {
                    result.success(currentRuntimeState())
                }
                VpnContract.DISCONNECT -> {
                    val args = call.arguments as? Map<*, *>
                    val rawMode = args?.get(VpnContract.ARG_CONNECTION_MODE)?.toString()
                    val attemptId = args?.get(VpnContract.ARG_ATTEMPT_ID)?.toString().orEmpty()
                    val targets = VpnDisconnectDispatchPolicy.targetsForConnectionMode(rawMode)
                    AppLog.d(
                        TAG,
                        "vpn_call disconnect dispatched action=ACTION_STOP mode=${rawMode.orEmpty().ifEmpty { "all" }} attempt=$attemptId vpn=${targets.stopVpn} proxy=${targets.stopProxy}",
                    )
                    if (targets.stopVpn) {
                        startService(
                            Intent(this, TunnelVpnService::class.java).apply {
                                action = TunnelVpnService.ACTION_STOP
                                if (attemptId.isNotEmpty()) {
                                    putExtra(TunnelVpnService.EXTRA_ATTEMPT_ID, attemptId)
                                }
                            },
                        )
                    }
                    if (targets.stopProxy) {
                        startService(
                            Intent(this, ProxyTunnelService::class.java).apply {
                                action = ProxyTunnelService.ACTION_STOP
                                if (attemptId.isNotEmpty()) {
                                    putExtra(ProxyTunnelService.EXTRA_ATTEMPT_ID, attemptId)
                                }
                            },
                        )
                    }
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun installedVersionInfo(): Map<String, String> {
        val packageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        val versionName = packageInfo.versionName?.trim().orEmpty()
        val buildNumber =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
        return mapOf(
            AppInfoContract.ARG_VERSION_NAME to versionName,
            AppInfoContract.ARG_BUILD_NUMBER to buildNumber,
        )
    }

    override fun onDestroy() {
        profileTransferChannel = null
        VpnTunnelEvents.detach()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProfileTransferIntent(intent, deliverImmediately = true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_PERMISSION) return
        val pending = vpnPermissionResult ?: return
        vpnPermissionResult = null
        AppLog.d(TAG, "vpn_call prepareVpn result_code=$resultCode")
        if (resultCode == Activity.RESULT_OK) {
            pending.success(true)
        } else {
            pending.success(false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_POST_NOTIFICATIONS) return
        val pending = notificationPermissionResult ?: return
        val connectIntent = pendingConnectIntent
        notificationPermissionResult = null
        pendingConnectIntent = null
        val granted =
            grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            AppLog.w(TAG, "vpn_call connect rejected notification_permission")
            pending.error("notification_permission", "Notification permission is required to show VPN status", null)
            return
        }
        if (connectIntent == null) {
            pending.error("internal", "Missing pending VPN connect intent", null)
            return
        }
        dispatchConnectIntent(connectIntent, pending, "vpn_call connect after notification grant")
    }

    /**
     * All installed apps relevant for per-app VPN, excluding this app and pure system packages
     * (user-installed and updated system apps such as Play-updated Chrome are included).
     * Requires [android.permission.QUERY_ALL_PACKAGES] on Android 11+ for a complete list.
     */
    private fun installedAppsForVpnPicker(): List<Map<String, String>> {
        val pm = packageManager
        val selfPkg = packageName
        val installed =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }
        val seen = LinkedHashSet<String>()
        val pairs = mutableListOf<Pair<String, String>>()
        for (info in installed) {
            val pkg = info.packageName ?: continue
            if (pkg == selfPkg) continue
            val flags = info.flags
            val isPureSystem =
                (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isPureSystem) continue
            if (!seen.add(pkg)) continue
            val label =
                try {
                    info.loadLabel(pm).toString().ifBlank { pkg }
                } catch (_: Exception) {
                    pkg
                }
            pairs.add(pkg to label)
        }
        return pairs
            .sortedBy { it.second.lowercase() }
            .map { mapOf("packageName" to it.first, "label" to it.second) }
    }

    private fun drawableToPngBytes(drawable: Drawable, sizePx: Int): ByteArray {
        val bitmap = drawableToBitmap(drawable, sizePx)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 92, stream)
        return stream.toByteArray()
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable) {
            val existing = drawable.bitmap
            if (existing != null && !existing.isRecycled) {
                return Bitmap.createScaledBitmap(existing, sizePx, sizePx, true)
            }
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }

    private fun startForegroundWorkerService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun dispatchConnectIntent(intent: Intent, result: MethodChannel.Result, logPrefix: String) {
        try {
            startForegroundWorkerService(intent)
            AppLog.d(TAG, "$logPrefix dispatched")
            result.success(null)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            AppLog.e(TAG, "$logPrefix failed foreground_service_start_not_allowed", e)
            result.error(
                "start_service_failed",
                "Android blocked starting the foreground service.",
                null,
            )
        } catch (e: SecurityException) {
            AppLog.e(TAG, "$logPrefix failed security_exception", e)
            result.error(
                "start_service_failed",
                "Android rejected starting the service.",
                null,
            )
        } catch (e: RuntimeException) {
            AppLog.e(TAG, "$logPrefix failed runtime_exception", e)
            result.error(
                "start_service_failed",
                "Could not start the tunnel service.",
                null,
            )
        }
    }

    private fun currentRuntimeState(): Map<String, Any?> {
        val vpnSnapshot = TunnelVpnService.runtimeSnapshot()
        if (vpnSnapshot != null) {
            return vpnSnapshot
        }
        val proxySnapshot = ProxyTunnelService.runtimeSnapshot()
        if (proxySnapshot != null) {
            return proxySnapshot
        }
        return RuntimeStateSnapshot.tunnel(
            state = VpnContract.TUNNEL_STOPPED,
            detail = "Idle",
            attemptId = "",
            connectionMode = VpnContract.MODE_VPN_TUNNEL,
        )
    }

    private fun sanitizePort(raw: Any?, fallback: Int): Int {
        val candidate =
            when (raw) {
                is Int -> raw
                is Long -> raw.toInt()
                is Number -> raw.toInt()
                else -> fallback
            }
        return if (candidate in 1..65535) candidate else fallback
    }

    private fun parseDnsServers(raw: Any?): List<DnsServerConfig> {
        val out = mutableListOf<DnsServerConfig>()
        if (raw is List<*>) {
            for (entry in raw) {
                val map = entry as? Map<*, *> ?: continue
                val host = (map[VpnContract.ARG_DNS_SERVER_HOST] as? String)?.trim().orEmpty()
                val protocol =
                    DnsProtocol.fromWireValue(
                        map[VpnContract.ARG_DNS_SERVER_PROTOCOL] as? String,
                    )
                out += DnsServerConfig(host = host, protocol = protocol)
            }
        }
        return DnsConfigSupport.sanitize(out)
    }

    private fun putDnsServerExtras(intent: Intent, dnsServers: List<DnsServerConfig>) {
        val dns1 = dnsServers.getOrNull(0)
        val dns2 = dnsServers.getOrNull(1)
        intent.putExtra(TunnelVpnService.EXTRA_DNS_SERVER_1_HOST, dns1?.host ?: "")
        intent.putExtra(
            TunnelVpnService.EXTRA_DNS_SERVER_1_PROTOCOL,
            dns1?.protocol?.wireValue ?: DnsProtocol.dnsOverUdp.wireValue,
        )
        intent.putExtra(TunnelVpnService.EXTRA_DNS_SERVER_2_HOST, dns2?.host ?: "")
        intent.putExtra(
            TunnelVpnService.EXTRA_DNS_SERVER_2_PROTOCOL,
            dns2?.protocol?.wireValue ?: DnsProtocol.dnsOverUdp.wireValue,
        )
    }

    private fun hasPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleProfileTransferIntent(intent: Intent?, deliverImmediately: Boolean) {
        val transfer = parseProfileTransferIntent(intent) ?: return
        dispatchProfileTransfer(transfer, deliverImmediately)
    }

    private fun dispatchProfileTransfer(
        transfer: Map<String, String?>,
        deliverImmediately: Boolean,
    ) {
        if (!deliverImmediately) {
            pendingProfileTransfers.add(transfer)
            return
        }
        val channel = profileTransferChannel
        if (channel == null) {
            pendingProfileTransfers.add(transfer)
            return
        }
        channel.invokeMethod(ProfileTransferContract.ON_INCOMING_TRANSFER, transfer)
    }

    private fun parseProfileTransferIntent(intent: Intent?): Map<String, String?>? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> parseViewIntent(intent)
            Intent.ACTION_SEND -> parseSendIntent(intent)
            else -> null
        }
    }

    private fun parseViewIntent(intent: Intent): Map<String, String?>? {
        val uri = intent.data ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme == "tf") {
            return transferPayload(
                type = ProfileTransferContract.TYPE_TF_URI,
                data = uri.toString(),
                source = "link",
            )
        }
        if (!looksLikeProfileFile(intent, uri)) return null
        return profileFileTransfer(uri)
    }

    private fun parseSendIntent(intent: Intent): Map<String, String?>? {
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return null
        if (!looksLikeProfileFile(intent, uri)) return null
        return profileFileTransfer(uri)
    }

    private fun looksLikeProfileFile(intent: Intent, uri: Uri): Boolean {
        val mime = intent.type?.lowercase().orEmpty()
        if (mime == ProfileTransferMimeType) {
            return true
        }
        val path = uri.lastPathSegment?.lowercase().orEmpty()
        return path.endsWith(".tfp")
    }

    private fun profileFileTransfer(uri: Uri): Map<String, String?> {
        return try {
            val data = readUtf8Text(uri)
            transferPayload(
                type = ProfileTransferContract.TYPE_TFP_JSON,
                data = data,
                source = uri.lastPathSegment,
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "profile_transfer read failed uri=$uri", e)
            transferError("Couldn't read the selected .tfp file", uri.lastPathSegment)
        }
    }

    private fun readUtf8Text(uri: Uri): String {
        ProfileImportUriValidator.requireSafeProfileImportUri(uri)
        val input =
            contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Input stream unavailable")
        return input.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun transferPayload(
        type: String,
        data: String,
        source: String?,
    ): Map<String, String?> {
        return mapOf(
            ProfileTransferContract.ARG_TYPE to type,
            ProfileTransferContract.ARG_DATA to data,
            ProfileTransferContract.ARG_SOURCE to source,
        )
    }

    private fun transferError(message: String, source: String?): Map<String, String?> {
        return mapOf(
            ProfileTransferContract.ARG_TYPE to ProfileTransferContract.TYPE_ERROR,
            ProfileTransferContract.ARG_MESSAGE to message,
            ProfileTransferContract.ARG_SOURCE to source,
        )
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_VPN_PERMISSION = 0x4E50
        private const val REQUEST_POST_NOTIFICATIONS = 0x4E51
        private const val ProfileTransferMimeType = "application/vnd.tunnelforge.profile+json"
    }
}

internal data class VpnDisconnectTargets(
    val stopVpn: Boolean,
    val stopProxy: Boolean,
)

internal typealias VpnCleanupTargets = VpnDisconnectTargets

internal object VpnDisconnectDispatchPolicy {
    fun targetsForConnectionMode(mode: String?): VpnDisconnectTargets =
        when (mode?.trim()) {
            VpnContract.MODE_PROXY_ONLY -> VpnDisconnectTargets(stopVpn = false, stopProxy = true)
            VpnContract.MODE_VPN_TUNNEL -> VpnDisconnectTargets(stopVpn = true, stopProxy = false)
            else -> VpnDisconnectTargets(stopVpn = true, stopProxy = true)
        }
}

internal object VpnConnectModeSwitchPolicy {
    fun cleanupTargetsForStart(mode: String?): VpnCleanupTargets =
        when (mode?.trim()) {
            VpnContract.MODE_PROXY_ONLY -> VpnCleanupTargets(stopVpn = true, stopProxy = false)
            VpnContract.MODE_VPN_TUNNEL -> VpnCleanupTargets(stopVpn = false, stopProxy = true)
            else -> VpnCleanupTargets(stopVpn = true, stopProxy = true)
        }
}
