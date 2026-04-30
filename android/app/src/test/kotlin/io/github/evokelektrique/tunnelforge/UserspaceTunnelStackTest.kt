package io.github.evokelektrique.tunnelforge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    fun openTcpSessionRejectsWhenSessionLimitIsReachedAndAllowsAfterClose() {
        val logs = CopyOnWriteArrayList<String>()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = AlwaysActiveBackend()),
                logger = { _, message -> logs.add(message) },
                maxTcpSessions = 1,
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val first = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))

        try {
            stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.35", port = 443, protocol = "http-connect"))
            throw AssertionError("Expected TCP session limit failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Too many active proxy TCP sessions"))
        }
        assertTrue(logs.any { it.contains("reason=too-many-tcp-sessions") })

        first.close()
        val second = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.35", port = 443, protocol = "http-connect"))
        second.close()
        stack.stop()
    }

    @Test
    fun openTcpSessionWaitsForPendingConnectPermitBeforeQueueingSyn() {
        val backend = CapturingBackend()
        val logs = CopyOnWriteArrayList<String>()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                logger = { _, message -> logs.add(message) },
                maxTcpSessions = 4,
                maxPendingTcpConnects = 1,
                synRetransmitDelaysMs = emptyList(),
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val first = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        val secondRef = AtomicReference<UserspaceTunnelSession?>()
        val errorRef = AtomicReference<Throwable?>()
        val opened = CountDownLatch(1)
        val waiter =
            Thread {
                try {
                    secondRef.set(
                        stack.openTcpSession(
                            ProxyConnectRequest(host = "93.184.216.35", port = 443, protocol = "http-connect"),
                        ),
                    )
                } catch (t: Throwable) {
                    errorRef.set(t)
                } finally {
                    opened.countDown()
                }
            }
        waiter.start()

        Thread.sleep(40)
        assertFalse(opened.await(10, TimeUnit.MILLISECONDS))
        assertEquals(1, backend.outboundPackets.size)
        assertTrue(logs.any { it.contains("reason=pending-tcp-connects") })

        first.close()
        assertTrue(opened.await(500, TimeUnit.MILLISECONDS))
        assertNull(errorRef.get())
        assertEquals(2, backend.outboundPackets.size)

        secondRef.get()?.close()
        stack.stop()
    }

    @Test
    fun establishedSessionReleasesPendingConnectPermit() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, _ -> },
                maxTcpSessions = 4,
                maxPendingTcpConnects = 1,
                synRetransmitDelaysMs = emptyList(),
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val first = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)
        val second = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.35", port = 443, protocol = "http-connect"))

        assertEquals(2, stack.activeSessions().size)

        first.close()
        second.close()
        stack.stop()
    }

    @Test
    fun timedOutConnectReleasesPendingConnectPermitBeforeClose() {
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = CapturingBackend()),
                logger = { _, _ -> },
                maxTcpSessions = 2,
                maxPendingTcpConnects = 1,
                connectTimeoutMs = 30,
                synRetransmitDelaysMs = emptyList(),
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val first = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        try {
            first.awaitEstablished(30)
            throw AssertionError("Expected connect timeout")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("timed out"))
        }

        val second = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.35", port = 443, protocol = "http-connect"))
        first.close()
        second.close()
        stack.stop()
    }

    @Test
    fun openTcpSessionTimesOutWhileWaitingForPendingConnectPermit() {
        val backend = CapturingBackend()
        val logs = CopyOnWriteArrayList<String>()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                logger = { _, message -> logs.add(message) },
                maxTcpSessions = 4,
                maxPendingTcpConnects = 1,
                connectTimeoutMs = 40,
                synRetransmitDelaysMs = emptyList(),
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))
        val first = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))

        try {
            stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.35", port = 443, protocol = "http-connect"))
            throw AssertionError("Expected pending TCP connect wait timeout")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Timed out while waiting for a pending proxy TCP connection slot"))
        }
        assertEquals(1, backend.outboundPackets.size)
        assertTrue(logs.any { it.contains("reason=pending-tcp-connect-wait-timeout") })

        first.close()
        stack.stop()
    }

    @Test
    fun proxyOnlyCapacityMatchesAppPolicy() {
        assertNull(ProxyTunnelService.PROXY_ONLY_MAX_TCP_SESSIONS)
        assertNull(ProxyTunnelService.PROXY_ONLY_MAX_CONCURRENT_CLIENTS)
        assertNull(ProxyTunnelService.PROXY_ONLY_MAX_PENDING_TCP_CONNECTS)
        assertEquals(60_000L, ProxyTunnelService.PROXY_ONLY_CONNECT_TIMEOUT_MS)
        assertEquals(10_000L, ProxyTunnelService.PROXY_ONLY_CONNECT_RESPONSE_TIMEOUT_MS)
        assertEquals(10_000L, ProxyTunnelService.PROXY_ONLY_UPSTREAM_CONNECT_TIMEOUT_MS)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L),
            ProxyTunnelService.PROXY_ONLY_SYN_RETRANSMIT_DELAYS_MS,
        )
        assertEquals(20L, ProxyTunnelService.PROXY_ONLY_SYN_PACING_INTERVAL_MS)
        assertEquals(5_000L, ProxyTunnelService.PROXY_ONLY_TCP_FIN_DRAIN_TIMEOUT_MS)
    }

    @Test
    fun initialSynQueueBackpressureRetriesInsteadOfFailingSessionOpen() {
        val logs = CopyOnWriteArrayList<String>()
        val backend =
            CapturingBackend(
                queueResult = { if (outboundPackets.size == 1) -1 else 0 },
            )
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, message -> logs.add(message) },
                connectTimeoutMs = 500,
                synRetransmitDelaysMs = emptyList(),
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        waitForOutboundCount(backend, 2)
        establishSession(stack, backend)
        session.awaitEstablished(500)

        assertTrue(logs.any { it.contains("queue retry") })
        session.close()
        stack.stop()
    }

    @Test
    fun synPacingAcceptsBurstButStaggersOutboundSyns() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, _ -> },
                synRetransmitDelaysMs = emptyList(),
                synPacingIntervalMs = 80,
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))
        val sessions = ArrayList<UserspaceTunnelSession>()

        try {
            repeat(3) { index ->
                sessions += stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.${index + 34}", port = 443, protocol = "http-connect"))
            }

            assertEquals(3, stack.activeSessions().size)
            waitForOutboundCount(backend, 1)
            Thread.sleep(30)
            assertEquals(1, backend.outboundPackets.size)
            waitForOutboundCount(backend, 3)
        } finally {
            sessions.forEach { it.close() }
            stack.stop()
        }
    }

    @Test
    fun timedOutConnectReleasesSessionPermitAfterClose() {
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = CapturingBackend()),
                logger = { _, _ -> },
                maxTcpSessions = 1,
                connectTimeoutMs = 30,
                synRetransmitDelaysMs = emptyList(),
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val first = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        try {
            try {
                first.awaitEstablished(30)
                throw AssertionError("Expected connect timeout")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().contains("timed out"))
            }
        } finally {
            first.close()
        }

        val second = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.35", port = 443, protocol = "http-connect"))
        second.close()
        stack.stop()
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
    fun tcpPacketBuilderAndParserPreserveAdvertisedWindow() {
        val packet =
            TcpPacketBuilder.buildIpv4TcpPacket(
                sourceIp = "10.0.0.2",
                destinationIp = "93.184.216.34",
                sourcePort = 40000,
                destinationPort = 443,
                sequenceNumber = 10,
                acknowledgementNumber = 20,
                flags = TcpPacketBuilder.TCP_FLAG_ACK,
                windowSize = 1234,
                payload = "x".toByteArray(),
            )

        val ipv4 = IpPacketParser.parseIpv4(packet)!!
        val tcp = IpPacketParser.parseTcp(packet, ipv4)!!

        assertEquals(1234, tcp.windowSize)
        assertEquals(1, tcp.payloadLength)
    }

    @Test
    fun tcpSourcePortAllocationWrapsAndReusesOnlyAfterClose() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))
        val nextLocalPort = BridgeUserspaceTunnelStack::class.java.getDeclaredField("nextLocalPort").apply { isAccessible = true }
            .get(stack) as AtomicInteger

        nextLocalPort.set(65535)
        val first = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        assertEquals(65535, tcpSourcePort(backend.outboundPackets.last()))

        nextLocalPort.set(65535)
        val second = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.35", port = 443, protocol = "http-connect"))
        assertEquals(30000, tcpSourcePort(backend.outboundPackets.last()))

        first.close()
        nextLocalPort.set(65535)
        val third = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.36", port = 443, protocol = "http-connect"))
        assertEquals(65535, tcpSourcePort(backend.outboundPackets.last()))

        second.close()
        third.close()
        stack.stop()
    }

    @Test
    fun udpAssociationQueuesOutboundUdpAndDeliversMatchedReply() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val association = stack.openUdpAssociation(ProxyConnectRequest(host = "0.0.0.0", port = 0, protocol = "socks5-udp"))
        association.send(
            ProxyUdpDatagram(
                host = "93.184.216.34",
                port = 5353,
                payload = "ping".toByteArray(Charsets.US_ASCII),
            ),
        )

        val outbound = backend.outboundPackets.single()
        val outboundIpv4 = IpPacketParser.parseIpv4(outbound)!!
        val outboundUdp = IpPacketParser.parseUdp(outbound, outboundIpv4)!!
        assertEquals("10.0.0.2", outboundIpv4.sourceIp)
        assertEquals("93.184.216.34", outboundIpv4.destinationIp)
        assertEquals(5353, outboundUdp.destinationPort)

        stack.processInboundPacketForTesting(
            UdpPacketBuilder.buildIpv4UdpPacket(
                sourceIp = "93.184.216.34",
                destinationIp = "10.0.0.2",
                sourcePort = 5353,
                destinationPort = outboundUdp.sourcePort,
                payload = "pong".toByteArray(Charsets.US_ASCII),
            ),
        )

        val received = association.receive(500)
        assertEquals("93.184.216.34", received!!.host)
        assertEquals(5353, received.port)
        assertEquals("pong", received.payload.toString(Charsets.US_ASCII))
        association.close()
        stack.stop()
    }

    @Test
    fun udpAssociationRemovesRecordedRemoteWhenQueueFails() {
        val backend = CapturingBackend(queueResult = { -1 })
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val association = stack.openUdpAssociation(ProxyConnectRequest(host = "0.0.0.0", port = 0, protocol = "socks5-udp"))
        try {
            association.send(
                ProxyUdpDatagram(
                    host = "93.184.216.34",
                    port = 5353,
                    payload = "ping".toByteArray(Charsets.US_ASCII),
                ),
            )
            throw AssertionError("Expected UDP queue failure")
        } catch (_: IOException) {
        }

        val outbound = backend.outboundPackets.single()
        val outboundIpv4 = IpPacketParser.parseIpv4(outbound)!!
        val outboundUdp = IpPacketParser.parseUdp(outbound, outboundIpv4)!!
        stack.processInboundPacketForTesting(
            UdpPacketBuilder.buildIpv4UdpPacket(
                sourceIp = "93.184.216.34",
                destinationIp = "10.0.0.2",
                sourcePort = 5353,
                destinationPort = outboundUdp.sourcePort,
                payload = "late".toByteArray(Charsets.US_ASCII),
            ),
        )

        assertNull(association.receive(50))
        association.close()
        stack.stop()
    }

    @Test
    fun udpAssociationKeepsExistingRemoteWhenRetryQueueFails() {
        val backend = CapturingBackend(queueResult = { if (outboundPackets.size == 1) 0 else -1 })
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val association = stack.openUdpAssociation(ProxyConnectRequest(host = "0.0.0.0", port = 0, protocol = "socks5-udp"))
        association.send(
            ProxyUdpDatagram(
                host = "93.184.216.34",
                port = 5353,
                payload = "first".toByteArray(Charsets.US_ASCII),
            ),
        )
        val firstOutbound = backend.outboundPackets.single()
        val firstIpv4 = IpPacketParser.parseIpv4(firstOutbound)!!
        val firstUdp = IpPacketParser.parseUdp(firstOutbound, firstIpv4)!!

        try {
            association.send(
                ProxyUdpDatagram(
                    host = "93.184.216.34",
                    port = 5353,
                    payload = "retry".toByteArray(Charsets.US_ASCII),
                ),
            )
            throw AssertionError("Expected UDP queue failure")
        } catch (_: IOException) {
        }

        stack.processInboundPacketForTesting(
            UdpPacketBuilder.buildIpv4UdpPacket(
                sourceIp = "93.184.216.34",
                destinationIp = "10.0.0.2",
                sourcePort = 5353,
                destinationPort = firstUdp.sourcePort,
                payload = "pong".toByteArray(Charsets.US_ASCII),
            ),
        )

        val received = association.receive(500)
        assertEquals("93.184.216.34", received!!.host)
        assertEquals(5353, received.port)
        assertEquals("pong", received.payload.toString(Charsets.US_ASCII))
        association.close()
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
    fun openingSessionRetransmitsSynUntilEstablished() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, _ -> },
                synRetransmitDelaysMs = listOf(20L, 20L),
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))

        waitForOutboundCount(backend, 3)
        val synPackets = backend.outboundPackets.take(3)
        val firstIpv4 = IpPacketParser.parseIpv4(synPackets.first())!!
        val firstTcp = IpPacketParser.parseTcp(synPackets.first(), firstIpv4)!!
        synPackets.forEach { packet ->
            val ipv4 = IpPacketParser.parseIpv4(packet)!!
            val tcp = IpPacketParser.parseTcp(packet, ipv4)!!
            assertEquals("93.184.216.34", ipv4.destinationIp)
            assertEquals(443, tcp.destinationPort)
            assertEquals(TcpPacketBuilder.TCP_FLAG_SYN, tcp.flags)
            assertEquals(firstTcp.sourcePort, tcp.sourcePort)
            assertEquals(firstTcp.sequenceNumber, tcp.sequenceNumber)
        }

        session.close()
        stack.stop()
    }

    @Test
    fun synRetransmitStopsAfterSessionEstablished() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, _ -> },
                synRetransmitDelaysMs = listOf(30L, 30L, 30L),
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)
        Thread.sleep(120)

        assertEquals(2, backend.outboundPackets.size)
        val ackIpv4 = IpPacketParser.parseIpv4(backend.outboundPackets.last())!!
        val ackTcp = IpPacketParser.parseTcp(backend.outboundPackets.last(), ackIpv4)!!
        assertEquals(TcpPacketBuilder.TCP_FLAG_ACK, ackTcp.flags)

        session.close()
        stack.stop()
    }

    @Test
    fun pendingSessionCloseCancelsSynRetransmits() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, _ -> },
                synRetransmitDelaysMs = listOf(30L, 30L, 30L),
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        assertEquals(1, backend.outboundPackets.size)
        session.close()
        Thread.sleep(120)

        assertEquals(1, backend.outboundPackets.size)
        assertTrue(stack.activeSessions().isEmpty())
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

        waitForOutboundCount(backend, 3)
        val dataPacket = backend.outboundPackets[2]
        val dataIpv4 = IpPacketParser.parseIpv4(dataPacket)!!
        val dataTcp = IpPacketParser.parseTcp(dataPacket, dataIpv4)!!
        assertEquals(TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_PSH, dataTcp.flags)
        assertEquals(101, dataTcp.acknowledgementNumber)
        assertEquals(5, dataTcp.payloadLength)
        assertEquals("hello", dataPacket.copyOfRange(dataTcp.payloadOffset, dataTcp.payloadOffset + dataTcp.payloadLength).toString(Charsets.UTF_8))
        val finPacket =
            waitForOutboundTcpPacket(backend) { tcp ->
                tcp.flags == (TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_FIN)
            }
        val finIpv4 = IpPacketParser.parseIpv4(finPacket)!!
        val finTcp = IpPacketParser.parseTcp(finPacket, finIpv4)!!
        assertEquals(TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_FIN, finTcp.flags)
        assertEquals(101, finTcp.acknowledgementNumber)

        session.close()
        stack.stop()
    }

    @Test
    fun activeSessionRespectsPeerReceiveWindowAndResumesAfterAck() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, _ -> },
                tcpFinDrainTimeoutMs = 50,
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend, windowSize = 3)

        ServerSocket(0).use { server ->
            val pumpDone = CountDownLatch(1)
            val pumpThread =
                Thread {
                    try {
                        server.accept().use { accepted ->
                            session.pumpBidirectional(accepted.asProxyClientConnection())
                        }
                    } finally {
                        pumpDone.countDown()
                    }
                }
            pumpThread.start()
            Socket("127.0.0.1", server.localPort).use { peer ->
                peer.getOutputStream().write("hello".toByteArray())
                peer.getOutputStream().flush()

                waitForOutboundCount(backend, 3)
                val firstDataPacket = backend.outboundPackets[2]
                val firstDataIpv4 = IpPacketParser.parseIpv4(firstDataPacket)!!
                val firstDataTcp = IpPacketParser.parseTcp(firstDataPacket, firstDataIpv4)!!
                assertEquals(TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_PSH, firstDataTcp.flags)
                assertEquals(3, firstDataTcp.payloadLength)
                assertEquals(
                    "hel",
                    firstDataPacket.copyOfRange(firstDataTcp.payloadOffset, firstDataTcp.payloadOffset + firstDataTcp.payloadLength)
                        .toString(Charsets.UTF_8),
                )
                Thread.sleep(75)
                assertEquals(3, backend.outboundPackets.size)

                stack.processInboundPacketForTesting(
                    TcpPacketBuilder.buildIpv4TcpPacket(
                        sourceIp = "93.184.216.34",
                        destinationIp = "10.0.0.2",
                        sourcePort = 443,
                        destinationPort = sessionLocalPort(backend),
                        sequenceNumber = 101,
                        acknowledgementNumber = firstDataTcp.sequenceNumber + firstDataTcp.payloadLength,
                        flags = TcpPacketBuilder.TCP_FLAG_ACK,
                        windowSize = 2,
                    ),
                )

                waitForOutboundCount(backend, 4)
                val secondDataPacket = backend.outboundPackets[3]
                val secondDataIpv4 = IpPacketParser.parseIpv4(secondDataPacket)!!
                val secondDataTcp = IpPacketParser.parseTcp(secondDataPacket, secondDataIpv4)!!
                assertEquals(TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_PSH, secondDataTcp.flags)
                assertEquals(2, secondDataTcp.payloadLength)
                assertEquals(
                    "lo",
                    secondDataPacket.copyOfRange(secondDataTcp.payloadOffset, secondDataTcp.payloadOffset + secondDataTcp.payloadLength)
                        .toString(Charsets.UTF_8),
                )

                peer.shutdownOutput()
                waitForOutboundCount(backend, 5)
            }
            assertTrue(pumpDone.await(1, TimeUnit.SECONDS))
        }

        session.close()
        stack.stop()
    }

    @Test
    fun zeroWindowSessionSendsPersistProbeAndResumesWhenWindowOpens() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, _ -> },
                tcpPersistProbeDelaysMs = listOf(20L),
                tcpFinDrainTimeoutMs = 50,
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend, windowSize = 0)

        ServerSocket(0).use { server ->
            val pumpDone = CountDownLatch(1)
            val pumpThread =
                Thread {
                    try {
                        server.accept().use { accepted ->
                            session.pumpBidirectional(accepted.asProxyClientConnection())
                        }
                    } finally {
                        pumpDone.countDown()
                    }
                }
            pumpThread.start()
            Socket("127.0.0.1", server.localPort).use { peer ->
                peer.getOutputStream().write("hi".toByteArray())
                peer.getOutputStream().flush()

                waitForOutboundCount(backend, 3)
                val probePacket = backend.outboundPackets[2]
                val probeIpv4 = IpPacketParser.parseIpv4(probePacket)!!
                val probeTcp = IpPacketParser.parseTcp(probePacket, probeIpv4)!!
                assertEquals(TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_PSH, probeTcp.flags)
                assertEquals(1, probeTcp.payloadLength)
                assertEquals("h", probePacket.copyOfRange(probeTcp.payloadOffset, probeTcp.payloadOffset + probeTcp.payloadLength).toString(Charsets.UTF_8))

                stack.processInboundPacketForTesting(
                    TcpPacketBuilder.buildIpv4TcpPacket(
                        sourceIp = "93.184.216.34",
                        destinationIp = "10.0.0.2",
                        sourcePort = 443,
                        destinationPort = sessionLocalPort(backend),
                        sequenceNumber = 101,
                        acknowledgementNumber = probeTcp.sequenceNumber,
                        flags = TcpPacketBuilder.TCP_FLAG_ACK,
                        windowSize = 2,
                    ),
                )

                waitForOutboundCount(backend, 4)
                val dataPacket = backend.outboundPackets[3]
                val dataIpv4 = IpPacketParser.parseIpv4(dataPacket)!!
                val dataTcp = IpPacketParser.parseTcp(dataPacket, dataIpv4)!!
                assertEquals(2, dataTcp.payloadLength)
                assertEquals("hi", dataPacket.copyOfRange(dataTcp.payloadOffset, dataTcp.payloadOffset + dataTcp.payloadLength).toString(Charsets.UTF_8))
                Thread.sleep(50)
                assertEquals(4, backend.outboundPackets.size)

                peer.shutdownOutput()
                waitForOutboundCount(backend, 5)
            }
            assertTrue(pumpDone.await(1, TimeUnit.SECONDS))
        }

        session.close()
        stack.stop()
    }

    @Test
    fun duplicateAcksTriggerFastRetransmitBeforeRto() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, _ -> },
                tcpFinDrainTimeoutMs = 50,
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend, windowSize = 10)

        ServerSocket(0).use { server ->
            val pumpDone = CountDownLatch(1)
            val pumpThread =
                Thread {
                    try {
                        server.accept().use { accepted ->
                            session.pumpBidirectional(accepted.asProxyClientConnection())
                        }
                    } finally {
                        pumpDone.countDown()
                    }
                }
            pumpThread.start()
            Socket("127.0.0.1", server.localPort).use { peer ->
                peer.getOutputStream().write("hello".toByteArray())
                peer.getOutputStream().flush()

                waitForOutboundCount(backend, 3)
                val dataPacket = backend.outboundPackets[2]
                val dataIpv4 = IpPacketParser.parseIpv4(dataPacket)!!
                val dataTcp = IpPacketParser.parseTcp(dataPacket, dataIpv4)!!
                repeat(3) {
                    stack.processInboundPacketForTesting(
                        TcpPacketBuilder.buildIpv4TcpPacket(
                            sourceIp = "93.184.216.34",
                            destinationIp = "10.0.0.2",
                            sourcePort = 443,
                            destinationPort = sessionLocalPort(backend),
                            sequenceNumber = 101,
                            acknowledgementNumber = dataTcp.sequenceNumber,
                            flags = TcpPacketBuilder.TCP_FLAG_ACK,
                            windowSize = 10,
                        ),
                    )
                }

                waitForOutboundCount(backend, 4)
                val retransmitPacket = backend.outboundPackets[3]
                val retransmitIpv4 = IpPacketParser.parseIpv4(retransmitPacket)!!
                val retransmitTcp = IpPacketParser.parseTcp(retransmitPacket, retransmitIpv4)!!
                assertEquals(dataTcp.sequenceNumber, retransmitTcp.sequenceNumber)
                assertEquals(5, retransmitTcp.payloadLength)

                peer.shutdownOutput()
                waitForOutboundCount(backend, 5)
            }
            assertTrue(pumpDone.await(1, TimeUnit.SECONDS))
        }

        session.close()
        stack.stop()
    }

    @Test
    fun localFinDrainTimeoutReleasesSessionWithoutRemoteFin() {
        val backend = CapturingBackend()
        val logs = CopyOnWriteArrayList<String>()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, message -> logs.add(message) },
                tcpFinDrainTimeoutMs = 50,
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend)

        ServerSocket(0).use { server ->
            val pumpDone = CountDownLatch(1)
            val pumpThread =
                Thread {
                    server.accept().use { accepted ->
                        session.pumpBidirectional(accepted.asProxyClientConnection())
                    }
                    pumpDone.countDown()
                }
            pumpThread.start()
            Socket("127.0.0.1", server.localPort).use { peer ->
                peer.shutdownOutput()
            }
            assertTrue(pumpDone.await(1, TimeUnit.SECONDS))
        }

        assertTrue(stack.sessionSnapshots().single().lastEvent.contains("FIN drain timeout"))
        assertTrue(logs.any { it.contains("reason=fin-drain-timeout") })
        session.close()
        stack.stop()
    }

    @Test
    fun establishedSessionSurvivesBeyondConnectTimeoutAndReceivesData() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, _ -> },
                connectTimeoutMs = 40,
                synRetransmitDelaysMs = emptyList(),
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
                Thread.sleep(120)
                assertEquals(UserspaceSessionState.established, stack.sessionSnapshots().single().state)
                stack.processInboundPacketForTesting(
                    TcpPacketBuilder.buildIpv4TcpPacket(
                        sourceIp = "93.184.216.34",
                        destinationIp = "10.0.0.2",
                        sourcePort = 443,
                        destinationPort = sessionLocalPort(backend),
                        sequenceNumber = 101,
                        acknowledgementNumber = sessionInitialAck(backend),
                        flags = TcpPacketBuilder.TCP_FLAG_ACK,
                        payload = "download".toByteArray(),
                    ),
                )

                assertEquals("download", String(peer.getInputStream().readNBytes(8), Charsets.UTF_8))
                peer.shutdownOutput()
            }
            pumpThread.join(1000)
        }

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
                waitForSessionState(
                    stack,
                    UserspaceSessionState.closed,
                    timeoutMs = 250,
                    pollIntervalMs = 5,
                )
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
    fun outOfOrderInboundPayloadIsBufferedAndDeliveredAfterGap() {
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

        ServerSocket(0).use { server ->
            val thread =
                Thread {
                    server.accept().use { accepted ->
                        session.pumpBidirectional(accepted.asProxyClientConnection())
                    }
                }
            thread.start()
            Socket("127.0.0.1", server.localPort).use { peer ->
                peer.soTimeout = 500
                waitForClientAttachment(stack, session.descriptor.sessionId)
                val before = backend.outboundPackets.size
                stack.processInboundPacketForTesting(outOfOrderPacket)

                assertEquals(before + 1, backend.outboundPackets.size)
                assertTrue(stack.sessionSnapshots().single().lastEvent.contains("Buffered out-of-order inbound TCP"))
                val reAckPacket = backend.outboundPackets.last()
                val reAckIpv4 = IpPacketParser.parseIpv4(reAckPacket)!!
                val reAckTcp = IpPacketParser.parseTcp(reAckPacket, reAckIpv4)!!
                assertEquals(TcpPacketBuilder.TCP_FLAG_ACK, reAckTcp.flags)
                assertEquals(101, reAckTcp.acknowledgementNumber)

                stack.processInboundPacketForTesting(
                    TcpPacketBuilder.buildIpv4TcpPacket(
                        sourceIp = "93.184.216.34",
                        destinationIp = "10.0.0.2",
                        sourcePort = 443,
                        destinationPort = sessionLocalPort(backend),
                        sequenceNumber = 101,
                        acknowledgementNumber = sessionInitialAck(backend),
                        flags = TcpPacketBuilder.TCP_FLAG_ACK,
                        payload = "hello".toByteArray(),
                    ),
                )
                assertEquals("hellolater", String(peer.getInputStream().readNBytes(10), Charsets.UTF_8))
                val finalAckPacket = backend.outboundPackets.last()
                val finalAckIpv4 = IpPacketParser.parseIpv4(finalAckPacket)!!
                val finalAckTcp = IpPacketParser.parseTcp(finalAckPacket, finalAckIpv4)!!
                assertEquals(111, finalAckTcp.acknowledgementNumber)
                peer.shutdownOutput()
            }
            thread.join(1000)
        }
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
    fun stoppingStackReleasesActiveSessionWaitingForSendWindow() {
        val backend = CapturingBackend()
        val stack = BridgeUserspaceTunnelStack(bridge = ProxyPacketBridge(backend = backend), clientIpv4 = "10.0.0.2", logger = { _, _ -> })
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        establishSession(stack, backend, windowSize = 0)

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

                Thread.sleep(100)
                stack.stop()

                pumpThread.join(1_000)
                assertFalse(pumpThread.isAlive)
            }
        }

        assertTrue(failure is IOException)
        val message = (failure as IOException).message.orEmpty()
        assertTrue(message.contains("Proxy packet bridge stopped"))
        assertFalse(message.contains("Timed out waiting for remote TCP receive window"))
        session.close()
    }

    @Test
    fun hostnameSessionResolvesThroughTunneledDnsBeforeQueuingSyn() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                dnsServers =
                    listOf(
                        ResolvedDnsServerConfig(
                            host = "1.1.1.1",
                            protocol = DnsProtocol.dnsOverUdp,
                            resolvedIpv4 = "1.1.1.1",
                        ),
                    ),
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
                dnsServers =
                    listOf(
                        ResolvedDnsServerConfig(
                            host = "1.1.1.1",
                            protocol = DnsProtocol.dnsOverUdp,
                            resolvedIpv4 = "1.1.1.1",
                        ),
                        ResolvedDnsServerConfig(
                            host = "8.8.8.8",
                            protocol = DnsProtocol.dnsOverUdp,
                            resolvedIpv4 = "8.8.8.8",
                        ),
                    ),
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
                dnsServers =
                    listOf(
                        ResolvedDnsServerConfig(
                            host = "1.1.1.1",
                            protocol = DnsProtocol.dnsOverUdp,
                            resolvedIpv4 = "1.1.1.1",
                        ),
                        ResolvedDnsServerConfig(
                            host = "8.8.8.8",
                            protocol = DnsProtocol.dnsOverUdp,
                            resolvedIpv4 = "8.8.8.8",
                        ),
                    ),
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
        assertEquals("DNS response code 3.", (failure as IOException).message)
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
                dnsServers =
                    listOf(
                        ResolvedDnsServerConfig(
                            host = "1.1.1.1",
                            protocol = DnsProtocol.dnsOverUdp,
                            resolvedIpv4 = "1.1.1.1",
                        ),
                        ResolvedDnsServerConfig(
                            host = "8.8.8.8",
                            protocol = DnsProtocol.dnsOverUdp,
                            resolvedIpv4 = "8.8.8.8",
                        ),
                    ),
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
        val logs = CopyOnWriteArrayList<String>()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, message -> logs.add(message) },
                connectTimeoutMs = 60,
                synRetransmitDelaysMs = listOf(20L, 100L),
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
                            assertTrue(e.message.orEmpty().contains("host=93.184.216.34"))
                            assertTrue(e.message.orEmpty().contains("ip=93.184.216.34"))
                            assertTrue(e.message.orEmpty().contains("sport="))
                            assertTrue(e.message.orEmpty().contains("synAttempts=2"))
                            assertTrue(e.message.orEmpty().contains("connectDiag="))
                            assertTrue(e.message.orEmpty().contains("upstreamReply=none-through-tunnel"))
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
        assertTrue(logs.any { it.contains("reason=connect-timeout") && it.contains("host=93.184.216.34") })
        assertFalse(logs.any { it.contains("reason=connect-failed") })
        Thread.sleep(140)
        assertEquals(2, backend.outboundPackets.size)
        session.close()
        stack.stop()
    }

    @Test
    fun noReplyConnectTimeoutCachesDuplicateTargetBriefly() {
        val backend = CapturingBackend()
        val logs = CopyOnWriteArrayList<String>()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, message -> logs.add(message) },
                connectTimeoutMs = 40,
                synRetransmitDelaysMs = emptyList(),
                noReplyFailureCacheTtlMs = 1_000,
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val first = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        try {
            first.awaitEstablished(100)
            throw AssertionError("Expected first connect timeout")
        } catch (e: ProxyTransportException) {
            assertEquals(ProxyTransportFailureReason.upstreamTimeout, e.failureReason)
            assertTrue(e.message.orEmpty().contains("upstreamReply=none-through-tunnel"))
        }
        first.close()
        val outboundAfterFirst = backend.outboundPackets.size

        try {
            stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
            throw AssertionError("Expected cached no-reply failure")
        } catch (e: ProxyTransportException) {
            assertEquals(ProxyTransportFailureReason.upstreamTimeout, e.failureReason)
            assertTrue(e.message.orEmpty().contains("cachedAgeMs="))
            assertTrue(e.message.orEmpty().contains("upstreamReply=none-through-tunnel"))
        }

        assertEquals(outboundAfterFirst, backend.outboundPackets.size)
        assertTrue(logs.any { it.contains("reason=upstream-no-reply-cache") })
        stack.stop()
    }

    @Test
    fun rstConnectFailureDoesNotCacheTarget() {
        val backend = CapturingBackend()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, _ -> },
                connectTimeoutMs = 5_000,
                synRetransmitDelaysMs = emptyList(),
                noReplyFailureCacheTtlMs = 1_000,
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val first = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
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
                flags = TcpPacketBuilder.TCP_FLAG_RST or TcpPacketBuilder.TCP_FLAG_ACK,
            ),
        )
        try {
            first.awaitEstablished(200)
            throw AssertionError("Expected TCP RST failure")
        } catch (e: ProxyTransportException) {
            assertEquals(ProxyTransportFailureReason.upstreamConnectFailed, e.failureReason)
        }
        first.close()
        val outboundAfterFirst = backend.outboundPackets.size

        val second = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))

        assertEquals(outboundAfterFirst + 1, backend.outboundPackets.size)
        second.close()
        stack.stop()
    }

    @Test
    fun tcpRstBeforeEstablishmentFailsConnectImmediately() {
        val backend = CapturingBackend()
        val logs = CopyOnWriteArrayList<String>()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, message -> logs.add(message) },
                connectTimeoutMs = 5_000,
                synRetransmitDelaysMs = emptyList(),
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
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
                flags = TcpPacketBuilder.TCP_FLAG_RST or TcpPacketBuilder.TCP_FLAG_ACK,
            ),
        )

        try {
            session.awaitEstablished(200)
            throw AssertionError("Expected TCP RST failure")
        } catch (e: ProxyTransportException) {
            assertEquals(ProxyTransportFailureReason.upstreamConnectFailed, e.failureReason)
            assertTrue(e.message.orEmpty().contains("TCP RST"))
        }
        assertTrue(logs.any { it.contains("reason=rst-in") })

        session.close()
        stack.stop()
    }

    @Test
    fun icmpDestinationUnreachableFailsPendingConnectImmediately() {
        val backend = CapturingBackend()
        val logs = CopyOnWriteArrayList<String>()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, message -> logs.add(message) },
                connectTimeoutMs = 5_000,
                synRetransmitDelaysMs = emptyList(),
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        val quotedPacket = backend.outboundPackets.first()
        stack.processInboundPacketForTesting(
            buildIcmpDestinationUnreachablePacket(
                sourceIp = "172.16.200.5",
                destinationIp = "10.0.0.2",
                code = 3,
                quotedPacket = quotedPacket,
            ),
        )

        try {
            session.awaitEstablished(200)
            throw AssertionError("Expected ICMP unreachable failure")
        } catch (e: ProxyTransportException) {
            assertEquals(ProxyTransportFailureReason.upstreamConnectFailed, e.failureReason)
            assertTrue(e.message.orEmpty().contains("ICMP destination unreachable"))
        }
        assertTrue(logs.any { it.contains("reason=icmp-unreachable code=3") })

        session.close()
        stack.stop()
    }

    @Test
    fun inboundTcpSourceMismatchIsLoggedAndIncludedInTimeoutDiagnostics() {
        val backend = CapturingBackend()
        val logs = CopyOnWriteArrayList<String>()
        val stack =
            BridgeUserspaceTunnelStack(
                bridge = ProxyPacketBridge(backend = backend),
                clientIpv4 = "10.0.0.2",
                logger = { _, message -> logs.add(message) },
                connectTimeoutMs = 60,
                synRetransmitDelaysMs = emptyList(),
            )
        assertTrue(stack.waitUntilReady(timeoutMs = 50, pollIntervalMs = 5))

        val session = stack.openTcpSession(ProxyConnectRequest(host = "93.184.216.34", port = 443, protocol = "http-connect"))
        val synPacket = backend.outboundPackets.first()
        val synIpv4 = IpPacketParser.parseIpv4(synPacket)!!
        val synTcp = IpPacketParser.parseTcp(synPacket, synIpv4)!!
        stack.processInboundPacketForTesting(
            TcpPacketBuilder.buildIpv4TcpPacket(
                sourceIp = "203.0.113.10",
                destinationIp = "10.0.0.2",
                sourcePort = 443,
                destinationPort = synTcp.sourcePort,
                sequenceNumber = 100,
                acknowledgementNumber = synTcp.sequenceNumber + 1,
                flags = TcpPacketBuilder.TCP_FLAG_SYN or TcpPacketBuilder.TCP_FLAG_ACK,
            ),
        )

        try {
            session.awaitEstablished(200)
            throw AssertionError("Expected connect timeout")
        } catch (e: ProxyTransportException) {
            assertEquals(ProxyTransportFailureReason.upstreamTimeout, e.failureReason)
            assertTrue(e.message.orEmpty().contains("sourceMismatchDrops=1"))
        }
        assertTrue(logs.any { it.contains("Dropped inbound TCP source mismatch") })

        session.close()
        stack.stop()
    }

    private fun establishSession(stack: BridgeUserspaceTunnelStack, backend: CapturingBackend, windowSize: Int = 65535) {
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
                windowSize = windowSize,
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

    private fun waitForClientAttachment(stack: BridgeUserspaceTunnelStack, sessionId: Int) {
        val field = BridgeUserspaceTunnelStack::class.java.getDeclaredField("clientAttachments")
        field.isAccessible = true
        val attachments = field.get(stack) as Map<*, *>
        repeat(40) {
            if (attachments.containsKey(sessionId)) return
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for client attachment for session $sessionId")
    }

    private fun waitForOutboundTcpPacket(backend: CapturingBackend, predicate: (ParsedTcpSegment) -> Boolean): ByteArray {
        repeat(40) {
            backend.outboundPackets.firstOrNull { packet ->
                val ipv4 = IpPacketParser.parseIpv4(packet) ?: return@firstOrNull false
                val tcp = IpPacketParser.parseTcp(packet, ipv4) ?: return@firstOrNull false
                predicate(tcp)
            }?.let { return it }
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for matching outbound TCP packet; saw ${backend.outboundPackets.size}")
    }

    private fun waitForSessionState(
        stack: BridgeUserspaceTunnelStack,
        expectedState: UserspaceSessionState,
        timeoutMs: Long = 1_000,
        pollIntervalMs: Long = 25,
    ) {
        val attempts = (timeoutMs / pollIntervalMs).coerceAtLeast(1)
        repeat(attempts.toInt()) {
            val snapshot = stack.sessionSnapshots().singleOrNull()
            if (snapshot != null && snapshot.state == expectedState) {
                return
            }
            Thread.sleep(pollIntervalMs)
        }
        throw AssertionError(
            "Timed out waiting for session state=$expectedState; snapshots=${stack.sessionSnapshots()}",
        )
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

    private fun buildIcmpDestinationUnreachablePacket(
        sourceIp: String,
        destinationIp: String,
        code: Int,
        quotedPacket: ByteArray,
    ): ByteArray {
        val payload = ByteArray(8 + quotedPacket.size)
        payload[0] = 3
        payload[1] = code.toByte()
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

    private fun tcpSourcePort(packet: ByteArray): Int {
        val ipv4 = IpPacketParser.parseIpv4(packet)!!
        return IpPacketParser.parseTcp(packet, ipv4)!!.sourcePort
    }

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
