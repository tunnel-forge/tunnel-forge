package com.example.tunnel_forge

import android.util.Log

interface ProxyPacketBridgeBackend {
    fun isActive(): Boolean

    fun queueOutboundPacket(packet: ByteArray): Int

    fun readInboundPacket(maxLen: Int): ByteArray?
}

object VpnBridgeProxyPacketBackend : ProxyPacketBridgeBackend {
    override fun isActive(): Boolean = VpnBridge.nativeIsProxyPacketBridgeActive()

    override fun queueOutboundPacket(packet: ByteArray): Int = VpnBridge.nativeQueueProxyOutboundPacket(packet)

    override fun readInboundPacket(maxLen: Int): ByteArray? = VpnBridge.nativeReadProxyInboundPacket(maxLen)
}

/**
 * Small Android-side wrapper around the native proxy packet bridge.
 *
 * This keeps JNI access in one place so a future userspace TCP/IP stack or local HTTP/SOCKS5
 * listeners can consume packets without reaching into [VpnBridge] directly.
 */
class ProxyPacketBridge(
    private val backend: ProxyPacketBridgeBackend = VpnBridgeProxyPacketBackend,
    private val maxPacketLen: Int = MAX_PACKET_LEN,
    private val idleDelayMs: Long = IDLE_DELAY_MS,
) {
    @Volatile
    private var running = false

    fun waitUntilActive(timeoutMs: Long = DEFAULT_READY_TIMEOUT_MS, pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (backend.isActive()) {
                running = true
                return true
            }
            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        if (backend.isActive()) {
            running = true
            return true
        }
        return false
    }

    fun queueOutboundPacket(packet: ByteArray): Boolean {
        if (!running || packet.isEmpty()) return false
        return backend.queueOutboundPacket(packet) == 0
    }

    fun isRunning(): Boolean = running && backend.isActive()

    fun startInboundPump(
        threadName: String,
        onPacket: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ): Thread {
        running = true
        return Thread(
            {
                while (running && !Thread.currentThread().isInterrupted) {
                    try {
                        val packet = backend.readInboundPacket(maxPacketLen)
                        if (packet == null) {
                            Thread.sleep(idleDelayMs)
                            continue
                        }
                        if (packet.isNotEmpty()) {
                            onPacket(packet)
                        }
                    } catch (t: Throwable) {
                        if (!running || Thread.currentThread().isInterrupted) break
                        onError(t)
                        try {
                            Thread.sleep(idleDelayMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            },
            threadName,
        ).also { it.start() }
    }

    fun stop() {
        running = false
    }

    companion object {
        private const val MAX_PACKET_LEN = 65535
        private const val IDLE_DELAY_MS = 50L
        private const val DEFAULT_POLL_INTERVAL_MS = 100L
        private const val DEFAULT_READY_TIMEOUT_MS = 4000L
        private const val TAG = "ProxyPacketBridge"

        fun logDroppedInboundPacket(length: Int) {
            Log.d(TAG, "Dropped inbound proxy bridge packet len=$length because no userspace stack is attached")
        }
    }
}
