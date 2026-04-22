package io.github.evokelektrique.tunnelforge

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectSocketProxyTransportTest {

    @Test
    fun awaitConnectedRejectsIpv6OnlyResolution() {
        val transport =
            DirectSocketProxyTransport(
                resolver = { listOf(InetAddress.getByName("::1")) },
            )
        val session =
            transport.openTcpSession(
                ProxyConnectRequest(host = "ipv6.example", port = 443, protocol = "http-connect"),
            )

        try {
            session.awaitConnected(1_000)
            throw AssertionError("Expected IPv6-only resolution to fail")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("IPv6"))
        } finally {
            session.close()
        }
    }

    @Test
    fun pumpBidirectionalRelaysClientTraffic() {
        ServerSocket(0).use { remoteServer ->
            val remoteThread =
                Thread {
                    remoteServer.accept().use { socket ->
                        val request = socket.getInputStream().readNBytes(4)
                        socket.getOutputStream().write(request)
                        socket.getOutputStream().flush()
                    }
                }.also { it.start() }

            ServerSocket(0).use { localPair ->
                val localClient = Socket("127.0.0.1", localPair.localPort)
                val localAccepted = localPair.accept()
                localClient.soTimeout = 1_000

                val transport =
                    DirectSocketProxyTransport(
                        resolver = { listOf(InetAddress.getByName("127.0.0.1")) },
                    )
                val session =
                    transport.openTcpSession(
                        ProxyConnectRequest(
                            host = "echo.test",
                            port = remoteServer.localPort,
                            protocol = "http-connect",
                        ),
                    )
                session.awaitConnected(1_000)

                val pumpThread =
                    Thread {
                        session.use {
                            it.pumpBidirectional(
                                ProxyClientConnection(
                                    socket = localAccepted,
                                    input = localAccepted.getInputStream(),
                                    output = localAccepted.getOutputStream(),
                                ),
                            )
                        }
                    }.also { it.start() }

                localClient.getOutputStream().write("ping".toByteArray(StandardCharsets.US_ASCII))
                localClient.getOutputStream().flush()
                val echoed = localClient.getInputStream().readNBytes(4)
                assertEquals("ping", echoed.toString(StandardCharsets.US_ASCII))

                localClient.close()
                pumpThread.join(2_000)
                remoteThread.join(2_000)
                assertTrue(!pumpThread.isAlive)
            }
        }
    }
}
