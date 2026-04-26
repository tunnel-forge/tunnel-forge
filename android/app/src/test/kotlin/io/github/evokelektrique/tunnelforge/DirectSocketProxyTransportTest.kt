package io.github.evokelektrique.tunnelforge

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun pumpBidirectionalPreservesRemoteResponseAfterClientHalfClose() {
        ServerSocket(0).use { remoteServer ->
            val remoteThread =
                Thread {
                    remoteServer.accept().use { socket ->
                        socket.soTimeout = 1_000
                        val request = socket.getInputStream().readNBytes(4)
                        assertEquals("ping", request.toString(StandardCharsets.US_ASCII))
                        val eof = socket.getInputStream().read()
                        assertEquals(-1, eof)
                        socket.getOutputStream().write("pong".toByteArray(StandardCharsets.US_ASCII))
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
                localClient.shutdownOutput()
                val response = localClient.getInputStream().readNBytes(4)
                assertEquals("pong", response.toString(StandardCharsets.US_ASCII))

                localClient.close()
                pumpThread.join(2_000)
                remoteThread.join(2_000)
                assertFalse(pumpThread.isAlive)
            }
        }
    }

    @Test
    fun udpAssociationRelaysDatagrams() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { remote ->
            val remoteThread =
                Thread {
                    val buffer = ByteArray(64)
                    val packet = DatagramPacket(buffer, buffer.size)
                    remote.receive(packet)
                    remote.send(
                        DatagramPacket(
                            packet.data,
                            packet.length,
                            packet.address,
                            packet.port,
                        ),
                    )
                }.also { it.start() }

            val transport =
                DirectSocketProxyTransport(
                    resolver = { listOf(InetAddress.getByName("127.0.0.1")) },
                )
            val association =
                transport.openUdpAssociation(
                    ProxyConnectRequest(
                        host = "echo.test",
                        port = 0,
                        protocol = "socks5-udp",
                    ),
                )

            association.use {
                it.send(
                    ProxyUdpDatagram(
                        host = "echo.test",
                        port = remote.localPort,
                        payload = "ping".toByteArray(StandardCharsets.US_ASCII),
                    ),
                )
                val received = it.receive(1_000)
                assertEquals("ping", received!!.payload.toString(StandardCharsets.US_ASCII))
                assertEquals(remote.localPort, received.port)
            }
            remoteThread.join(2_000)
            assertTrue(!remoteThread.isAlive)
        }
    }

    @Test
    fun udpAssociationIgnoresDatagramsFromUnsentRemote() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { remote ->
            val remoteThread =
                Thread {
                    val buffer = ByteArray(64)
                    val packet = DatagramPacket(buffer, buffer.size)
                    remote.receive(packet)
                    DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { rogue ->
                        val roguePayload = "rogue".toByteArray(StandardCharsets.US_ASCII)
                        rogue.send(
                            DatagramPacket(
                                roguePayload,
                                roguePayload.size,
                                packet.address,
                                packet.port,
                            ),
                        )
                    }
                    remote.send(
                        DatagramPacket(
                            packet.data,
                            packet.length,
                            packet.address,
                            packet.port,
                        ),
                    )
                }.also { it.start() }

            val transport =
                DirectSocketProxyTransport(
                    resolver = { listOf(InetAddress.getByName("127.0.0.1")) },
                )
            val association =
                transport.openUdpAssociation(
                    ProxyConnectRequest(
                        host = "echo.test",
                        port = 0,
                        protocol = "socks5-udp",
                    ),
                )

            association.use {
                it.send(
                    ProxyUdpDatagram(
                        host = "echo.test",
                        port = remote.localPort,
                        payload = "ping".toByteArray(StandardCharsets.US_ASCII),
                    ),
                )
                val received = it.receive(1_000)
                assertEquals("ping", received!!.payload.toString(StandardCharsets.US_ASCII))
                assertEquals(remote.localPort, received.port)
            }
            remoteThread.join(2_000)
            assertTrue(!remoteThread.isAlive)
        }
    }

    @Test
    fun udpAssociationSendFailureOnlyRemovesNewlyRecordedRemote() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { remote ->
            val transport =
                DirectSocketProxyTransport(
                    resolver = { listOf(InetAddress.getByName("127.0.0.1")) },
                )
            val association =
                transport.openUdpAssociation(
                    ProxyConnectRequest(
                        host = "echo.test",
                        port = 0,
                        protocol = "socks5-udp",
                    ),
                )

            association.send(
                ProxyUdpDatagram(
                    host = "echo.test",
                    port = remote.localPort,
                    payload = "ping".toByteArray(StandardCharsets.US_ASCII),
                ),
            )
            val received = DatagramPacket(ByteArray(64), 64)
            remote.receive(received)

            val remotes = recordedUdpRemotes(association)
            val existingRemote = InetSocketAddress(InetAddress.getByName("127.0.0.1"), remote.localPort)
            assertTrue(remotes.contains(existingRemote))

            directUdpSocket(association).close()
            try {
                association.send(
                    ProxyUdpDatagram(
                        host = "echo.test",
                        port = remote.localPort,
                        payload = "retry".toByteArray(StandardCharsets.US_ASCII),
                    ),
                )
                throw AssertionError("Expected closed UDP socket send to fail")
            } catch (_: IOException) {
            }
            assertTrue(remotes.contains(existingRemote))

            val newPort = if (remote.localPort == 65535) 65534 else remote.localPort + 1
            val newRemote = InetSocketAddress(InetAddress.getByName("127.0.0.1"), newPort)
            try {
                association.send(
                    ProxyUdpDatagram(
                        host = "echo.test",
                        port = newPort,
                        payload = "new".toByteArray(StandardCharsets.US_ASCII),
                    ),
                )
                throw AssertionError("Expected closed UDP socket send to fail")
            } catch (_: IOException) {
            }
            assertFalse(remotes.contains(newRemote))
            association.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun recordedUdpRemotes(association: ProxyUdpAssociation): MutableSet<InetSocketAddress> {
        val field = association.javaClass.getDeclaredField("remotes")
        field.isAccessible = true
        return field.get(association) as MutableSet<InetSocketAddress>
    }

    private fun directUdpSocket(association: ProxyUdpAssociation): DatagramSocket {
        val field = association.javaClass.getDeclaredField("socket")
        field.isAccessible = true
        return field.get(association) as DatagramSocket
    }
}
