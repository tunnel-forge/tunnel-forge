package com.example.tunnel_forge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private var vpnPermissionResult: MethodChannel.Result? = null
    private var notificationPermissionResult: MethodChannel.Result? = null
    private var pendingConnectIntent: Intent? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val vpnChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VpnContract.METHOD_CHANNEL)
        VpnTunnelEvents.attach(vpnChannel)
        vpnChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                VpnContract.LIST_VPN_CANDIDATE_APPS -> {
                    try {
                        result.success(installedAppsForVpnPicker())
                    } catch (e: Exception) {
                        Log.e(TAG, "listVpnCandidateApps failed", e)
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
                        Log.w(TAG, "getAppIcon failed for $pkg: ${e.message}")
                        result.success(null)
                    }
                }
                VpnContract.PREPARE_VPN -> {
                    Log.i(TAG, "vpn_call prepareVpn start")
                    val intent = VpnService.prepare(this)
                    if (intent == null) {
                        Log.i(TAG, "vpn_call prepareVpn result granted=true (already)")
                        result.success(true)
                    } else {
                        vpnPermissionResult = result
                        Log.i(TAG, "vpn_call prepareVpn result needs_ui=true")
                        startActivityForResult(intent, REQUEST_VPN_PERMISSION)
                    }
                }
                VpnContract.CONNECT -> {
                    val args = call.arguments as? Map<*, *>
                    if (args == null) {
                        Log.e(TAG, "vpn_call connect rejected bad_args (non-map)")
                        result.error("bad_args", "Expected map", null)
                        return@setMethodCallHandler
                    }
                    val attemptId = args[VpnContract.ARG_ATTEMPT_ID] as? String ?: ""
                    val server = args[VpnContract.ARG_SERVER] as? String
                    if (server.isNullOrBlank()) {
                        Log.e(TAG, "vpn_call connect rejected bad_args (server required) attempt=$attemptId")
                        result.error("bad_args", "server required", null)
                        return@setMethodCallHandler
                    }
                    val user = args[VpnContract.ARG_USER] as? String ?: ""
                    val password = args[VpnContract.ARG_PASSWORD] as? String ?: ""
                    val psk = args[VpnContract.ARG_PSK] as? String ?: ""
                    val dns = args[VpnContract.ARG_DNS] as? String ?: "8.8.8.8"
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
                    val routingModeRaw = args[VpnContract.ARG_ROUTING_MODE] as? String
                    val routingMode =
                        if (routingModeRaw == VpnContract.ROUTING_PER_APP_ALLOW_LIST) {
                            VpnContract.ROUTING_PER_APP_ALLOW_LIST
                        } else {
                            VpnContract.ROUTING_FULL_TUNNEL
                        }
                    val allowedPackages = mutableListOf<String>()
                    val apRaw = args[VpnContract.ARG_ALLOWED_PACKAGES]
                    if (apRaw is List<*>) {
                        for (e in apRaw) {
                            val s = e as? String
                            if (!s.isNullOrBlank()) allowedPackages.add(s.trim())
                        }
                    }
                    val proxyHttpEnabled = args[VpnContract.ARG_PROXY_HTTP_ENABLED] as? Boolean ?: true
                    val proxyHttpPort = sanitizePort(args[VpnContract.ARG_PROXY_HTTP_PORT], ProxyTunnelService.DEFAULT_HTTP_PORT)
                    val proxySocksEnabled = args[VpnContract.ARG_PROXY_SOCKS_ENABLED] as? Boolean ?: true
                    val proxySocksPort = sanitizePort(args[VpnContract.ARG_PROXY_SOCKS_PORT], ProxyTunnelService.DEFAULT_SOCKS_PORT)
                    val serverTrim = server.trim()
                    val hasScheme = serverTrim.contains("://")
                    val hasPort = serverTrim.count { it == ':' } == 1 && !serverTrim.contains("::")
                    if (serverTrim != server) {
                        Log.w(TAG, "vpn_call connect server had leading/trailing whitespace attempt=$attemptId")
                    }
                    if (hasScheme) {
                        Log.w(TAG, "vpn_call connect server looks like URL; expected host only attempt=$attemptId server=$serverTrim")
                    }
                    if (hasPort) {
                        Log.w(TAG, "vpn_call connect server includes port; expected host only attempt=$attemptId server=$serverTrim")
                    }
                    if (connectionMode == VpnContract.MODE_VPN_TUNNEL) {
                        val prep = VpnService.prepare(this)
                        if (prep != null) {
                            Log.e(TAG, "vpn_call connect rejected vpn_permission attempt=$attemptId")
                            result.error("vpn_permission", "VPN permission not granted", null)
                            return@setMethodCallHandler
                        }
                    }
                    Log.i(
                        TAG,
                        "vpn_call connect start attempt=$attemptId server=$serverTrim userPresent=${user.isNotEmpty()} pskPresent=${psk.isNotEmpty()} dns=$dns mtu=$mtu mode=$connectionMode routing=$routingMode allowedApps=${allowedPackages.size}",
                    )
                    val intent =
                        if (connectionMode == VpnContract.MODE_PROXY_ONLY) {
                            Intent(this, ProxyTunnelService::class.java).apply {
                                action = ProxyTunnelService.ACTION_START
                                putExtra(ProxyTunnelService.EXTRA_ATTEMPT_ID, attemptId)
                                putExtra(ProxyTunnelService.EXTRA_SERVER, serverTrim)
                                putExtra(ProxyTunnelService.EXTRA_USER, user)
                                putExtra(ProxyTunnelService.EXTRA_PASSWORD, password)
                                putExtra(ProxyTunnelService.EXTRA_PSK, psk)
                                putExtra(ProxyTunnelService.EXTRA_DNS, dns)
                                putExtra(ProxyTunnelService.EXTRA_MTU, mtu)
                                putExtra(ProxyTunnelService.EXTRA_PROFILE_NAME, profileName)
                                putExtra(ProxyTunnelService.EXTRA_PROXY_HTTP_ENABLED, proxyHttpEnabled)
                                putExtra(ProxyTunnelService.EXTRA_PROXY_HTTP_PORT, proxyHttpPort)
                                putExtra(ProxyTunnelService.EXTRA_PROXY_SOCKS_ENABLED, proxySocksEnabled)
                                putExtra(ProxyTunnelService.EXTRA_PROXY_SOCKS_PORT, proxySocksPort)
                            }
                        } else {
                            Intent(this, TunnelVpnService::class.java).apply {
                                action = TunnelVpnService.ACTION_START
                                putExtra(TunnelVpnService.EXTRA_ATTEMPT_ID, attemptId)
                                putExtra(TunnelVpnService.EXTRA_SERVER, serverTrim)
                                putExtra(TunnelVpnService.EXTRA_USER, user)
                                putExtra(TunnelVpnService.EXTRA_PASSWORD, password)
                                putExtra(TunnelVpnService.EXTRA_PSK, psk)
                                putExtra(TunnelVpnService.EXTRA_DNS, dns)
                                putExtra(TunnelVpnService.EXTRA_MTU, mtu)
                                putExtra(TunnelVpnService.EXTRA_PROFILE_NAME, profileName)
                                putExtra(TunnelVpnService.EXTRA_ROUTING_MODE, routingMode)
                                putStringArrayListExtra(
                                    TunnelVpnService.EXTRA_ALLOWED_PACKAGES,
                                    ArrayList(allowedPackages),
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
                        Log.i(TAG, "vpn_call connect requesting POST_NOTIFICATIONS attempt=$attemptId")
                        requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_POST_NOTIFICATIONS,
                        )
                        return@setMethodCallHandler
                    }
                    startForegroundWorkerService(intent)
                    Log.i(TAG, "vpn_call connect dispatched action=ACTION_START attempt=$attemptId")
                    result.success(null)
                }
                VpnContract.DISCONNECT -> {
                    Log.i(TAG, "vpn_call disconnect dispatched action=ACTION_STOP")
                    val intent = Intent(this, TunnelVpnService::class.java).apply {
                        action = TunnelVpnService.ACTION_STOP
                    }
                    startService(intent)
                    startService(
                        Intent(this, ProxyTunnelService::class.java).apply {
                            action = ProxyTunnelService.ACTION_STOP
                        },
                    )
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onDestroy() {
        VpnTunnelEvents.detach()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_PERMISSION) return
        val pending = vpnPermissionResult ?: return
        vpnPermissionResult = null
        Log.i(TAG, "vpn_call prepareVpn result_code=$resultCode")
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
            Log.w(TAG, "vpn_call connect rejected notification_permission")
            pending.error("notification_permission", "Notification permission is required to show VPN status", null)
            return
        }
        if (connectIntent == null) {
            pending.error("internal", "Missing pending VPN connect intent", null)
            return
        }
        startForegroundWorkerService(connectIntent)
        Log.i(TAG, "vpn_call connect dispatched action=ACTION_START after notification grant")
        pending.success(null)
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

    private fun hasPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_VPN_PERMISSION = 0x4E50
        private const val REQUEST_POST_NOTIFICATIONS = 0x4E51
    }
}
