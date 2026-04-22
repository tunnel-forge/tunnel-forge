package io.github.evokelektrique.tunnelforge

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import io.flutter.plugin.common.MethodChannel

/** Pushes tunnel lifecycle updates to Flutter on the main thread (same [MethodChannel] as [MainActivity]). */
object VpnTunnelEvents {
    @Volatile
    private var channel: MethodChannel? = null

    private val noopResult =
        object : MethodChannel.Result {
            override fun success(result: Any?) {}

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {}

            override fun notImplemented() {}
        }

    fun attach(c: MethodChannel) {
        channel = c
    }

    fun detach() {
        channel = null
    }

    private fun invokeOnMain(method: String, payload: Map<String, Any?>) {
        val ch =
            channel
                ?: run {
                    Log.w(TAG, sanitizeLogMessage("event_drop reason=no_channel method=$method"))
                    return
                }
        val job =
            Runnable {
                try {
                    ch.invokeMethod(method, payload, noopResult)
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        sanitizeLogMessage(
                            "event_drop reason=invoke_exception method=$method err=${e.javaClass.simpleName}:${e.message}",
                        ),
                    )
                }
            }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            job.run()
        } else {
            Handler(Looper.getMainLooper()).post(job)
        }
    }

    fun emit(state: String, detail: String?, attemptId: String? = null) {
        invokeOnMain(
            VpnContract.ON_TUNNEL_STATE,
            mapOf(
                VpnContract.ARG_ATTEMPT_ID to (attemptId ?: ""),
                VpnContract.ARG_TUNNEL_STATE to state,
                VpnContract.ARG_TUNNEL_DETAIL to (detail ?: ""),
            ),
        )
    }

    fun emitProxyExposureChanged(exposure: ProxyExposureInfo) {
        invokeOnMain(
            VpnContract.ON_PROXY_EXPOSURE_CHANGED,
            mapOf(
                VpnContract.ARG_PROXY_EXPOSURE_ACTIVE to exposure.active,
                VpnContract.ARG_PROXY_EXPOSURE_BIND_ADDRESS to exposure.bindAddress,
                VpnContract.ARG_PROXY_EXPOSURE_DISPLAY_ADDRESS to exposure.displayAddress,
                VpnContract.ARG_PROXY_EXPOSURE_HTTP_PORT to exposure.httpPort,
                VpnContract.ARG_PROXY_EXPOSURE_SOCKS_PORT to exposure.socksPort,
                VpnContract.ARG_PROXY_EXPOSURE_LAN_REQUESTED to exposure.lanRequested,
                VpnContract.ARG_PROXY_EXPOSURE_LAN_ACTIVE to exposure.lanActive,
                VpnContract.ARG_PROXY_EXPOSURE_WARNING to exposure.warning,
            ),
        )
    }

    /** Kotlin host: same payload shape as JNI [emitEngineLogFromNative]. */
    fun emitEngineLog(
        priority: Int,
        tag: String,
        message: String,
        source: String = VpnContract.LOG_SOURCE_KOTLIN,
    ) {
        val sanitizedMessage = sanitizeLogMessage(message)
        if (!shouldForwardEngineLog(priority)) return
        invokeOnMain(
            VpnContract.ON_ENGINE_LOG,
            mapOf(
                VpnContract.ARG_ENGINE_LOG_LEVEL to priority,
                VpnContract.ARG_ENGINE_LOG_SOURCE to source,
                VpnContract.ARG_ENGINE_LOG_TAG to tag,
                VpnContract.ARG_ENGINE_LOG_MESSAGE to sanitizedMessage,
            ),
        )
    }

    @Keep
    @JvmStatic
    fun emitEngineLogFromNative(priority: Int, tag: String, message: String) {
        emitEngineLog(priority, tag, message, source = VpnContract.LOG_SOURCE_NATIVE)
    }

    internal fun shouldForwardEngineLog(priority: Int): Boolean =
        EngineLogPolicy.shouldForward(priority)

    private const val TAG = "VpnTunnelEvents"
}
