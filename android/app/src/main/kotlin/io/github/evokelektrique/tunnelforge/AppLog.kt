package io.github.evokelektrique.tunnelforge

import android.util.Log

object AppLog {
    fun println(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.println(priority, tag, message)
            VpnTunnelEvents.emitEngineLog(priority, tag, message)
            return
        }

        logThrowable(priority, tag, message, throwable)
        VpnTunnelEvents.emitEngineLog(priority, tag, renderForwardedMessage(message, throwable))
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        println(Log.DEBUG, tag, message, throwable)
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        println(Log.INFO, tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        println(Log.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        println(Log.ERROR, tag, message, throwable)
    }

    internal fun renderForwardedMessage(message: String, throwable: Throwable?): String {
        if (throwable == null) return message
        val stackTrace = throwable.stackTraceToString().trimEnd()
        return if (message.isEmpty()) stackTrace else "$message\n$stackTrace"
    }

    private fun logThrowable(priority: Int, tag: String, message: String, throwable: Throwable) {
        when (priority) {
            Log.DEBUG -> Log.d(tag, message, throwable)
            Log.INFO -> Log.i(tag, message, throwable)
            Log.WARN -> Log.w(tag, message, throwable)
            Log.ERROR -> Log.e(tag, message, throwable)
            else -> Log.println(priority, tag, renderForwardedMessage(message, throwable))
        }
    }
}
