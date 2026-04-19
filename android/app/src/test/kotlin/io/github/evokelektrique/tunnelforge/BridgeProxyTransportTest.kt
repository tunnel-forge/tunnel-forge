package io.github.evokelektrique.tunnelforge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProxyTransportTest {

    @Test
    fun openTcpSessionRequiresReadyStack() {
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = InactiveBackend()), logger = { _, _ -> })
        val transport = BridgeProxyTransport(stack)

        try {
            transport.openTcpSession(ProxyConnectRequest(host = "example.com", port = 443, protocol = "http-connect"))
            throw AssertionError("Expected inactive stack failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("not active"))
        }
    }

    @Test
    fun openTcpSessionTracksSessionLifecycle() {
        val bridge = ProxyPacketBridge(backend = AlwaysActiveBackend())
        val stack = BridgeUserspaceTunnelStack(bridge = bridge, logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 10, pollIntervalMs = 1))
        val transport = BridgeProxyTransport(stack)

        val session = transport.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))

        assertEquals(1, stack.activeSessions().size)
        assertEquals("93.184.216.34", stack.activeSessions().first().request.host)
        try {
            session.pumpBidirectional(
                ProxyClientConnection(
                    socket = Socket(),
                    input = ByteArrayInputStream(byteArrayOf()),
                    output = ByteArrayOutputStream(),
                ),
            )
            throw AssertionError("Expected bridge-backed transport stub failure")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().isNotEmpty())
        } finally {
            session.close()
            stack.stop()
        }
        assertTrue(stack.activeSessions().isEmpty())
    }

    private class InactiveBackend : ProxyPacketBridgeBackend {
        override fun isActive(): Boolean = false

        override fun queueOutboundPacket(packet: ByteArray): Int = -1

        override fun readInboundPacket(maxLen: Int): ByteArray? = null
    }

    private class AlwaysActiveBackend : ProxyPacketBridgeBackend {
        override fun isActive(): Boolean = true

        override fun queueOutboundPacket(packet: ByteArray): Int = 0

        override fun readInboundPacket(maxLen: Int): ByteArray? = null
    }
}
