package io.github.evokelektrique.tunnelforge

import android.util.Log

internal data class NativeTunnelOwner(
    val mode: String,
    val attemptId: String,
)

internal class NativeTunnelSessionCoordinator(
    private val stopNativeTunnel: () -> Unit,
    private val logger: (Int, String) -> Unit = { level, message ->
        VpnTunnelEvents.emitEngineLog(level, TAG, message)
    },
) {
    private val lock = Object()
    private var owner: NativeTunnelOwner? = null
    // Native stop runs outside the monitor, so this blocks a newer owner from
    // acquiring while a stop for the previously observed owner is still in flight.
    private var stoppingOwner: NativeTunnelOwner? = null

    fun acquire(nextOwner: NativeTunnelOwner, reason: String, waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS): Boolean {
        var ownerToStop: NativeTunnelOwner? = null
        synchronized(lock) {
            val current = owner
            if ((current == null && stoppingOwner == null) || current == nextOwner) {
                owner = nextOwner
                logger(Log.DEBUG, "native owner acquired mode=${nextOwner.mode} attempt=${nextOwner.attemptId} reason=$reason")
                return true
            }
            if (current != null && stoppingOwner == null) {
                stoppingOwner = current
                ownerToStop = current
                logger(
                    Log.WARN,
                    "native owner busy currentMode=${current.mode} currentAttempt=${current.attemptId} nextMode=${nextOwner.mode} nextAttempt=${nextOwner.attemptId} reason=$reason",
                )
            }
        }
        if (ownerToStop != null) {
            var shouldStopPrevious = false
            synchronized(lock) {
                if (owner == ownerToStop && stoppingOwner == ownerToStop) {
                    shouldStopPrevious = true
                } else if (stoppingOwner == ownerToStop) {
                    stoppingOwner = null
                    lock.notifyAll()
                }
            }
            if (shouldStopPrevious) {
                try {
                    stopNativeTunnel()
                } finally {
                    synchronized(lock) {
                        if (stoppingOwner == ownerToStop) {
                            stoppingOwner = null
                            lock.notifyAll()
                        }
                    }
                }
            }
        }
        val deadline = System.currentTimeMillis() + waitTimeoutMs.coerceAtLeast(1L)
        synchronized(lock) {
            while ((owner != null && owner != nextOwner) || stoppingOwner != null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    val current = owner
                    val stopping = stoppingOwner
                    logger(
                        Log.ERROR,
                        "native owner acquire timed out currentMode=${current?.mode.orEmpty()} currentAttempt=${current?.attemptId.orEmpty()} stoppingMode=${stopping?.mode.orEmpty()} stoppingAttempt=${stopping?.attemptId.orEmpty()} nextMode=${nextOwner.mode} nextAttempt=${nextOwner.attemptId} reason=$reason",
                    )
                    return false
                }
                try {
                    lock.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger(Log.WARN, "native owner acquire interrupted mode=${nextOwner.mode} attempt=${nextOwner.attemptId}")
                    return false
                }
            }
            owner = nextOwner
            logger(Log.DEBUG, "native owner acquired mode=${nextOwner.mode} attempt=${nextOwner.attemptId} reason=$reason")
            return true
        }
    }

    fun stopOwner(expectedOwner: NativeTunnelOwner?, reason: String): Boolean {
        val current =
            synchronized(lock) {
                owner
            } ?: return false
        if (expectedOwner != null && current != expectedOwner) {
            logger(
                Log.DEBUG,
                "native owner stop ignored requestedMode=${expectedOwner.mode} requestedAttempt=${expectedOwner.attemptId} currentMode=${current.mode} currentAttempt=${current.attemptId} reason=$reason",
            )
            return false
        }
        logger(Log.DEBUG, "native owner stop mode=${current.mode} attempt=${current.attemptId} reason=$reason")
        stopNativeTunnel()
        return true
    }

    fun release(expectedOwner: NativeTunnelOwner, reason: String): Boolean {
        synchronized(lock) {
            val current = owner
            if (current != expectedOwner) {
                logger(
                    Log.DEBUG,
                    "native owner release ignored requestedMode=${expectedOwner.mode} requestedAttempt=${expectedOwner.attemptId} currentMode=${current?.mode.orEmpty()} currentAttempt=${current?.attemptId.orEmpty()} reason=$reason",
                )
                return false
            }
            owner = null
            lock.notifyAll()
        }
        logger(Log.DEBUG, "native owner released mode=${expectedOwner.mode} attempt=${expectedOwner.attemptId} reason=$reason")
        return true
    }

    fun currentOwner(): NativeTunnelOwner? =
        synchronized(lock) {
            owner
        }

    companion object {
        private const val TAG = "NativeTunnelSession"
        internal const val DEFAULT_WAIT_TIMEOUT_MS = 12_000L
    }
}

internal object NativeTunnelSessions {
    val shared =
        NativeTunnelSessionCoordinator(
            stopNativeTunnel = {
                try {
                    VpnBridge.nativeStopTunnel()
                } catch (e: Exception) {
                    AppLog.w("NativeTunnelSession", "nativeStopTunnel", e)
                }
            },
        )
}
