package com.example.tunnel_forge

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyPacketBridgeTest {

    @Test
    fun waitUntilActiveReturnsTrueWhenBackendActivates() {
        val active = AtomicBoolean(false)
        val backend =
            object : ProxyPacketBridgeBackend {
                override fun isActive(): Boolean = active.get()

                override fun queueOutboundPacket(packet: ByteArray): Int = 0

                override fun readInboundPacket(maxLen: Int): ByteArray? = null
            }
        Thread {
            Thread.sleep(80)
            active.set(true)
        }.start()

        val bridge = ProxyPacketBridge(backend = backend)

        assertTrue(bridge.waitUntilActive(timeoutMs = 500, pollIntervalMs = 20))
    }

    @Test
    fun queueOutboundPacketRequiresRunningBridge() {
        val backend =
            object : ProxyPacketBridgeBackend {
                override fun isActive(): Boolean = false

                override fun queueOutboundPacket(packet: ByteArray): Int = 0

                override fun readInboundPacket(maxLen: Int): ByteArray? = null
            }

        val bridge = ProxyPacketBridge(backend = backend)

        assertFalse(bridge.queueOutboundPacket(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun inboundPumpDeliversPackets() {
        val active = AtomicBoolean(true)
        val packets = ArrayDeque<ByteArray>().apply {
            add(byteArrayOf(1, 2, 3))
            add(byteArrayOf(4, 5))
        }
        val backend =
            object : ProxyPacketBridgeBackend {
                override fun isActive(): Boolean = active.get()

                override fun queueOutboundPacket(packet: ByteArray): Int = 0

                override fun readInboundPacket(maxLen: Int): ByteArray? = if (packets.isEmpty()) null else packets.removeFirst()
            }

        val delivered = CopyOnWriteArrayList<Int>()
        val bridge = ProxyPacketBridge(backend = backend, idleDelayMs = 5)
        assertTrue(bridge.waitUntilActive(timeoutMs = 100, pollIntervalMs = 5))

        val pump = bridge.startInboundPump(
            threadName = "test-pump",
            onPacket = { delivered.add(it.size) },
            onError = { throw AssertionError(it) },
        )

        Thread.sleep(40)
        bridge.stop()
        pump.interrupt()
        pump.join(500)

        assertTrue(delivered.contains(3))
        assertTrue(delivered.contains(2))
    }
}
