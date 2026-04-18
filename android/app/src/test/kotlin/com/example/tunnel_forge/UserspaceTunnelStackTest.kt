package com.example.tunnel_forge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserspaceTunnelStackTest {

    @Test
    fun waitUntilReadyStartsInboundPumpAndLogsPackets() {
        val backend = ActivePacketBackend(packet = byteArrayOf(1, 2, 3, 4))
        val logs = CopyOnWriteArrayList<String>()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend, idleDelayMs = 5),
                logger = { _, message -> logs.add(message) },
            )

        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))
        Thread.sleep(30)
        stack.stop()

        assertTrue(
            logs.any {
                it.contains("Observed inbound TCP packet") ||
                    it.contains("Dropped malformed TCP segment") ||
                    it.contains("Dropped non-IPv4 bridge packet")
            },
        )
    }

    @Test
    fun openTcpSessionAllocatesDescriptors() {
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = AlwaysActiveBackend()), logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))

        assertEquals(1, stack.activeSessions().size)
        assertEquals(1, session.descriptor.sessionId)
        session.close()
        stack.stop()
        assertTrue(stack.activeSessions().isEmpty())
    }

    @Test
    fun failedPumpUpdatesSessionState() {
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = AlwaysActiveBackend()),
                logger = { _, _ -> },
                connectTimeoutMs = 100,
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))

        try {
            session.pumpBidirectional(
                ProxyClientConnection(
                    socket = Socket(),
                    input = ByteArrayInputStream(byteArrayOf()),
                    output = ByteArrayOutputStream(),
                ),
            )
        } catch (_: Exception) {
        }

        val snapshot = stack.sessionSnapshots().single()
        assertEquals(UserspaceSessionState.failed, snapshot.state)
        assertTrue(snapshot.lastEvent.isNotEmpty())
        session.close()
        stack.stop()
    }

    @Test
    fun ipv4LiteralSessionQueuesSyntheticSyn() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))

        assertEquals(1, backend.outboundPackets.size)
        val ipv4 = IpPacketParser.parseIpv4(backend.outboundPackets.first())
        val tcp = IpPacketParser.parseTcp(backend.outboundPackets.first(), ipv4!!)
        assertEquals("10.0.0.2", ipv4.sourceIp)
        assertEquals("93.184.216.34", ipv4.destinationIp)
        assertEquals(443, tcp!!.destinationPort)
        assertEquals(TcpPacketBuilder.TCP_FLAG_SYN, tcp.flags)
        session.close()
        stack.stop()
    }

    @Test
    fun inboundSynAckQueuesFinalAckAndMarksSessionEstablished() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)

        val snapshot = stack.sessionSnapshots().single()
        assertEquals(UserspaceSessionState.established, snapshot.state)
        assertTrue(snapshot.lastEvent.contains("Queued TCP ACK for SYN/ACK"))
        assertEquals(2, backend.outboundPackets.size)
        val ackPacket = backend.outboundPackets.last()
        val ackIpv4 = IpPacketParser.parseIpv4(ackPacket)!!
        val ackTcp = IpPacketParser.parseTcp(ackPacket, ackIpv4)!!
        assertEquals(TcpPacketBuilder.TCP_FLAG_ACK, ackTcp.flags)
        assertEquals(101, ackTcp.acknowledgementNumber)

        session.close()
        stack.stop()
    }

    @Test
    fun awaitEstablishedBlocksUntilSynAckArrives() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        var failure: Throwable? = null
        val worker =
            Thread {
                try {
                    session.awaitEstablished(500)
                } catch (t: Throwable) {
                    failure = t
                }
            }
        worker.start()

        Thread.sleep(100)
        assertTrue(worker.isAlive)
        establishSession(stack, backend)

        worker.join(1000)
        assertFalse(worker.isAlive)
        assertNull(failure)

        session.close()
        stack.stop()
    }

    @Test
    fun syntheticSynAdvertisesMssFromConfiguredMtu() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                linkMtu = 600,
                logger = { _, _ -> },
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))

        val synPacket = backend.outboundPackets.first()
        val synIpv4 = IpPacketParser.parseIpv4(synPacket)!!
        val synTcp = IpPacketParser.parseTcp(synPacket, synIpv4)!!
        val optionsOffset = synIpv4.payloadOffset + 20
        assertEquals(44, synIpv4.totalLength)
        assertEquals(24, synTcp.headerLength)
        assertEquals(0x02, synPacket[optionsOffset].toInt() and 0xff)
        assertEquals(0x04, synPacket[optionsOffset + 1].toInt() and 0xff)
        assertEquals(560, ((synPacket[optionsOffset + 2].toInt() and 0xff) shl 8) or (synPacket[optionsOffset + 3].toInt() and 0xff))

        session.close()
        stack.stop()
    }

    @Test
    fun fastSynAckDuringSynQueueIsNotDropped() {
        lateinit var stack: BridgeUserspaceTunnelStack
        val backend =
            CapturingBackend(
                onQueue = { packet ->
                    if (outboundPackets.size == 1) {
                        val synIpv4 = IpPacketParser.parseIpv4(packet) ?: return@CapturingBackend
                        val synTcp = IpPacketParser.parseTcp(packet, synIpv4) ?: return@CapturingBackend
                        stack.processInboundPacketForTesting(
                            TcpPacketBuilder.buildIpv4TcpPacket(
                                sourceIp = "93.184.216.34",
                                destinationIp = "10.0.0.2",
                                sourcePort = 443,
                                destinationPort = synTcp.sourcePort,
                                sequenceNumber = 100,
                                acknowledgementNumber = synTcp.sequenceNumber + 1,
                                flags = TcpPacketBuilder.TCP_FLAG_SYN or TcpPacketBuilder.TCP_FLAG_ACK,
                            ),
                        )
                    }
                },
            )
        stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))

        assertEquals(2, backend.outboundPackets.size)
        val ackPacket = backend.outboundPackets.last()
        val ackIpv4 = IpPacketParser.parseIpv4(ackPacket)!!
        val ackTcp = IpPacketParser.parseTcp(ackPacket, ackIpv4)!!
        assertEquals(TcpPacketBuilder.TCP_FLAG_ACK, ackTcp.flags)
        assertEquals(UserspaceSessionState.established, stack.sessionSnapshots().single().state)

        session.close()
        stack.stop()
    }

    @Test
    fun activeSessionPumpsClientPayloadIntoOutboundPacket() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)

        ServerSocket(0).use { server ->
            val pumpThread =
                Thread {
                    server.accept().use { accepted ->
                        session.pumpBidirectional(accepted.asProxyClientConnection())
                    }
                }
            pumpThread.start()
            Socket("127.0.0.1", server.localPort).use { peer ->
                peer.getOutputStream().write("hello".toByteArray())
                peer.shutdownOutput()
            }
            pumpThread.join(1000)
        }

        assertEquals(4, backend.outboundPackets.size)
        val dataPacket = backend.outboundPackets[2]
        val dataIpv4 = IpPacketParser.parseIpv4(dataPacket)!!
        val dataTcp = IpPacketParser.parseTcp(dataPacket, dataIpv4)!!
        assertEquals(TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_PSH, dataTcp.flags)
        assertEquals(101, dataTcp.acknowledgementNumber)
        assertEquals(5, dataTcp.payloadLength)
        assertEquals("hello", dataPacket.copyOfRange(dataTcp.payloadOffset, dataTcp.payloadOffset + dataTcp.payloadLength).toString(Charsets.UTF_8))
        val finPacket = backend.outboundPackets.last()
        val finIpv4 = IpPacketParser.parseIpv4(finPacket)!!
        val finTcp = IpPacketParser.parseTcp(finPacket, finIpv4)!!
        assertEquals(TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_FIN, finTcp.flags)
        assertEquals(101, finTcp.acknowledgementNumber)

        session.close()
        stack.stop()
    }

    @Test
    fun inboundPayloadAfterLocalFinKeepsSessionMatchedUntilRemoteFin() {
        val backend = CapturingBackend()
        val logs = CopyOnWriteArrayList<String>()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, message -> logs.add(message) },
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)

        ServerSocket(0).use { server ->
            val pumpThread =
                Thread {
                    server.accept().use { accepted ->
                        session.pumpBidirectional(accepted.asProxyClientConnection())
                    }
                }
            pumpThread.start()
            Socket("127.0.0.1", server.localPort).use { peer ->
                peer.soTimeout = 500
                peer.getOutputStream().write("hello".toByteArray())
                peer.getOutputStream().flush()
                peer.shutdownOutput()

                waitForOutboundCount(backend, 4)
                val finPacket = backend.outboundPackets.last()
                val finIpv4 = IpPacketParser.parseIpv4(finPacket)!!
                val finTcp = IpPacketParser.parseTcp(finPacket, finIpv4)!!
                assertEquals(TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_FIN, finTcp.flags)

                val beforePayloadAck = backend.outboundPackets.size
                stack.processInboundPacketForTesting(
                    TcpPacketBuilder.buildIpv4TcpPacket(
                        sourceIp = "93.184.216.34",
                        destinationIp = "10.0.0.2",
                        sourcePort = 443,
                        destinationPort = sessionLocalPort(backend),
                        sequenceNumber = 101,
                        acknowledgementNumber = sessionInitialAck(backend) + 6,
                        flags = TcpPacketBuilder.TCP_FLAG_ACK,
                        payload = "world".toByteArray(),
                    ),
                )

                assertEquals("world", String(peer.getInputStream().readNBytes(5), Charsets.UTF_8))
                assertEquals(beforePayloadAck + 1, backend.outboundPackets.size)
                assertEquals(UserspaceSessionState.established, stack.sessionSnapshots().single().state)
                assertTrue(logs.none { it.contains("Dropped inbound TCP without session match") })

                stack.processInboundPacketForTesting(
                    TcpPacketBuilder.buildIpv4TcpPacket(
                        sourceIp = "93.184.216.34",
                        destinationIp = "10.0.0.2",
                        sourcePort = 443,
                        destinationPort = sessionLocalPort(backend),
                        sequenceNumber = 106,
                        acknowledgementNumber = sessionInitialAck(backend) + 6,
                        flags = TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_FIN,
                    ),
                )

                pumpThread.join(1000)
                assertFalse(pumpThread.isAlive)
                assertEquals(UserspaceSessionState.closed, stack.sessionSnapshots().single().state)
            }
        }

        session.close()
        stack.stop()
    }

    @Test
    fun duplicateInboundPayloadIsIgnoredAndReAcked() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)

        val payloadPacket =
            TcpPacketBuilder.buildIpv4TcpPacket(
                sourceIp = "93.184.216.34",
                destinationIp = "10.0.0.2",
                sourcePort = 443,
                destinationPort = sessionLocalPort(backend),
                sequenceNumber = 101,
                acknowledgementNumber = sessionInitialAck(backend),
                flags = TcpPacketBuilder.TCP_FLAG_ACK,
                payload = "hello".toByteArray(),
            )

        val received = CopyOnWriteArrayList<String>()
        ServerSocket(0).use { server ->
            val thread =
                Thread {
                    server.accept().use { accepted ->
                        session.pumpBidirectional(accepted.asProxyClientConnection())
                    }
                }
            thread.start()
            Socket("127.0.0.1", server.localPort).use { peer ->
                peer.soTimeout = 300
                Thread.sleep(50)
                stack.processInboundPacketForTesting(payloadPacket)
                received.add(String(peer.getInputStream().readNBytes(5), Charsets.UTF_8))
                val before = backend.outboundPackets.size
                stack.processInboundPacketForTesting(payloadPacket)
                assertEquals(before + 1, backend.outboundPackets.size)
                val reAckPacket = backend.outboundPackets.last()
                val reAckIpv4 = IpPacketParser.parseIpv4(reAckPacket)!!
                val reAckTcp = IpPacketParser.parseTcp(reAckPacket, reAckIpv4)!!
                assertEquals(TcpPacketBuilder.TCP_FLAG_ACK, reAckTcp.flags)
                assertEquals(106, reAckTcp.acknowledgementNumber)
                peer.shutdownOutput()
            }
            thread.join(1000)
        }

        assertEquals(listOf("hello"), received)
        session.close()
        stack.stop()
    }

    @Test
    fun outOfOrderInboundPayloadIsIgnoredAndReAcked() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)

        val outOfOrderPacket =
            TcpPacketBuilder.buildIpv4TcpPacket(
                sourceIp = "93.184.216.34",
                destinationIp = "10.0.0.2",
                sourcePort = 443,
                destinationPort = sessionLocalPort(backend),
                sequenceNumber = 106,
                acknowledgementNumber = sessionInitialAck(backend),
                flags = TcpPacketBuilder.TCP_FLAG_ACK,
                payload = "later".toByteArray(),
            )

        val before = backend.outboundPackets.size
        stack.processInboundPacketForTesting(outOfOrderPacket)

        assertEquals(before + 1, backend.outboundPackets.size)
        assertTrue(stack.sessionSnapshots().single().lastEvent.contains("Ignored out-of-order inbound TCP payload"))
        val reAckPacket = backend.outboundPackets.last()
        val reAckIpv4 = IpPacketParser.parseIpv4(reAckPacket)!!
        val reAckTcp = IpPacketParser.parseTcp(reAckPacket, reAckIpv4)!!
        assertEquals(TcpPacketBuilder.TCP_FLAG_ACK, reAckTcp.flags)
        assertEquals(101, reAckTcp.acknowledgementNumber)
        session.close()
        stack.stop()
    }

    @Test
    fun inboundPayloadAcknowledgesNextExpectedByte() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)

        val payloadPacket =
            TcpPacketBuilder.buildIpv4TcpPacket(
                sourceIp = "93.184.216.34",
                destinationIp = "10.0.0.2",
                sourcePort = 443,
                destinationPort = sessionLocalPort(backend),
                sequenceNumber = 101,
                acknowledgementNumber = sessionInitialAck(backend),
                flags = TcpPacketBuilder.TCP_FLAG_ACK,
                payload = "hello".toByteArray(),
            )

        ServerSocket(0).use { server ->
            val thread =
                Thread {
                    server.accept().use { accepted ->
                        session.pumpBidirectional(accepted.asProxyClientConnection())
                    }
                }
            thread.start()
            Socket("127.0.0.1", server.localPort).use { peer ->
                peer.soTimeout = 300
                Thread.sleep(50)
                val before = backend.outboundPackets.size
                stack.processInboundPacketForTesting(payloadPacket)
                assertEquals("hello", String(peer.getInputStream().readNBytes(5), Charsets.UTF_8))
                val ackPacket = backend.outboundPackets[before]
                val ackIpv4 = IpPacketParser.parseIpv4(ackPacket)!!
                val ackTcp = IpPacketParser.parseTcp(ackPacket, ackIpv4)!!
                assertEquals(TcpPacketBuilder.TCP_FLAG_ACK, ackTcp.flags)
                assertEquals(106, ackTcp.acknowledgementNumber)
                peer.shutdownOutput()
            }
            thread.join(1000)
        }
        session.close()
        stack.stop()
    }

    @Test
    fun inboundFinAcknowledgesNextExpectedByte() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)

        val finPacket =
            TcpPacketBuilder.buildIpv4TcpPacket(
                sourceIp = "93.184.216.34",
                destinationIp = "10.0.0.2",
                sourcePort = 443,
                destinationPort = sessionLocalPort(backend),
                sequenceNumber = 101,
                acknowledgementNumber = sessionInitialAck(backend),
                flags = TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_FIN,
            )

        stack.processInboundPacketForTesting(finPacket)

        val ackPacket = backend.outboundPackets.last()
        val ackIpv4 = IpPacketParser.parseIpv4(ackPacket)!!
        val ackTcp = IpPacketParser.parseTcp(ackPacket, ackIpv4)!!
        assertEquals(TcpPacketBuilder.TCP_FLAG_ACK, ackTcp.flags)
        assertEquals(102, ackTcp.acknowledgementNumber)
        session.close()
        stack.stop()
    }

    @Test
    fun interruptingAwaitEstablishedReturnsIoFailure() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        var failure: Throwable? = null
        val worker =
            Thread {
                try {
                    session.awaitEstablished(5_000)
                } catch (t: Throwable) {
                    failure = t
                }
            }
        worker.start()

        Thread.sleep(100)
        worker.interrupt()
        worker.join(1_000)

        assertFalse(worker.isAlive)
        assertTrue(failure is IOException)
        assertTrue((failure as IOException).message!!.contains("interrupted"))

        session.close()
        stack.stop()
    }

    @Test
    fun stoppingStackReleasesActiveSessionWaitingForRemoteClose() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)

        var failure: Throwable? = null
        ServerSocket(0).use { server ->
            val pumpThread =
                Thread {
                    try {
                        server.accept().use { accepted ->
                            session.pumpBidirectional(accepted.asProxyClientConnection())
                        }
                    } catch (t: Throwable) {
                        failure = t
                    }
                }
            pumpThread.start()
            Socket("127.0.0.1", server.localPort).use { peer ->
                peer.getOutputStream().write("hello".toByteArray())
                peer.getOutputStream().flush()
                peer.shutdownOutput()

                waitForOutboundCount(backend, 4)
                stack.stop()

                pumpThread.join(1_000)
                assertFalse(pumpThread.isAlive)
            }
        }

        assertTrue(failure is IOException)
        assertTrue((failure as IOException).message!!.contains("Proxy packet bridge stopped"))
        session.close()
    }

    @Test
    fun hostnameSessionResolvesThroughTunneledDnsBeforeQueuingSyn() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                dnsServers = listOf("1.1.1.1"),
                logger = { _, _ -> },
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val ready = CountDownLatch(1)
        var session: UserspaceTunnelSession? = null
        var failure: Throwable? = null
        val worker =
            Thread {
                ready.countDown()
                try {
                    session = stack.openTcpSession(ProxyConnectRequest(host = "example.com", port = 443, protocol = "http-connect"))
                } catch (t: Throwable) {
                    failure = t
                }
            }
        worker.start()
        ready.await()

        waitForOutboundCount(backend, 1)
        val dnsPacket = backend.outboundPackets.first()
        val dnsIpv4 = IpPacketParser.parseIpv4(dnsPacket)!!
        val dnsUdp = IpPacketParser.parseUdp(dnsPacket, dnsIpv4)!!
        assertEquals("10.0.0.2", dnsIpv4.sourceIp)
        assertEquals("1.1.1.1", dnsIpv4.destinationIp)
        assertEquals(53, dnsUdp.destinationPort)

        val dnsPayload = dnsPacket.copyOfRange(dnsUdp.payloadOffset, dnsUdp.payloadOffset + dnsUdp.payloadLength)
        stack.processInboundPacketForTesting(
            buildDnsResponsePacket(
                txId = readUint16(dnsPayload, 0),
                sourceIp = "1.1.1.1",
                destinationIp = "10.0.0.2",
                destinationPort = dnsUdp.sourcePort,
                hostname = "example.com",
                answerIp = "93.184.216.34",
            ),
        )

        worker.join(1000)
        assertNull(failure)
        waitForOutboundCount(backend, 2)
        val synPacket = backend.outboundPackets[1]
        val synIpv4 = IpPacketParser.parseIpv4(synPacket)!!
        val synTcp = IpPacketParser.parseTcp(synPacket, synIpv4)!!
        assertEquals("93.184.216.34", synIpv4.destinationIp)
        assertEquals(443, synTcp.destinationPort)
        assertEquals(TcpPacketBuilder.TCP_FLAG_SYN, synTcp.flags)

        session?.close()
        stack.stop()
    }

    @Test
    fun hostnameSessionFallsBackToSecondaryDnsAfterPrimaryQueueFailure() {
        val backend =
            CapturingBackend(
                queueResult = { _ ->
                    if (outboundPackets.size == 1) -1 else 0
                },
            )
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                dnsServers = listOf("1.1.1.1", "8.8.8.8"),
                logger = { _, _ -> },
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val ready = CountDownLatch(1)
        var session: UserspaceTunnelSession? = null
        var failure: Throwable? = null
        val worker =
            Thread {
                ready.countDown()
                try {
                    session = stack.openTcpSession(ProxyConnectRequest(host = "example.com", port = 443, protocol = "http-connect"))
                } catch (t: Throwable) {
                    failure = t
                }
            }
        worker.start()
        ready.await()

        waitForOutboundCount(backend, 2)
        val firstDnsPacket = backend.outboundPackets[0]
        val secondDnsPacket = backend.outboundPackets[1]
        val firstDnsIpv4 = IpPacketParser.parseIpv4(firstDnsPacket)!!
        val secondDnsIpv4 = IpPacketParser.parseIpv4(secondDnsPacket)!!
        val secondDnsUdp = IpPacketParser.parseUdp(secondDnsPacket, secondDnsIpv4)!!
        assertEquals("1.1.1.1", firstDnsIpv4.destinationIp)
        assertEquals("8.8.8.8", secondDnsIpv4.destinationIp)

        val secondDnsPayload =
            secondDnsPacket.copyOfRange(
                secondDnsUdp.payloadOffset,
                secondDnsUdp.payloadOffset + secondDnsUdp.payloadLength,
            )
        stack.processInboundPacketForTesting(
            buildDnsResponsePacket(
                txId = readUint16(secondDnsPayload, 0),
                sourceIp = "8.8.8.8",
                destinationIp = "10.0.0.2",
                destinationPort = secondDnsUdp.sourcePort,
                hostname = "example.com",
                answerIp = "93.184.216.34",
            ),
        )

        worker.join(1000)
        assertNull(failure)
        waitForOutboundCount(backend, 3)
        val synPacket = backend.outboundPackets[2]
        val synIpv4 = IpPacketParser.parseIpv4(synPacket)!!
        assertEquals("93.184.216.34", synIpv4.destinationIp)

        session?.close()
        stack.stop()
    }

    @Test
    fun hostnameSessionDoesNotFallbackAfterAuthoritativeDnsNegativeResponse() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                dnsServers = listOf("1.1.1.1", "8.8.8.8"),
                logger = { _, _ -> },
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        val worker =
            Thread {
                ready.countDown()
                try {
                    stack.openTcpSession(ProxyConnectRequest(host = "example.com", port = 443, protocol = "http-connect"))
                    throw AssertionError("Expected DNS negative response to fail the session")
                } catch (t: Throwable) {
                    failure = t
                }
            }
        worker.start()
        ready.await()

        waitForOutboundCount(backend, 1)
        val dnsPacket = backend.outboundPackets.first()
        val dnsIpv4 = IpPacketParser.parseIpv4(dnsPacket)!!
        val dnsUdp = IpPacketParser.parseUdp(dnsPacket, dnsIpv4)!!
        val dnsPayload = dnsPacket.copyOfRange(dnsUdp.payloadOffset, dnsUdp.payloadOffset + dnsUdp.payloadLength)
        stack.processInboundPacketForTesting(
            buildDnsNegativeResponsePacket(
                txId = readUint16(dnsPayload, 0),
                sourceIp = "1.1.1.1",
                destinationIp = "10.0.0.2",
                destinationPort = dnsUdp.sourcePort,
                hostname = "example.com",
                rcode = 3,
            ),
        )

        worker.join(1000)
        assertTrue(failure is IOException)
        assertTrue((failure as IOException).message!!.contains("DNS response code 3"))
        assertEquals(1, backend.outboundPackets.size)

        stack.stop()
    }

    @Test
    fun hostnameSessionFallsBackAfterServfailResponse() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                dnsServers = listOf("1.1.1.1", "8.8.8.8"),
                logger = { _, _ -> },
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val ready = CountDownLatch(1)
        var session: UserspaceTunnelSession? = null
        var failure: Throwable? = null
        val worker =
            Thread {
                ready.countDown()
                try {
                    session = stack.openTcpSession(ProxyConnectRequest(host = "example.com", port = 443, protocol = "http-connect"))
                } catch (t: Throwable) {
                    failure = t
                }
            }
        worker.start()
        ready.await()

        waitForOutboundCount(backend, 1)
        val firstDnsPacket = backend.outboundPackets[0]
        val firstDnsIpv4 = IpPacketParser.parseIpv4(firstDnsPacket)!!
        val firstDnsUdp = IpPacketParser.parseUdp(firstDnsPacket, firstDnsIpv4)!!
        val firstDnsPayload = firstDnsPacket.copyOfRange(firstDnsUdp.payloadOffset, firstDnsUdp.payloadOffset + firstDnsUdp.payloadLength)
        stack.processInboundPacketForTesting(
            buildDnsNegativeResponsePacket(
                txId = readUint16(firstDnsPayload, 0),
                sourceIp = "1.1.1.1",
                destinationIp = "10.0.0.2",
                destinationPort = firstDnsUdp.sourcePort,
                hostname = "example.com",
                rcode = 2,
            ),
        )

        waitForOutboundCount(backend, 2)
        val secondDnsPacket = backend.outboundPackets[1]
        val secondDnsIpv4 = IpPacketParser.parseIpv4(secondDnsPacket)!!
        val secondDnsUdp = IpPacketParser.parseUdp(secondDnsPacket, secondDnsIpv4)!!
        assertEquals("8.8.8.8", secondDnsIpv4.destinationIp)

        val secondDnsPayload =
            secondDnsPacket.copyOfRange(
                secondDnsUdp.payloadOffset,
                secondDnsUdp.payloadOffset + secondDnsUdp.payloadLength,
            )
        stack.processInboundPacketForTesting(
            buildDnsResponsePacket(
                txId = readUint16(secondDnsPayload, 0),
                sourceIp = "8.8.8.8",
                destinationIp = "10.0.0.2",
                destinationPort = secondDnsUdp.sourcePort,
                hostname = "example.com",
                answerIp = "93.184.216.34",
            ),
        )

        worker.join(1000)
        assertNull(failure)
        waitForOutboundCount(backend, 3)
        val synPacket = backend.outboundPackets[2]
        val synIpv4 = IpPacketParser.parseIpv4(synPacket)!!
        assertEquals("93.184.216.34", synIpv4.destinationIp)

        session?.close()
        stack.stop()
    }

    @Test
    fun outboundWritesKeepLatestInboundAckState() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)

        ServerSocket(0).use { server ->
            val pumpThread =
                Thread {
                    server.accept().use { accepted ->
                        session.pumpBidirectional(accepted.asProxyClientConnection())
                    }
                }
            pumpThread.start()
            Socket("127.0.0.1", server.localPort).use { peer ->
                peer.soTimeout = 500
                peer.getOutputStream().write("hello".toByteArray())
                peer.getOutputStream().flush()
                waitForOutboundCount(backend, 3)

                stack.processInboundPacketForTesting(
                    TcpPacketBuilder.buildIpv4TcpPacket(
                        sourceIp = "93.184.216.34",
                        destinationIp = "10.0.0.2",
                        sourcePort = 443,
                        destinationPort = sessionLocalPort(backend),
                        sequenceNumber = 101,
                        acknowledgementNumber = sessionInitialAck(backend),
                        flags = TcpPacketBuilder.TCP_FLAG_ACK,
                        payload = "first".toByteArray(),
                    ),
                )
                assertEquals("first", String(peer.getInputStream().readNBytes(5), Charsets.UTF_8))
                waitForOutboundCount(backend, 4)

                peer.getOutputStream().write("again".toByteArray())
                peer.getOutputStream().flush()
                waitForOutboundCount(backend, 5)

                val secondDataPacket = backend.outboundPackets[4]
                val secondDataIpv4 = IpPacketParser.parseIpv4(secondDataPacket)!!
                val secondDataTcp = IpPacketParser.parseTcp(secondDataPacket, secondDataIpv4)!!
                assertEquals(TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_PSH, secondDataTcp.flags)
                assertEquals(106, secondDataTcp.acknowledgementNumber)
                assertEquals("again", secondDataPacket.copyOfRange(secondDataTcp.payloadOffset, secondDataTcp.payloadOffset + secondDataTcp.payloadLength).toString(Charsets.UTF_8))
                peer.shutdownOutput()
            }
            pumpThread.join(1000)
        }

        session.close()
        stack.stop()
    }

    @Test
    fun outboundPayloadIsSegmentedToConfiguredMtu() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                linkMtu = 60,
                logger = { _, _ -> },
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)

        val thread =
            Thread {
                try {
                    session.pumpBidirectional(
                        ProxyClientConnection(
                            socket = Socket(),
                            input = ByteArrayInputStream(ByteArray(45) { 'a'.code.toByte() }),
                            output = ByteArrayOutputStream(),
                        ),
                    )
                } catch (_: java.io.IOException) {
                }
            }
        thread.start()

        waitForOutboundCount(backend, 6)
        session.close()
        thread.join(1000)

        val payloadPackets = backend.outboundPackets.subList(2, 5)
        val payloadLengths =
            payloadPackets.map { packet ->
                val ipv4 = IpPacketParser.parseIpv4(packet)!!
                val tcp = IpPacketParser.parseTcp(packet, ipv4)!!
                assertTrue(ipv4.totalLength <= 60)
                tcp.payloadLength
            }
        assertEquals(listOf(20, 20, 5), payloadLengths)

        stack.stop()
    }

    @Test
    fun icmpFragmentationNeededIsLoggedAsPmtuSignal() {
        val backend = CapturingBackend()
        val logs = CopyOnWriteArrayList<String>()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, message -> logs.add(message) },
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        val quotedPacket = backend.outboundPackets.first()
        stack.processInboundPacketForTesting(
            buildIcmpFragmentationNeededPacket(
                sourceIp = "172.16.200.5",
                destinationIp = "10.0.0.2",
                nextHopMtu = 1400,
                quotedPacket = quotedPacket,
            ),
        )

        assertTrue(logs.any { it.contains("proxy tcp pmtu sid=1") && it.contains("nextHopMtu=1400") })

        session.close()
        stack.stop()
    }

    @Test
    fun connectTimeoutReturnsSpecificError() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, _ -> },
                connectTimeoutMs = 100,
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))

        ServerSocket(0).use { server ->
            val pumpThread =
                Thread {
                    server.accept().use { accepted ->
                        try {
                            session.pumpBidirectional(accepted.asProxyClientConnection())
                            throw AssertionError("Expected connect timeout")
                        } catch (e: java.io.IOException) {
                            assertTrue(e.message.orEmpty().contains("timed out"))
                        }
                    }
                }
            pumpThread.start()
            Socket("127.0.0.1", server.localPort).use { peer ->
                peer.soTimeout = 200
                pumpThread.join(1000)
            }
        }

        assertEquals(UserspaceSessionState.failed, stack.sessionSnapshots().single().state)
        assertTrue(stack.sessionSnapshots().single().lastEvent.contains("timed out"))
        session.close()
        stack.stop()
    }

    private fun establishSession(stack: BridgeUserspaceTunnelStack, backend: CapturingBackend) {
        val synPacket = backend.outboundPackets.first()
        val synIpv4 = IpPacketParser.parseIpv4(synPacket)!!
        val synTcp = IpPacketParser.parseTcp(synPacket, synIpv4)!!
        stack.processInboundPacketForTesting(
            TcpPacketBuilder.buildIpv4TcpPacket(
                sourceIp = "93.184.216.34",
                destinationIp = "10.0.0.2",
                sourcePort = 443,
                destinationPort = synTcp.sourcePort,
                sequenceNumber = 100,
                acknowledgementNumber = synTcp.sequenceNumber + 1,
                flags = TcpPacketBuilder.TCP_FLAG_SYN or TcpPacketBuilder.TCP_FLAG_ACK,
            ),
        )
    }

    private fun sessionLocalPort(backend: CapturingBackend): Int {
        val synIpv4 = IpPacketParser.parseIpv4(backend.outboundPackets.first())!!
        return IpPacketParser.parseTcp(backend.outboundPackets.first(), synIpv4)!!.sourcePort
    }

    private fun sessionInitialAck(backend: CapturingBackend): Long {
        val ackIpv4 = IpPacketParser.parseIpv4(backend.outboundPackets[1])!!
        return IpPacketParser.parseTcp(backend.outboundPackets[1], ackIpv4)!!.sequenceNumber
    }

    private fun waitForOutboundCount(backend: CapturingBackend, count: Int) {
        repeat(40) {
            if (backend.outboundPackets.size >= count) return
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for $count outbound packets; saw ${backend.outboundPackets.size}")
    }

    private fun buildDnsResponsePacket(
        txId: Int,
        sourceIp: String,
        destinationIp: String,
        destinationPort: Int,
        hostname: String,
        answerIp: String,
    ): ByteArray {
        val question = encodeDnsName(hostname) + byteArrayOf(0x00, 0x01, 0x00, 0x01)
        val answerBytes = answerIp.split('.').map { it.toInt().toByte() }
        val header = ByteArray(12)
        writeUint16(header, 0, txId)
        writeUint16(header, 2, 0x8180)
        writeUint16(header, 4, 1)
        writeUint16(header, 6, 1)
        val answer =
            byteArrayOf(
                0xc0.toByte(),
                0x0c,
                0x00,
                0x01,
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x3c,
                0x00,
                0x04,
                answerBytes[0],
                answerBytes[1],
                answerBytes[2],
                answerBytes[3],
            )
        return UdpPacketBuilder.buildIpv4UdpPacket(
            sourceIp = sourceIp,
            destinationIp = destinationIp,
            sourcePort = 53,
            destinationPort = destinationPort,
            payload = header + question + answer,
        )
    }

    private fun buildDnsNegativeResponsePacket(
        txId: Int,
        sourceIp: String,
        destinationIp: String,
        destinationPort: Int,
        hostname: String,
        rcode: Int,
    ): ByteArray {
        val question = encodeDnsName(hostname) + byteArrayOf(0x00, 0x01, 0x00, 0x01)
        val header = ByteArray(12)
        writeUint16(header, 0, txId)
        writeUint16(header, 2, 0x8180 or (rcode and 0x0f))
        writeUint16(header, 4, 1)
        writeUint16(header, 6, 0)
        return UdpPacketBuilder.buildIpv4UdpPacket(
            sourceIp = sourceIp,
            destinationIp = destinationIp,
            sourcePort = 53,
            destinationPort = destinationPort,
            payload = header + question,
        )
    }

    private fun buildIcmpFragmentationNeededPacket(
        sourceIp: String,
        destinationIp: String,
        nextHopMtu: Int,
        quotedPacket: ByteArray,
    ): ByteArray {
        val payload = ByteArray(8 + quotedPacket.size)
        payload[0] = 3
        payload[1] = 4
        payload[6] = ((nextHopMtu ushr 8) and 0xff).toByte()
        payload[7] = (nextHopMtu and 0xff).toByte()
        quotedPacket.copyInto(payload, destinationOffset = 8)
        val source = java.net.InetAddress.getByName(sourceIp).address
        val destination = java.net.InetAddress.getByName(destinationIp).address
        val packet = ByteArray(20 + payload.size)
        packet[0] = 0x45
        writeUint16(packet, 2, packet.size)
        writeUint16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = IpPacketParser.IP_PROTOCOL_ICMP.toByte()
        source.copyInto(packet, destinationOffset = 12)
        destination.copyInto(packet, destinationOffset = 16)
        payload.copyInto(packet, destinationOffset = 20)
        return packet
    }

    private fun encodeDnsName(hostname: String): ByteArray {
        val bytes = ArrayList<Byte>()
        hostname.split('.').forEach { label ->
            bytes.add(label.length.toByte())
            label.toByteArray(Charsets.US_ASCII).forEach(bytes::add)
        }
        bytes.add(0)
        return bytes.toByteArray()
    }

    private fun readUint16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xff) shl 8) or (buf[offset + 1].toInt() and 0xff)

    private fun writeUint16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xff).toByte()
        buf[offset + 1] = (value and 0xff).toByte()
    }

    private fun Socket.asProxyClientConnection(): ProxyClientConnection =
        ProxyClientConnection(
            socket = this,
            input = getInputStream(),
            output = getOutputStream(),
        )

    private class ActivePacketBackend(
        private val packet: ByteArray,
    ) : ProxyPacketBridgeBackend {
        private var served = false

        override fun isActive(): Boolean = true

        override fun queueOutboundPacket(packet: ByteArray): Int = 0

        override fun readInboundPacket(maxLen: Int): ByteArray? {
            if (served) return null
            served = true
            return packet
        }
    }

    private class AlwaysActiveBackend : ProxyPacketBridgeBackend {
        override fun isActive(): Boolean = true

        override fun queueOutboundPacket(packet: ByteArray): Int = 0

        override fun readInboundPacket(maxLen: Int): ByteArray? = null
    }

    private class CapturingBackend(
        private val onQueue: CapturingBackend.(ByteArray) -> Unit = {},
        private val queueResult: CapturingBackend.(ByteArray) -> Int = { 0 },
    ) : ProxyPacketBridgeBackend {
        val outboundPackets = CopyOnWriteArrayList<ByteArray>()
        val inboundPackets = CopyOnWriteArrayList<ByteArray>()

        override fun isActive(): Boolean = true

        override fun queueOutboundPacket(packet: ByteArray): Int {
            outboundPackets.add(packet)
            onQueue(packet)
            return queueResult(packet)
        }

        override fun readInboundPacket(maxLen: Int): ByteArray? = if (inboundPackets.isEmpty()) null else inboundPackets.removeAt(0)
    }
}
