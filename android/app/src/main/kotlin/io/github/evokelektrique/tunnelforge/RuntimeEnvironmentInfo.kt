package io.github.evokelektrique.tunnelforge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

internal object RuntimeEnvironmentInfo {
    fun emit(context: Context, tag: String, attemptPrefix: String, mode: String) {
        val packageInfo =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
            } catch (_: Exception) {
                null
            }
        val buildNumber =
            packageInfo?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    it.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    it.versionCode.toString()
                }
            }.orEmpty()
        VpnTunnelEvents.emitEngineLog(
            Log.INFO,
            tag,
            attemptPrefix + format(
                mode = mode,
                packageName = context.packageName,
                versionName = packageInfo?.versionName?.trim().orEmpty(),
                buildNumber = buildNumber,
                androidSdk = Build.VERSION.SDK_INT,
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                supportedAbis = Build.SUPPORTED_ABIS.joinToString(","),
            ),
        )
    }

    internal fun format(
        mode: String,
        packageName: String,
        versionName: String,
        buildNumber: String,
        androidSdk: Int,
        androidRelease: String,
        manufacturer: String,
        model: String,
        device: String,
        supportedAbis: String,
    ): String =
        "runtime info mode=${mode.ifBlank { "unknown" }} " +
            "appVersion=${versionName.ifBlank { "unknown" }} buildNumber=${buildNumber.ifBlank { "unknown" }} " +
            "package=${packageName.ifBlank { "unknown" }} androidSdk=$androidSdk androidRelease=${androidRelease.ifBlank { "unknown" }} " +
            "manufacturer=${manufacturer.ifBlank { "unknown" }} model=${model.ifBlank { "unknown" }} " +
            "device=${device.ifBlank { "unknown" }} supportedAbis=${supportedAbis.ifBlank { "unknown" }}"
}
