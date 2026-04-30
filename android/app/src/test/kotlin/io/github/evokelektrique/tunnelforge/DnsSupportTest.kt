package io.github.evokelektrique.tunnelforge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsSupportTest {

    @Test
    fun `dns-over-https accepts custom endpoint path and preserves it`() {
        val sanitized =
            DnsConfigSupport.sanitize(
                listOf(
                    DnsServerConfig(
                        host = "wikimedia-dns.org/custom-path?dns=1",
                        protocol = DnsProtocol.dnsOverHttps,
                    ),
                ),
            )

        assertEquals(1, sanitized.size)
        assertEquals("wikimedia-dns.org/custom-path?dns=1", sanitized.single().host)
        assertTrue(
            DnsConfigSupport.isValid(
                DnsServerConfig(
                    host = "wikimedia-dns.org/custom-path?dns=1",
                    protocol = DnsProtocol.dnsOverHttps,
                ),
            ),
        )
    }

    @Test
    fun `dns-over-https accepts full https urls`() {
        val sanitized =
            DnsConfigSupport.sanitize(
                listOf(
                    DnsServerConfig(
                        host = "https://wikimedia-dns.org/custom-path?dns=1",
                        protocol = DnsProtocol.dnsOverHttps,
                    ),
                ),
            )

        assertEquals(1, sanitized.size)
        assertEquals("https://wikimedia-dns.org/custom-path?dns=1", sanitized.single().host)
        assertTrue(
            DnsConfigSupport.isValid(
                DnsServerConfig(
                    host = "https://wikimedia-dns.org/custom-path?dns=1",
                    protocol = DnsProtocol.dnsOverHttps,
                ),
            ),
        )
    }

    @Test
    fun `dns-over-https rejects non-https urls`() {
        assertFalse(
            DnsConfigSupport.isValid(
                DnsServerConfig(
                    host = "http://wikimedia-dns.org/dns-query",
                    protocol = DnsProtocol.dnsOverHttps,
                ),
            ),
        )
    }

    @Test
    fun validationMessageDoesNotEchoInputValue() {
        assertEquals(
            "DNS 1 must be a hostname or HTTPS URL for DNS-over-HTTPS",
            DnsConfigSupport.validationMessage(
                "DNS 1",
                DnsServerConfig(
                    host = "ftp://wikimedia-dns.org/custom-path",
                    protocol = DnsProtocol.dnsOverHttps,
                ),
            ),
        )
    }

    @Test
    fun `dns-over-https request uses configured endpoint target`() {
        val output = ByteArrayOutputStream()

        val response =
            exchangeDnsOverHttps(
                ByteArrayInputStream(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: 2\r\n" +
                            "\r\nOK"
                    ).toByteArray(),
                ),
                output,
                byteArrayOf(0x01, 0x02),
                ResolvedDnsServerConfig(
                    host = "https://wikimedia-dns.org/custom-path?dns=1",
                    protocol = DnsProtocol.dnsOverHttps,
                    resolvedIpv4 = "1.1.1.1",
                    tlsHostname = "wikimedia-dns.org",
                    requestAuthority = "wikimedia-dns.org",
                    requestPath = "/custom-path?dns=1",
                ),
            )

        assertEquals("OK", response.decodeToString())
        val request = output.toString(Charsets.US_ASCII.name())
        assertTrue(request.contains("POST /custom-path?dns=1 HTTP/1.1"))
        assertTrue(request.contains("Host: wikimedia-dns.org"))
    }

    @Test
    fun `resolve upstream keeps all IPv4 candidates for DoH host`() {
        val resolved =
            DnsConfigSupport.resolveUpstreamServers(
                listOf(
                    DnsServerConfig(
                        host = "wikimedia-dns.org/dns-query",
                        protocol = DnsProtocol.dnsOverHttps,
                    ),
                ),
                hostnameResolver = { host ->
                    assertEquals("wikimedia-dns.org", host)
                    listOf("185.71.138.138", "185.71.138.138", "208.80.154.224")
                },
            ).single()

        assertEquals("185.71.138.138", resolved.resolvedIpv4)
        assertEquals(listOf("185.71.138.138", "208.80.154.224"), resolved.ipv4Candidates)
        assertEquals("wikimedia-dns.org", resolved.tlsHostname)
        assertEquals("wikimedia-dns.org", resolved.requestAuthority)
        assertEquals("/dns-query", resolved.dohPath)
    }

    @Test
    fun vpnDnsPacketBridgeBuildsUdpResponseToOriginalClient() {
        val queryPayload = dnsQueryPayload(txId = 0x1234)
        val upstreamResponse = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte())
        val queryPacket =
            UdpPacketBuilder.buildIpv4UdpPacket(
                sourceIp = "10.0.0.2",
                destinationIp = TunnelVpnService.MANUAL_DNS_VIRTUAL_IPV4,
                sourcePort = 44000,
                destinationPort = 53,
                payload = queryPayload,
            )

        val responsePacket =
            VpnDnsPacketBridge.buildResponsePacket(
                queryPacket = queryPacket,
                virtualDnsIpv4 = TunnelVpnService.MANUAL_DNS_VIRTUAL_IPV4,
                exchangeClient =
                    object : DnsExchangeClient {
                        override fun exchange(query: ByteArray): ByteArray {
                            assertTrue(query.contentEquals(queryPayload))
                            return upstreamResponse
                        }
                    },
            )

        val ipv4 = IpPacketParser.parseIpv4(responsePacket)!!
        val udp = IpPacketParser.parseUdp(responsePacket, ipv4)!!
        assertEquals(TunnelVpnService.MANUAL_DNS_VIRTUAL_IPV4, ipv4.sourceIp)
        assertEquals("10.0.0.2", ipv4.destinationIp)
        assertEquals(53, udp.sourcePort)
        assertEquals(44000, udp.destinationPort)
        assertTrue(upstreamResponse.contentEquals(responsePacket.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)))
    }

    @Test
    fun vpnDnsPacketBridgeServfailPreservesTransactionIdAndQuestion() {
        val queryPayload = dnsQueryPayload(txId = 0x5678)
        val queryPacket =
            UdpPacketBuilder.buildIpv4UdpPacket(
                sourceIp = "10.0.0.2",
                destinationIp = TunnelVpnService.MANUAL_DNS_VIRTUAL_IPV4,
                sourcePort = 44001,
                destinationPort = 53,
                payload = queryPayload,
            )

        val responsePacket =
            VpnDnsPacketBridge.buildServfailResponsePacket(
                queryPacket = queryPacket,
                virtualDnsIpv4 = TunnelVpnService.MANUAL_DNS_VIRTUAL_IPV4,
            )!!

        val ipv4 = IpPacketParser.parseIpv4(responsePacket)!!
        val udp = IpPacketParser.parseUdp(responsePacket, ipv4)!!
        val payload = responsePacket.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        assertEquals(0x56, payload[0].toInt() and 0xff)
        assertEquals(0x78, payload[1].toInt() and 0xff)
        assertEquals(0x81, payload[2].toInt() and 0xff)
        assertEquals(0x82, payload[3].toInt() and 0xff)
        assertEquals(1, ((payload[4].toInt() and 0xff) shl 8) or (payload[5].toInt() and 0xff))
    }

    @Test(expected = IOException::class)
    fun vpnDnsPacketBridgePropagatesUpstreamFailure() {
        val queryPacket =
            UdpPacketBuilder.buildIpv4UdpPacket(
                sourceIp = "10.0.0.2",
                destinationIp = TunnelVpnService.MANUAL_DNS_VIRTUAL_IPV4,
                sourcePort = 44002,
                destinationPort = 53,
                payload = dnsQueryPayload(txId = 0x9999),
            )

        VpnDnsPacketBridge.buildResponsePacket(
            queryPacket = queryPacket,
            virtualDnsIpv4 = TunnelVpnService.MANUAL_DNS_VIRTUAL_IPV4,
            exchangeClient =
                object : DnsExchangeClient {
                    override fun exchange(query: ByteArray): ByteArray {
                        throw IOException("boom")
                    }
                },
        )
    }

    @Test
    fun vpnDnsPacketBridgeDoesNotLetSlowQueryBlockLaterQuery() {
        val firstQuery =
            UdpPacketBuilder.buildIpv4UdpPacket(
                sourceIp = "10.0.0.2",
                destinationIp = TunnelVpnService.MANUAL_DNS_VIRTUAL_IPV4,
                sourcePort = 44003,
                destinationPort = 53,
                payload = dnsQueryPayload(txId = 0x1111),
            )
        val secondQuery =
            UdpPacketBuilder.buildIpv4UdpPacket(
                sourceIp = "10.0.0.2",
                destinationIp = TunnelVpnService.MANUAL_DNS_VIRTUAL_IPV4,
                sourcePort = 44004,
                destinationPort = 53,
                payload = dnsQueryPayload(txId = 0x2222),
            )
        val releaseFirst = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val responses = mutableListOf<ByteArray>()
        val nativeIo =
            object : VpnDnsNativeIo {
                private val packets = ArrayDeque(listOf(firstQuery, secondQuery))

                override fun readQuery(maxLen: Int): ByteArray? =
                    synchronized(packets) {
                        packets.removeFirstOrNull()
                    }

                override fun queueResponse(packet: ByteArray): Int {
                    synchronized(responses) {
                        responses.add(packet)
                    }
                    return 0
                }
            }
        val bridge =
            VpnDnsPacketBridge(
                virtualDnsIpv4 = TunnelVpnService.MANUAL_DNS_VIRTUAL_IPV4,
                exchangeClient =
                    object : DnsExchangeClient {
                        override fun exchange(query: ByteArray): ByteArray {
                            return when (queryTxId(query)) {
                                0x1111 -> {
                                    firstStarted.countDown()
                                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                                    dnsResponsePayload(0x1111)
                                }
                                0x2222 -> {
                                    secondStarted.countDown()
                                    dnsResponsePayload(0x2222)
                                }
                                else -> error("unexpected query")
                            }
                        }
                    },
                logger = { _, _ -> },
                nativeIo = nativeIo,
                workerCount = 2,
                queueCapacity = 2,
            )

        try {
            bridge.start()
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertTrue(waitForResponseTxId(responses, 0x2222))
            synchronized(responses) {
                assertFalse(responses.any { responseTxId(it) == 0x1111 })
            }
        } finally {
            releaseFirst.countDown()
            bridge.close()
        }
    }

    @Test
    fun vpnDnsPacketBridgeCloseInterruptsAndJoinsReaderThread() {
        val readStarted = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val nativeIo =
            object : VpnDnsNativeIo {
                override fun readQuery(maxLen: Int): ByteArray? {
                    readStarted.countDown()
                    while (!Thread.currentThread().isInterrupted) {
                        try {
                            Thread.sleep(1_000)
                        } catch (_: InterruptedException) {
                            interrupted.set(true)
                            Thread.currentThread().interrupt()
                        }
                    }
                    return null
                }

                override fun queueResponse(packet: ByteArray): Int = 0
            }
        val bridge =
            VpnDnsPacketBridge(
                virtualDnsIpv4 = TunnelVpnService.MANUAL_DNS_VIRTUAL_IPV4,
                exchangeClient =
                    object : DnsExchangeClient {
                        override fun exchange(query: ByteArray): ByteArray = byteArrayOf()
                    },
                logger = { _, _ -> },
                nativeIo = nativeIo,
            )

        bridge.start()
        assertTrue(readStarted.await(1, TimeUnit.SECONDS))
        bridge.close()

        assertTrue(interrupted.get())
    }

    @Test
    fun directDnsExchangeProtectsUdpSocketBeforeConnect() {
        val protectCalls = AtomicInteger(0)
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2_000
            val serverThread =
                Thread {
                    val buffer = ByteArray(512)
                    val request = DatagramPacket(buffer, buffer.size)
                    server.receive(request)
                    val response = byteArrayOf(0x55, 0x66)
                    server.send(DatagramPacket(response, response.size, request.address, request.port))
                }.also { it.start() }

            val client =
                DirectDnsExchangeClient(
                    servers =
                        listOf(
                            ResolvedDnsServerConfig(
                                host = "127.0.0.1",
                                protocol = DnsProtocol.dnsOverUdp,
                                resolvedIpv4 = "127.0.0.1",
                                overridePort = server.localPort,
                            ),
                        ),
                    logger = { _, _ -> },
                    socketProtector =
                        object : DnsSocketProtector {
                            override fun protect(socket: Socket): Boolean = true

                            override fun protect(socket: DatagramSocket): Boolean {
                                assertFalse(socket.isConnected)
                                protectCalls.incrementAndGet()
                                return true
                            }
                        },
                )

            val response = client.exchange(byteArrayOf(0x01, 0x02))
            serverThread.join(2_000)
            assertEquals(1, protectCalls.get())
            assertTrue(byteArrayOf(0x55, 0x66).contentEquals(response))
        }
    }

    @Test
    fun directDnsExchangeProtectsTcpSocketAfterBindBeforeConnect() {
        val protectCalls = AtomicInteger(0)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2_000
            val serverThread =
                Thread {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        readFramedDnsMessage(input)
                        writeFramedDnsMessage(output, byteArrayOf(0x33, 0x44))
                        output.flush()
                    }
                }.also { it.start() }

            val client =
                DirectDnsExchangeClient(
                    servers =
                        listOf(
                            ResolvedDnsServerConfig(
                                host = "127.0.0.1",
                                protocol = DnsProtocol.dnsOverTcp,
                                resolvedIpv4 = "127.0.0.1",
                                overridePort = server.localPort,
                            ),
                        ),
                    logger = { _, _ -> },
                    socketProtector =
                        object : DnsSocketProtector {
                            override fun protect(socket: Socket): Boolean {
                                assertTrue(socket.isBound)
                                assertFalse(socket.isConnected)
                                protectCalls.incrementAndGet()
                                return true
                            }

                            override fun protect(socket: DatagramSocket): Boolean = true
                        },
                )

            val response = client.exchange(byteArrayOf(0x03, 0x04))
            serverThread.join(2_000)
            assertEquals(1, protectCalls.get())
            assertTrue(byteArrayOf(0x33, 0x44).contentEquals(response))
        }
    }

    @Test
    fun directDnsExchangeFallsBackWhenUdpProtectReturnsFalse() {
        val logs = mutableListOf<String>()
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2_000
            val serverThread =
                Thread {
                    val buffer = ByteArray(512)
                    val request = DatagramPacket(buffer, buffer.size)
                    server.receive(request)
                    val response = byteArrayOf(0x66, 0x77)
                    server.send(DatagramPacket(response, response.size, request.address, request.port))
                }.also { it.start() }

            val client =
                DirectDnsExchangeClient(
                    servers =
                        listOf(
                            ResolvedDnsServerConfig(
                                host = "127.0.0.1",
                                protocol = DnsProtocol.dnsOverUdp,
                                resolvedIpv4 = "127.0.0.1",
                                overridePort = server.localPort,
                            ),
                        ),
                    logger = { _, message -> logs.add(message) },
                    socketProtector =
                        object : DnsSocketProtector {
                            override fun protect(socket: Socket): Boolean = true

                            override fun protect(socket: DatagramSocket): Boolean = false
                        },
                )

            val response = client.exchange(byteArrayOf(0x01))
            serverThread.join(2_000)
            assertTrue(byteArrayOf(0x66, 0x77).contentEquals(response))
            assertEquals(1, logs.count { it.contains("routing upstream through VPN fallback") })
        }
    }

    @Test
    fun directDnsExchangeFallsBackWhenTcpProtectReturnsFalse() {
        val logs = mutableListOf<String>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2_000
            val serverThread =
                Thread {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        readFramedDnsMessage(input)
                        writeFramedDnsMessage(output, byteArrayOf(0x44, 0x55))
                        output.flush()
                    }
                }.also { it.start() }

            val client =
                DirectDnsExchangeClient(
                    servers =
                        listOf(
                            ResolvedDnsServerConfig(
                                host = "127.0.0.1",
                                protocol = DnsProtocol.dnsOverTcp,
                                resolvedIpv4 = "127.0.0.1",
                                overridePort = server.localPort,
                            ),
                        ),
                    logger = { _, message -> logs.add(message) },
                    socketProtector =
                        object : DnsSocketProtector {
                            override fun protect(socket: Socket): Boolean {
                                assertTrue(socket.isBound)
                                assertFalse(socket.isConnected)
                                return false
                            }

                            override fun protect(socket: DatagramSocket): Boolean = true
                        },
                )

            val response = client.exchange(byteArrayOf(0x03, 0x04))
            serverThread.join(2_000)
            assertTrue(byteArrayOf(0x44, 0x55).contentEquals(response))
            assertEquals(1, logs.count { it.contains("routing upstream through VPN fallback") })
        }
    }

    @Test
    fun directDnsExchangeTriesNextIpv4CandidateWhenTcpCandidateFails() {
        val logs = mutableListOf<String>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2_000
            val serverThread =
                Thread {
                    repeat(2) {
                        server.accept().use { socket ->
                            val input = socket.getInputStream()
                            val output = socket.getOutputStream()
                            readFramedDnsMessage(input)
                            writeFramedDnsMessage(output, byteArrayOf(0x22, 0x33))
                            output.flush()
                        }
                    }
                }.also { it.start() }

            val client =
                DirectDnsExchangeClient(
                    servers =
                        listOf(
                            ResolvedDnsServerConfig(
                                host = "example.test",
                                protocol = DnsProtocol.dnsOverTcp,
                                resolvedIpv4 = "127.0.0.2",
                                resolvedIpv4Candidates = listOf("127.0.0.2", "127.0.0.1"),
                                overridePort = server.localPort,
                            ),
                        ),
                    logger = { _, message -> logs.add(message) },
                    socketProtector =
                        object : DnsSocketProtector {
                            override fun protect(socket: Socket): Boolean = true

                            override fun protect(socket: DatagramSocket): Boolean = true
                        },
                )

            val response = client.exchange(byteArrayOf(0x05, 0x06))
            val secondResponse = client.exchange(byteArrayOf(0x07, 0x08))
            serverThread.join(2_000)
            assertTrue(byteArrayOf(0x22, 0x33).contentEquals(response))
            assertTrue(byteArrayOf(0x22, 0x33).contentEquals(secondResponse))
            assertEquals(1, logs.count { it.contains("candidate=1/2") && it.contains("route=protected") && it.contains("phase=connect") })
        }
    }

    @Test
    fun directDnsExchangeRetriesTcpThroughVpnRouteWhenProtectedRouteFails() {
        val logs = mutableListOf<String>()
        val protectCalls = AtomicInteger(0)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2_000
            val serverThread =
                Thread {
                    repeat(2) {
                        server.accept().use { socket ->
                            val input = socket.getInputStream()
                            val output = socket.getOutputStream()
                            readFramedDnsMessage(input)
                            writeFramedDnsMessage(output, byteArrayOf(0x77, 0x22))
                            output.flush()
                        }
                    }
                }.also { it.start() }

            val client =
                DirectDnsExchangeClient(
                    servers =
                        listOf(
                            ResolvedDnsServerConfig(
                                host = "example.test",
                                protocol = DnsProtocol.dnsOverTcp,
                                resolvedIpv4 = "127.0.0.1",
                                overridePort = server.localPort,
                            ),
                        ),
                    logger = { _, message -> logs.add(message) },
                    socketProtector =
                        object : DnsSocketProtector {
                            override fun protect(socket: Socket): Boolean {
                                protectCalls.incrementAndGet()
                                socket.close()
                                return true
                            }

                            override fun protect(socket: DatagramSocket): Boolean = true
                        },
                )

            val response = client.exchange(byteArrayOf(0x09, 0x0a))
            val secondResponse = client.exchange(byteArrayOf(0x0b, 0x0c))
            serverThread.join(2_000)
            assertEquals(1, protectCalls.get())
            assertTrue(byteArrayOf(0x77, 0x22).contentEquals(response))
            assertTrue(byteArrayOf(0x77, 0x22).contentEquals(secondResponse))
            assertTrue(logs.any { it.contains("retrying route=vpn") })
        }
    }

    @Test
    fun directDnsExchangeDoesNotRetryVpnRouteForTcpExchangeFailure() {
        val logs = mutableListOf<String>()
        val protectCalls = AtomicInteger(0)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2_000
            val serverThread =
                Thread {
                    server.accept().close()
                }.also { it.start() }

            val client =
                DirectDnsExchangeClient(
                    servers =
                        listOf(
                            ResolvedDnsServerConfig(
                                host = "example.test",
                                protocol = DnsProtocol.dnsOverTcp,
                                resolvedIpv4 = "127.0.0.1",
                                overridePort = server.localPort,
                            ),
                        ),
                    logger = { _, message -> logs.add(message) },
                    socketProtector =
                        object : DnsSocketProtector {
                            override fun protect(socket: Socket): Boolean {
                                protectCalls.incrementAndGet()
                                return true
                            }

                            override fun protect(socket: DatagramSocket): Boolean = true
                        },
                )

            try {
                client.exchange(byteArrayOf(0x0d, 0x0e))
                error("expected exchange failure")
            } catch (e: IOException) {
                assertTrue(e.message?.contains("DNS-over-TCP exchange failed") == true)
            }
            serverThread.join(2_000)
            assertEquals(1, protectCalls.get())
            assertTrue(logs.any { it.contains("phase=exchange") })
            assertFalse(logs.any { it.contains("retrying route=vpn") })
        }
    }

    private fun dnsQueryPayload(txId: Int): ByteArray {
        val name = byteArrayOf(
            7,
            'e'.code.toByte(),
            'x'.code.toByte(),
            'a'.code.toByte(),
            'm'.code.toByte(),
            'p'.code.toByte(),
            'l'.code.toByte(),
            'e'.code.toByte(),
            3,
            'c'.code.toByte(),
            'o'.code.toByte(),
            'm'.code.toByte(),
            0,
        )
        return byteArrayOf(
            ((txId ushr 8) and 0xff).toByte(),
            (txId and 0xff).toByte(),
            0x01,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            *name,
            0x00,
            0x01,
            0x00,
            0x01,
        )
    }

    private fun dnsResponsePayload(txId: Int): ByteArray =
        dnsQueryPayload(txId).also {
            it[2] = 0x81.toByte()
            it[3] = 0x80.toByte()
        }

    private fun waitForResponseTxId(responses: List<ByteArray>, txId: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            synchronized(responses) {
                if (responses.any { responseTxId(it) == txId }) return true
            }
            Thread.sleep(10)
        }
        return false
    }

    private fun responseTxId(packet: ByteArray): Int {
        val ipv4 = IpPacketParser.parseIpv4(packet) ?: return -1
        val udp = IpPacketParser.parseUdp(packet, ipv4) ?: return -1
        return ((packet[udp.payloadOffset].toInt() and 0xff) shl 8) or
            (packet[udp.payloadOffset + 1].toInt() and 0xff)
    }

    private fun queryTxId(query: ByteArray): Int =
        ((query[0].toInt() and 0xff) shl 8) or (query[1].toInt() and 0xff)
}
