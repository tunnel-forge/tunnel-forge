package io.github.evokelektrique.tunnelforge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyServerRuntimeTest {

    @Test
    fun endpointSummaryListsEnabledListeners() {
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = 8080, socksEnabled = true, socksPort = 1080),
                logger = {},
            )

        assertEquals("HTTP 127.0.0.1:8080, SOCKS5 127.0.0.1:1080", runtime.endpointSummary())
    }

    @Test
    fun endpointSummaryReflectsLanBinding() {
        val runtime =
            ProxyServerRuntime(
                config =
                    ProxyRuntimeConfig(
                        httpEnabled = true,
                        httpPort = 8080,
                        socksEnabled = true,
                        socksPort = 1080,
                        allowLanConnections = true,
                        exposure =
                            ProxyExposureInfo(
                                active = true,
                                bindAddress = "192.168.1.24",
                                displayAddress = "192.168.1.24",
                                httpPort = 8080,
                                socksPort = 1080,
                                lanRequested = true,
                                lanActive = true,
                            ),
                    ),
                logger = {},
            )

        assertEquals("HTTP 192.168.1.24:8080, SOCKS5 192.168.1.24:1080", runtime.endpointSummary())
    }

    @Test
    fun lanBindingAlsoKeepsLoopbackListenerAvailable() {
        val exposure =
            ProxyExposureInfo(
                active = true,
                bindAddress = "192.168.1.24",
                displayAddress = "192.168.1.24",
                httpPort = 8080,
                socksPort = 1080,
                lanRequested = true,
                lanActive = true,
            )

        assertEquals(listOf("127.0.0.1", "192.168.1.24"), exposure.listenerBindAddresses())
    }

    @Test
    fun startRejectsDuplicatePorts() {
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = 8080, socksEnabled = true, socksPort = 8080),
                logger = {},
            )

        try {
            runtime.start()
            throw AssertionError("Expected duplicate-port validation to fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun startFailsWhenPortAlreadyBound() {
        ServerSocket(0).use { occupied: ServerSocket ->
            val runtime =
                ProxyServerRuntime(
                    config =
                        ProxyRuntimeConfig(
                            httpEnabled = true,
                            httpPort = occupied.localPort,
                            socksEnabled = false,
                            socksPort = 1080,
                        ),
                    logger = {},
                )

            try {
                runtime.start()
                throw AssertionError("Expected listener bind failure")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun httpConnectUsesTransport() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return successfulSession(request)
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write("CONNECT 93.184.216.34:443 HTTP/1.1\r\nHost: 93.184.216.34:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 200 Connection Established"))
            }
            assertEquals(1, requests.size)
            assertEquals("93.184.216.34", requests.first().host)
            assertEquals(443, requests.first().port)
            assertEquals("http-connect", requests.first().protocol)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectAcceptsLeadingBlankLines() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return successfulSession(request)
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write("\r\n\r\nCONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 200 Connection Established"))
            }
            assertEquals(1, requests.size)
            assertEquals("example.com", requests.first().host)
            assertEquals(443, requests.first().port)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpRejectsTlsClientHelloOnHttpPortBeforeOpeningTransport() {
        val logs = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return successfulSession(request)
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = { message -> logs.add(message) },
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x2f, 0x01, 0x00, 0x00))
                output.flush()
                val response = readResponseWithBody(input)
                assertTrue(response.startsWith("HTTP/1.1 400 Bad Request"))
                assertTrue(response.contains("HTTP proxy port expects a cleartext HTTP proxy request."))
            }
            assertEquals(0, requests.size)
            assertTrue(logs.any { it.contains("reason=protocol-mismatch") && it.contains("detected=tls-client-hello") })
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpRejectsSocksGreetingOnHttpPortBeforeOpeningTransport() {
        val logs = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return successfulSession(request)
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = { message -> logs.add(message) },
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                val response = readResponseWithBody(input)
                assertTrue(response.startsWith("HTTP/1.1 400 Bad Request"))
                assertTrue(response.contains("HTTP proxy port expects a cleartext HTTP proxy request."))
            }
            assertEquals(0, requests.size)
            assertTrue(logs.any { it.contains("reason=protocol-mismatch") && it.contains("detected=socks5-greeting") })
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectClearsHandshakeReadTimeoutBeforeTransportPump() {
        val observedTimeouts = CopyOnWriteArrayList<Int>()
        val readLatch = CountDownLatch(1)
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                    object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) = Unit

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            observedTimeouts.add(client.socket.soTimeout)
                            client.input.readNBytes(1)
                            readLatch.countDown()
                        }

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write("CONNECT 93.184.216.34:443 HTTP/1.1\r\nHost: 93.184.216.34:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 200 Connection Established"))
                output.write(byteArrayOf('x'.code.toByte()))
                output.flush()
                assertTrue(readLatch.await(1, TimeUnit.SECONDS))
            }
            assertEquals(listOf(0), observedTimeouts)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectPreservesPrefetchedClientBytes() {
        val received = CopyOnWriteArrayList<String>()
        val readLatch = CountDownLatch(1)
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                    object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) = Unit

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            received.add(String(client.input.readNBytes(5), Charsets.UTF_8))
                            readLatch.countDown()
                        }

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    "CONNECT 93.184.216.34:443 HTTP/1.1\r\nHost: 93.184.216.34:443\r\n\r\nhello"
                        .toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 200 Connection Established"))
                assertTrue(readLatch.await(1, TimeUnit.SECONDS))
            }
            assertEquals(listOf("hello"), received)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectPassesHostnameTargetsToTransport() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return successfulSession(request)
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 200 Connection Established"))
            }
            assertEquals(1, requests.size)
            assertEquals("example.com", requests.first().host)
            assertEquals(443, requests.first().port)
            assertEquals("http-connect", requests.first().protocol)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectAcceptsHttp10ProxyHeadersAndCustomPorts() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val logs = CopyOnWriteArrayList<String>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return successfulSession(request)
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = { message -> logs.add(message) },
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    (
                        "CONNECT example.com:8443 HTTP/1.0\r\n" +
                            "Proxy-Connection: keep-alive\r\n" +
                            "Proxy-Authorization: Basic dGVzdA==\r\n" +
                            "\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 200 Connection Established"))
            }
            assertEquals(1, requests.size)
            assertEquals("example.com", requests.first().host)
            assertEquals(8443, requests.first().port)
            assertEquals("http-connect", requests.first().protocol)
            assertTrue(logs.any { it.contains("proto=http-connect") && it.contains("version=HTTP/1.0") && it.contains("proxy-authorization") })
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectLocalUnavailableLogs503Diagnostics() {
        val logs = CopyOnWriteArrayList<String>()
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = { message -> logs.add(message) },
                transport = StubProxyTransport("Proxy packet bridge is not active."),
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 503 Service Unavailable"))
            }
            assertTrue(
                logs.any {
                    it.contains("proxy http error id=") &&
                        it.contains("proto=http-connect") &&
                        it.contains("phase=pre-success") &&
                        it.contains("reason=localServiceUnavailable") &&
                        it.contains("status=503") &&
                        it.contains("close=true") &&
                        it.contains("clients=") &&
                        it.contains("running=true")
                },
            )
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectDirectTunnelAllowsClientHalfCloseBeforeRemoteResponse() {
        val upstreamSawEof = CountDownLatch(1)
        val upstreamServer = ServerSocket(0)
        val upstreamThread =
            Thread {
                upstreamServer.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    assertEquals("ping", input.readNBytes(4).toString(StandardCharsets.US_ASCII))
                    assertEquals(-1, input.read())
                    upstreamSawEof.countDown()
                    output.write("pong".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                }
            }
        upstreamThread.start()
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = DirectSocketProxyTransport(),
        )
        runtime.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2_000
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    "CONNECT 127.0.0.1:${upstreamServer.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${upstreamServer.localPort}\r\n\r\n"
                        .toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                assertTrue(readResponse(input).startsWith("HTTP/1.1 200 Connection Established"))
                output.write("ping".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                socket.shutdownOutput()
                assertEquals("pong", input.readNBytes(4).toString(StandardCharsets.US_ASCII))
                assertTrue(upstreamSawEof.await(1, TimeUnit.SECONDS))
            }
        } finally {
            runtime.stop()
            upstreamServer.close()
            upstreamThread.join(1_000)
        }
    }

    @Test
    fun httpProxyForwardsAbsoluteFormRequestsInOriginForm() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val forwarded = CopyOnWriteArrayList<String>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) = Unit

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            forwarded.add(readForwardedRequest(client.input))
                            client.output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            client.output.flush()
                        }

                        override fun close() = Unit
                    }
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    "GET http://example.com/generate_204 HTTP/1.1\r\nHost: wrong.example\r\nProxy-Connection: keep-alive\r\nUser-Agent: proxy-test\r\n\r\n"
                        .toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 204 No Content"))
            }
            assertEquals(1, requests.size)
            assertEquals("example.com", requests.first().host)
            assertEquals(80, requests.first().port)
            assertEquals("http-proxy", requests.first().protocol)
            assertTrue(forwarded.first().startsWith("GET /generate_204 HTTP/1.1\r\n"))
            assertTrue(forwarded.first().contains("Host: example.com\r\n"))
            assertTrue(!forwarded.first().contains("Connection: close\r\n"))
            assertTrue(!forwarded.first().contains("Proxy-Connection:"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpProxyForwardsExplicitPortTargets() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val forwarded = CopyOnWriteArrayList<String>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) = Unit

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            forwarded.add(readForwardedRequest(client.input))
                            client.output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            client.output.flush()
                        }

                        override fun close() = Unit
                    }
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    "GET http://example.com:8080/status?ok=1 HTTP/1.1\r\nHost: example.com:8080\r\n\r\n"
                        .toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 204 No Content"))
            }
            assertEquals(1, requests.size)
            assertEquals("example.com", requests.first().host)
            assertEquals(8080, requests.first().port)
            assertTrue(forwarded.first().startsWith("GET /status?ok=1 HTTP/1.1\r\n"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpProxyAcceptsOriginFormRequestsWithHostHeader() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val forwarded = CopyOnWriteArrayList<String>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) = Unit

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            forwarded.add(readForwardedRequest(client.input))
                            client.output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            client.output.flush()
                        }

                        override fun close() = Unit
                    }
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    "GET /status HTTP/1.1\r\nHost: example.com:8080\r\n\r\n".toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 204 No Content"))
            }
            assertEquals(1, requests.size)
            assertEquals("example.com", requests.first().host)
            assertEquals(8080, requests.first().port)
            assertTrue(forwarded.first().startsWith("GET /status HTTP/1.1\r\n"))
            assertTrue(forwarded.first().contains("Host: example.com:8080\r\n"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpProxyPreservesRequestBodies() {
        val forwarded = CopyOnWriteArrayList<String>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                    object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) = Unit

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            forwarded.add(readForwardedRequest(client.input, bodyBytes = 5))
                            client.output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            client.output.flush()
                        }

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    "POST http://example.com/upload HTTP/1.1\r\nHost: example.com\r\nContent-Length: 5\r\n\r\nhello"
                        .toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 204 No Content"))
            }
            assertTrue(forwarded.first().startsWith("POST /upload HTTP/1.1\r\n"))
            assertTrue(forwarded.first().endsWith("\r\n\r\nhello"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpProxyParsesRequestLineWithFlexibleWhitespace() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val forwarded = CopyOnWriteArrayList<String>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) = Unit

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            forwarded.add(readForwardedRequest(client.input, bodyBytes = 5))
                            client.output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            client.output.flush()
                        }

                        override fun close() = Unit
                    }
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    "POST      http://example.com/upload      HTTP/1.1\r\nHost: example.com\r\nContent-Length: 5\r\n\r\nhello"
                        .toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 204 No Content"))
            }
            assertEquals(1, requests.size)
            assertEquals("example.com", requests.first().host)
            assertEquals(80, requests.first().port)
            assertTrue(forwarded.first().startsWith("POST /upload HTTP/1.1\r\n"))
            assertTrue(forwarded.first().endsWith("\r\n\r\nhello"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpProxyForwardsHttpsAbsoluteFormRequestsThroughSecureSocket() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val forwarded = CopyOnWriteArrayList<String>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) = Unit

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            forwarded.add(readForwardedRequest(client.input))
                            client.output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            client.output.flush()
                        }

                        override fun close() = Unit
                    }
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
                secureSocketFactory = PassthroughSecureSocketFactory,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    "GET https://example.com/status HTTP/1.1\r\nHost: wrong.example\r\nProxy-Connection: keep-alive\r\n\r\n".toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 204 No Content"))
            }
            assertEquals(1, requests.size)
            assertEquals("example.com", requests.first().host)
            assertEquals(443, requests.first().port)
            assertEquals("https-proxy", requests.first().protocol)
            assertTrue(forwarded.first().startsWith("GET /status HTTP/1.1\r\n"))
            assertTrue(forwarded.first().contains("Host: example.com\r\n"))
            assertTrue(!forwarded.first().contains("Connection: close\r\n"))
            assertTrue(!forwarded.first().contains("Proxy-Connection:"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpProxyForwardsHttpsAbsoluteFormRequestsWithExplicitPort() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val forwarded = CopyOnWriteArrayList<String>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) = Unit

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            forwarded.add(readForwardedRequest(client.input, bodyBytes = 5))
                            client.output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            client.output.flush()
                        }

                        override fun close() = Unit
                    }
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
                secureSocketFactory = PassthroughSecureSocketFactory,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    "POST https://example.com:8443/upload HTTP/1.1\r\nHost: wrong.example\r\nContent-Length: 5\r\n\r\nhello".toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 204 No Content"))
            }
            assertEquals(1, requests.size)
            assertEquals("example.com", requests.first().host)
            assertEquals(8443, requests.first().port)
            assertEquals("https-proxy", requests.first().protocol)
            assertTrue(forwarded.first().startsWith("POST /upload HTTP/1.1\r\n"))
            assertTrue(forwarded.first().contains("Host: example.com:8443\r\n"))
            assertTrue(forwarded.first().endsWith("\r\n\r\nhello"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpProxyReturnsServiceUnavailableWhenSecureSocketSetupFails() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return successfulSession(request)
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
                secureSocketFactory =
                    object : ProxySecureSocketFactory {
                        override fun create(socket: Socket, host: String, port: Int): Socket {
                            throw java.io.IOException("handshake failed")
                        }
                    },
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    "GET https://example.com/status HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 502 Bad Gateway"))
            }
            assertEquals(1, requests.size)
            assertEquals("https-proxy", requests.first().protocol)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectWaitsForTransportConnectBeforeSuccessResponse() {
        val allowConnect = CountDownLatch(1)
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                    object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) {
                            if (!allowConnect.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                                throw java.io.IOException("connect timed out")
                            }
                        }

                        override fun pumpBidirectional(client: ProxyClientConnection) = Unit

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
                connectTimeoutMs = 500,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 150
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write("CONNECT 93.184.216.34:443 HTTP/1.1\r\nHost: 93.184.216.34:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                try {
                    input.read()
                    throw AssertionError("Expected CONNECT response to wait for transport establishment")
                } catch (_: SocketTimeoutException) {
                }
                allowConnect.countDown()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 200 Connection Established"))
            }
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectReturnsTransportFailureWhenConnectWaitFails() {
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                    object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) {
                            throw java.io.IOException("connect failed")
                        }

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            throw AssertionError("pumpBidirectional should not run after connect failure")
                        }

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
                connectTimeoutMs = 200,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write("CONNECT 93.184.216.34:443 HTTP/1.1\r\nHost: 93.184.216.34:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 502 Bad Gateway"))
            }
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectReturnsTransportFailureForHostnameResolutionErrors() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    throw java.io.IOException("dns failed")
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 502 Bad Gateway"))
            }
            assertEquals(1, requests.size)
            assertEquals("example.com", requests.first().host)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectReturnsGatewayTimeoutWhenTransportTimesOut() {
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                    object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) {
                            throw java.io.IOException("connect timed out")
                        }

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            throw AssertionError("pumpBidirectional should not run after connect timeout")
                        }

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
                connectTimeoutMs = 200,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write("CONNECT 93.184.216.34:443 HTTP/1.1\r\nHost: 93.184.216.34:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 504 Gateway Timeout"))
            }
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectUsesResponseDeadlineBeforeTransportBudget() {
        val observedTimeouts = CopyOnWriteArrayList<Long>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                    object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) {
                            observedTimeouts.add(timeoutMs)
                            throw ProxyTransportException(
                                ProxyTransportFailureReason.upstreamTimeout,
                                "TCP connect timed out connectDiag=inboundTcp=0 synAck=0 rst=0 icmpUnreachable=0 sourceMismatchDrops=0.",
                            )
                        }

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            throw AssertionError("pumpBidirectional should not run after connect timeout")
                        }

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
                connectTimeoutMs = 60_000,
                connectResponseTimeoutMs = 25_000,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write("CONNECT 93.184.216.34:443 HTTP/1.1\r\nHost: 93.184.216.34:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                val response = readResponseWithBody(input)
                assertTrue(response.startsWith("HTTP/1.1 504 Gateway Timeout"))
                assertTrue(response.contains("connectDiag=inboundTcp=0"))
            }
            assertEquals(listOf(25_000L), observedTimeouts)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectLogsClientAbortWhenErrorResponseCannotBeWritten() {
        val logs = CopyOnWriteArrayList<String>()
        val closeLatch = CountDownLatch(1)
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                    object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(7, request, 0)

                        override fun awaitConnected(timeoutMs: Long) {
                            Thread.sleep(150)
                            throw ProxyTransportException(ProxyTransportFailureReason.upstreamTimeout, "TCP connect timed out.")
                        }

                        override fun pumpBidirectional(client: ProxyClientConnection) = Unit

                        override fun close() {
                            closeLatch.countDown()
                        }
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                levelLogger = { _, message -> logs.add(message) },
                transport = transport,
                connectTimeoutMs = 200,
                connectResponseTimeoutMs = 200,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                socket.setSoLinger(true, 0)
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write("CONNECT 93.184.216.34:443 HTTP/1.1\r\nHost: 93.184.216.34:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
            }
            assertTrue(closeLatch.await(1, TimeUnit.SECONDS))
            repeat(40) {
                if (logs.any { it.contains("reason=clientAborted") }) return@repeat
                Thread.sleep(25)
            }
            assertTrue(logs.any { it.contains("reason=clientAborted") && it.contains("phase=pre-success") })
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpProxyKeepsPlainHttpConnectionOpenForFramedSequentialRequests() {
        val upstreamRequests = AtomicInteger(0)
        val upstreamServer = ServerSocket(0)
        val upstreamThread =
            Thread {
                upstreamServer.accept().use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    val output = BufferedOutputStream(socket.getOutputStream())
                    readForwardedRequest(input)
                    upstreamRequests.incrementAndGet()
                    output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                    readForwardedRequest(input)
                    upstreamRequests.incrementAndGet()
                    output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                }
            }.also { it.start() }
        val transport =
            DirectSocketProxyTransport(
                resolver = { listOf(java.net.InetAddress.getByName("127.0.0.1")) },
            )
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 1_000
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    (
                        "GET http://127.0.0.1:${upstreamServer.localPort}/one HTTP/1.1\r\n" +
                            "Host: 127.0.0.1:${upstreamServer.localPort}\r\n" +
                            "\r\n" +
                            "GET http://127.0.0.1:${upstreamServer.localPort}/two HTTP/1.1\r\n" +
                            "Host: 127.0.0.1:${upstreamServer.localPort}\r\n" +
                            "Connection: close\r\n" +
                            "\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                assertTrue(readResponse(input).startsWith("HTTP/1.1 204 No Content"))
                assertTrue(readResponse(input).startsWith("HTTP/1.1 204 No Content"))
            }
            upstreamThread.join(2_000)
            assertEquals(2, upstreamRequests.get())
        } finally {
            upstreamServer.close()
            runtime.stop()
        }
    }

    @Test
    fun httpProxyRelaysChunkedResponsesWithMessageFraming() {
        val upstreamServer = ServerSocket(0)
        val upstreamThread =
            Thread {
                upstreamServer.accept().use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    val output = BufferedOutputStream(socket.getOutputStream())
                    readForwardedRequest(input)
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Transfer-Encoding: chunked\r\n" +
                                "\r\n" +
                                "4\r\npong\r\n0\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII),
                    )
                    output.flush()
                }
            }.also { it.start() }
        val transport =
            DirectSocketProxyTransport(
                resolver = { listOf(java.net.InetAddress.getByName("127.0.0.1")) },
            )
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 1_000
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    (
                        "GET http://127.0.0.1:${upstreamServer.localPort}/chunked HTTP/1.1\r\n" +
                            "Host: 127.0.0.1:${upstreamServer.localPort}\r\n" +
                            "Connection: close\r\n" +
                            "\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val headers = readResponse(input)
                assertTrue(headers.startsWith("HTTP/1.1 200 OK"))
                assertEquals("4\r\npong\r\n0\r\n\r\n", input.readNBytes(14).toString(StandardCharsets.US_ASCII))
            }
            upstreamThread.join(2_000)
        } finally {
            upstreamServer.close()
            runtime.stop()
        }
    }

    @Test
    fun httpConnectDoesNotWriteHttpErrorAfterSuccessHandshake() {
        val logs = CopyOnWriteArrayList<String>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                    object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) = Unit

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            throw java.io.IOException("remote reset after handshake")
                        }

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = { message -> logs.add(message) },
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 1_000
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write("CONNECT 93.184.216.34:443 HTTP/1.1\r\nHost: 93.184.216.34:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                assertTrue(readResponse(input).startsWith("HTTP/1.1 200 Connection Established"))
                assertEquals(-1, input.read())
            }
            assertTrue(logs.any { it.contains("proto=http-connect") && it.contains("phase=post-success") })
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpConnectRejectsIpv6TargetsBeforeTransport() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    throw AssertionError("Transport should not be called for IPv6 targets")
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write("CONNECT [2001:db8::1]:443 HTTP/1.1\r\nHost: [2001:db8::1]:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                val response = readResponse(input)
                assertTrue(response.startsWith("HTTP/1.1 501 Not Implemented"))
            }
            assertTrue(requests.isEmpty())
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun socksConnectUsesTransportFailureResponse() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    throw java.io.IOException("not ready")
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = false, httpPort = 8080, socksEnabled = true, socksPort = port),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())

                output.write(
                    byteArrayOf(
                        0x05,
                        0x01,
                        0x00,
                        0x01,
                        93.toByte(),
                        184.toByte(),
                        216.toByte(),
                        34,
                    ) + byteArrayOf(0x01, 0xbb.toByte()),
                )
                output.flush()

                assertEquals(0x05, input.read())
                assertEquals(0x01, input.read())
            }
            assertEquals(1, requests.size)
            assertEquals("93.184.216.34", requests.first().host)
            assertEquals(443, requests.first().port)
            assertEquals("socks5-connect", requests.first().protocol)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun socksConnectPreservesPrefetchedClientBytes() {
        val received = CopyOnWriteArrayList<String>()
        val readLatch = CountDownLatch(1)
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                    object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) = Unit

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            received.add(String(client.input.readNBytes(5), Charsets.UTF_8))
                            readLatch.countDown()
                        }

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = false, httpPort = 8080, socksEnabled = true, socksPort = port),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())

                output.write(
                    byteArrayOf(
                        0x05,
                        0x01,
                        0x00,
                        0x01,
                        93.toByte(),
                        184.toByte(),
                        216.toByte(),
                        34,
                    ) + byteArrayOf(0x01, 0xbb.toByte()) + "hello".toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()

                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())
                assertTrue(readLatch.await(1, TimeUnit.SECONDS))
            }
            assertEquals(listOf("hello"), received)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun socksConnectPassesHostnameTargetsToTransport() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return successfulSession(request)
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = false, httpPort = 8080, socksEnabled = true, socksPort = port),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())
                output.write(
                    byteArrayOf(
                        0x05,
                        0x01,
                        0x00,
                        0x03,
                        0x0b,
                    ) + "example.com".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0x01, 0xbb.toByte()),
                )
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())
            }
            assertEquals(1, requests.size)
            assertEquals("example.com", requests.first().host)
            assertEquals(443, requests.first().port)
            assertEquals("socks5-connect", requests.first().protocol)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun socksConnectWaitsForTransportConnectBeforeSuccessReply() {
        val allowConnect = CountDownLatch(1)
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                    object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) {
                            if (!allowConnect.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                                throw java.io.IOException("connect timed out")
                            }
                        }

                        override fun pumpBidirectional(client: ProxyClientConnection) = Unit

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = false, httpPort = 8080, socksEnabled = true, socksPort = port),
                logger = {},
                transport = transport,
                connectTimeoutMs = 500,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 150
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())

                output.write(
                    byteArrayOf(
                        0x05,
                        0x01,
                        0x00,
                        0x01,
                        93.toByte(),
                        184.toByte(),
                        216.toByte(),
                        34,
                    ) + byteArrayOf(0x01, 0xbb.toByte()),
                )
                output.flush()
                try {
                    input.read()
                    throw AssertionError("Expected SOCKS success reply to wait for transport establishment")
                } catch (_: SocketTimeoutException) {
                }
                allowConnect.countDown()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())
            }
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun socksConnectDoesNotWriteSecondReplyAfterSuccessHandshake() {
        val logs = CopyOnWriteArrayList<String>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                    object : ProxyTransportSession {
                        override val descriptor = ProxySessionDescriptor(1, request, 0)

                        override fun awaitConnected(timeoutMs: Long) = Unit

                        override fun pumpBidirectional(client: ProxyClientConnection) {
                            throw java.io.IOException("remote reset after handshake")
                        }

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = false, httpPort = 8080, socksEnabled = true, socksPort = port),
                logger = { message -> logs.add(message) },
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 1_000
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())

                output.write(
                    byteArrayOf(
                        0x05,
                        0x01,
                        0x00,
                        0x01,
                        93.toByte(),
                        184.toByte(),
                        216.toByte(),
                        34,
                    ) + byteArrayOf(0x01, 0xbb.toByte()),
                )
                output.flush()

                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())
                assertEquals(0x00, input.read())
                assertEquals(0x01, input.read())
                repeat(6) { assertTrue(input.read() >= 0) }
                assertEquals(-1, input.read())
            }
            assertTrue(logs.any { it.contains("proto=socks5-connect") && it.contains("phase=post-success") })
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun socksConnectRejectsIpv6TargetsBeforeTransport() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    throw AssertionError("Transport should not be called for IPv6 targets")
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = false, httpPort = 8080, socksEnabled = true, socksPort = port),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())
                output.write(
                    byteArrayOf(
                        0x05,
                        0x01,
                        0x00,
                        0x04,
                    ) + ByteArray(16) { 0 } + byteArrayOf(0x01, 0xbb.toByte()),
                )
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x04, input.read())
            }
            assertTrue(requests.isEmpty())
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun socksRejectsUnsupportedCommandBeforeTransport() {
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    throw AssertionError("Transport should not be called for unsupported SOCKS5 commands")
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = false, httpPort = 8080, socksEnabled = true, socksPort = port),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())
                output.write(
                    byteArrayOf(
                        0x05,
                        0x02,
                        0x00,
                        0x01,
                        93.toByte(),
                        184.toByte(),
                        216.toByte(),
                        34,
                    ) + byteArrayOf(0x01, 0xbb.toByte()),
                )
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x07, input.read())
            }
            assertTrue(requests.isEmpty())
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun socksUdpAssociateRelaysDatagramsThroughTransport() {
        val sent = CopyOnWriteArrayList<ProxyUdpDatagram>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    throw AssertionError("TCP transport should not be used for UDP ASSOCIATE")
                }

                override fun openUdpAssociation(request: ProxyConnectRequest): ProxyUdpAssociation {
                    val replies = ArrayBlockingQueue<ProxyUdpDatagram>(4)
                    return object : ProxyUdpAssociation {
                        override val descriptor = ProxySessionDescriptor(7, request, 0)

                        override fun send(datagram: ProxyUdpDatagram) {
                            sent.add(datagram)
                            replies.offer(
                                ProxyUdpDatagram(
                                    host = "127.0.0.1",
                                    port = datagram.port,
                                    payload = "pong".toByteArray(StandardCharsets.US_ASCII),
                                ),
                            )
                        }

                        override fun receive(timeoutMs: Long): ProxyUdpDatagram? =
                            replies.poll(timeoutMs, TimeUnit.MILLISECONDS)

                        override fun close() = Unit
                    }
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = false, httpPort = 8080, socksEnabled = true, socksPort = port),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { control ->
                val output = BufferedOutputStream(control.getOutputStream())
                val input = BufferedInputStream(control.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())
                output.write(
                    byteArrayOf(
                        0x05,
                        0x03,
                        0x00,
                        0x01,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                    ),
                )
                output.flush()
                val reply = input.readNBytes(10)
                assertEquals(0x05, reply[0].toInt() and 0xff)
                assertEquals(0x00, reply[1].toInt() and 0xff)
                val relayPort = ((reply[8].toInt() and 0xff) shl 8) or (reply[9].toInt() and 0xff)
                assertTrue(relayPort > 0)

                DatagramSocket().use { udp ->
                    udp.soTimeout = 2_000
                    val request =
                        byteArrayOf(
                            0x00,
                            0x00,
                            0x00,
                            0x01,
                            127,
                            0,
                            0,
                            1,
                            0x14,
                            0xe9.toByte(),
                        ) + "ping".toByteArray(StandardCharsets.US_ASCII)
                    udp.send(
                        DatagramPacket(
                            request,
                            request.size,
                            java.net.InetAddress.getByName("127.0.0.1"),
                            relayPort,
                        ),
                    )
                    val responseBuffer = ByteArray(64)
                    val response = DatagramPacket(responseBuffer, responseBuffer.size)
                    udp.receive(response)
                    val responseBytes = response.data.copyOfRange(response.offset, response.offset + response.length)
                    assertEquals("pong", responseBytes.copyOfRange(10, responseBytes.size).toString(StandardCharsets.US_ASCII))
                }
            }
            assertEquals(1, sent.size)
            assertEquals("127.0.0.1", sent.first().host)
            assertEquals(5353, sent.first().port)
            assertEquals("ping", sent.first().payload.toString(StandardCharsets.US_ASCII))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun socksUdpAssociateDropsSendFailuresWithoutClosingAssociation() {
        val sent = CopyOnWriteArrayList<ProxyUdpDatagram>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    throw AssertionError("TCP transport should not be used for UDP ASSOCIATE")
                }

                override fun openUdpAssociation(request: ProxyConnectRequest): ProxyUdpAssociation {
                    val replies = ArrayBlockingQueue<ProxyUdpDatagram>(4)
                    return object : ProxyUdpAssociation {
                        override val descriptor = ProxySessionDescriptor(10, request, 0)

                        override fun send(datagram: ProxyUdpDatagram) {
                            if (datagram.payload.toString(StandardCharsets.US_ASCII) == "bad") {
                                throw java.io.IOException("send failed")
                            }
                            sent.add(datagram)
                            replies.offer(
                                ProxyUdpDatagram(
                                    host = "127.0.0.1",
                                    port = datagram.port,
                                    payload = "pong".toByteArray(StandardCharsets.US_ASCII),
                                ),
                            )
                        }

                        override fun receive(timeoutMs: Long): ProxyUdpDatagram? =
                            replies.poll(timeoutMs, TimeUnit.MILLISECONDS)

                        override fun close() = Unit
                    }
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = false, httpPort = 8080, socksEnabled = true, socksPort = port),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { control ->
                val output = BufferedOutputStream(control.getOutputStream())
                val input = BufferedInputStream(control.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())
                output.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                val reply = input.readNBytes(10)
                assertEquals(0x00, reply[1].toInt() and 0xff)
                val relayPort = ((reply[8].toInt() and 0xff) shl 8) or (reply[9].toInt() and 0xff)

                DatagramSocket().use { udp ->
                    udp.soTimeout = 2_000
                    fun socksUdpRequest(payload: String): ByteArray =
                        byteArrayOf(
                            0x00,
                            0x00,
                            0x00,
                            0x01,
                            127,
                            0,
                            0,
                            1,
                            0x14,
                            0xe9.toByte(),
                        ) + payload.toByteArray(StandardCharsets.US_ASCII)

                    udp.send(
                        DatagramPacket(
                            socksUdpRequest("bad"),
                            socksUdpRequest("bad").size,
                            java.net.InetAddress.getByName("127.0.0.1"),
                            relayPort,
                        ),
                    )
                    udp.send(
                        DatagramPacket(
                            socksUdpRequest("good"),
                            socksUdpRequest("good").size,
                            java.net.InetAddress.getByName("127.0.0.1"),
                            relayPort,
                        ),
                    )

                    val responseBuffer = ByteArray(64)
                    val response = DatagramPacket(responseBuffer, responseBuffer.size)
                    udp.receive(response)
                    val responseBytes = response.data.copyOfRange(response.offset, response.offset + response.length)
                    assertEquals("pong", responseBytes.copyOfRange(10, responseBytes.size).toString(StandardCharsets.US_ASCII))
                }
            }
            assertEquals(1, sent.size)
            assertEquals("good", sent.first().payload.toString(StandardCharsets.US_ASCII))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun socksUdpAssociateHonorsExplicitClientEndpoint() {
        val sent = CopyOnWriteArrayList<ProxyUdpDatagram>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    throw AssertionError("TCP transport should not be used for UDP ASSOCIATE")
                }

                override fun openUdpAssociation(request: ProxyConnectRequest): ProxyUdpAssociation =
                    object : ProxyUdpAssociation {
                        override val descriptor = ProxySessionDescriptor(8, request, 0)

                        override fun send(datagram: ProxyUdpDatagram) {
                            sent.add(datagram)
                        }

                        override fun receive(timeoutMs: Long): ProxyUdpDatagram? = null

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = false, httpPort = 8080, socksEnabled = true, socksPort = port),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            DatagramSocket().use { expectedUdp ->
                DatagramSocket().use { rogueUdp ->
                    Socket("127.0.0.1", port).use { control ->
                        val output = BufferedOutputStream(control.getOutputStream())
                        val input = BufferedInputStream(control.getInputStream())
                        output.write(byteArrayOf(0x05, 0x01, 0x00))
                        output.flush()
                        assertEquals(0x05, input.read())
                        assertEquals(0x00, input.read())
                        output.write(
                            byteArrayOf(
                                0x05,
                                0x03,
                                0x00,
                                0x01,
                                127,
                                0,
                                0,
                                1,
                                ((expectedUdp.localPort ushr 8) and 0xff).toByte(),
                                (expectedUdp.localPort and 0xff).toByte(),
                            ),
                        )
                        output.flush()
                        val reply = input.readNBytes(10)
                        assertEquals(0x00, reply[1].toInt() and 0xff)
                        val relayPort = ((reply[8].toInt() and 0xff) shl 8) or (reply[9].toInt() and 0xff)

                        val request =
                            byteArrayOf(
                                0x00,
                                0x00,
                                0x00,
                                0x01,
                                127,
                                0,
                                0,
                                1,
                                0x14,
                                0xe9.toByte(),
                            ) + "ok".toByteArray(StandardCharsets.US_ASCII)
                        rogueUdp.send(
                            DatagramPacket(
                                request,
                                request.size,
                                java.net.InetAddress.getByName("127.0.0.1"),
                                relayPort,
                            ),
                        )
                        Thread.sleep(100)
                        assertTrue(sent.isEmpty())

                        expectedUdp.send(
                            DatagramPacket(
                                request,
                                request.size,
                                java.net.InetAddress.getByName("127.0.0.1"),
                                relayPort,
                            ),
                        )
                        repeat(20) {
                            if (sent.isNotEmpty()) return@repeat
                            Thread.sleep(25)
                        }
                    }
                }
            }
            assertEquals(1, sent.size)
            assertEquals("ok", sent.first().payload.toString(StandardCharsets.US_ASCII))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun socksUdpAssociateRejectsUnresolvableExplicitEndpointBeforeSuccess() {
        val opened = AtomicBoolean(false)
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    throw AssertionError("TCP transport should not be used for UDP ASSOCIATE")
                }

                override fun openUdpAssociation(request: ProxyConnectRequest): ProxyUdpAssociation {
                    opened.set(true)
                    return object : ProxyUdpAssociation {
                        override val descriptor = ProxySessionDescriptor(11, request, 0)

                        override fun send(datagram: ProxyUdpDatagram) = Unit

                        override fun receive(timeoutMs: Long): ProxyUdpDatagram? = null

                        override fun close() = Unit
                    }
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = false, httpPort = 8080, socksEnabled = true, socksPort = port),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { control ->
                control.soTimeout = 1_000
                val output = BufferedOutputStream(control.getOutputStream())
                val input = BufferedInputStream(control.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())

                val hostBytes = "999.999.999.999".toByteArray(StandardCharsets.US_ASCII)
                output.write(
                    byteArrayOf(
                        0x05,
                        0x03,
                        0x00,
                        0x03,
                        hostBytes.size.toByte(),
                    ) + hostBytes +
                        byteArrayOf(
                            0x12,
                            0x34,
                        ),
                )
                output.flush()

                val reply = input.readNBytes(10)
                assertEquals(10, reply.size)
                assertEquals(0x05, reply[0].toInt() and 0xff)
                assertEquals(0x01, reply[1].toInt() and 0xff)
                assertTrue(!opened.get())

                control.soTimeout = 200
                try {
                    assertEquals(-1, input.read())
                } catch (_: SocketTimeoutException) {
                }
            }
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun socksUdpAssociateAcceptsShortDomainAddressedDatagram() {
        val sent = CopyOnWriteArrayList<ProxyUdpDatagram>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    throw AssertionError("TCP transport should not be used for UDP ASSOCIATE")
                }

                override fun openUdpAssociation(request: ProxyConnectRequest): ProxyUdpAssociation =
                    object : ProxyUdpAssociation {
                        override val descriptor = ProxySessionDescriptor(9, request, 0)

                        override fun send(datagram: ProxyUdpDatagram) {
                            sent.add(datagram)
                        }

                        override fun receive(timeoutMs: Long): ProxyUdpDatagram? = null

                        override fun close() = Unit
                    }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = false, httpPort = 8080, socksEnabled = true, socksPort = port),
                logger = {},
                transport = transport,
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { control ->
                val output = BufferedOutputStream(control.getOutputStream())
                val input = BufferedInputStream(control.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())
                output.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                val reply = input.readNBytes(10)
                assertEquals(0x00, reply[1].toInt() and 0xff)
                val relayPort = ((reply[8].toInt() and 0xff) shl 8) or (reply[9].toInt() and 0xff)

                DatagramSocket().use { udp ->
                    val request =
                        byteArrayOf(
                            0x00,
                            0x00,
                            0x00,
                            0x03,
                            0x01,
                            'a'.code.toByte(),
                            0x14,
                            0xe9.toByte(),
                            'x'.code.toByte(),
                        )
                    udp.send(
                        DatagramPacket(
                            request,
                            request.size,
                            java.net.InetAddress.getByName("127.0.0.1"),
                            relayPort,
                        ),
                    )
                    repeat(20) {
                        if (sent.isNotEmpty()) return@repeat
                        Thread.sleep(25)
                    }
                }
            }
            assertEquals(1, sent.size)
            assertEquals("a", sent.first().host)
            assertEquals(5353, sent.first().port)
            assertEquals("x", sent.first().payload.toString(StandardCharsets.US_ASCII))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun httpClientRuntimeExceptionIsCaughtAndLogged() {
        val logs = CopyOnWriteArrayList<String>()
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config = ProxyRuntimeConfig(httpEnabled = true, httpPort = port, socksEnabled = false, socksPort = 1080),
                logger = { message -> logs.add(message) },
                transport =
                    object : ProxyTransport {
                        override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
                            object : ProxyTransportSession {
                                override val descriptor = ProxySessionDescriptor(1, request, 0)

                                override fun awaitConnected(timeoutMs: Long) {
                                    throw IllegalStateException("boom")
                                }

                                override fun pumpBidirectional(client: ProxyClientConnection) = Unit

                                override fun close() = Unit
                            }
                    },
            )
        runtime.start()
        try {
            Thread.sleep(50)
            Socket("127.0.0.1", port).use { socket ->
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write("CONNECT 93.184.216.34:443 HTTP/1.1\r\nHost: 93.184.216.34:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
            }
            Thread.sleep(100)
        } finally {
            runtime.stop()
        }

        assertTrue(logs.any { it.contains("http client crash: IllegalStateException: boom") })
    }

    @Test
    fun httpRejectsWhenClientLimitIsReachedWithoutOpeningTransport() {
        val releasePump = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return blockingSession(request, releasePump)
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config =
                    ProxyRuntimeConfig(
                        httpEnabled = true,
                        httpPort = port,
                        socksEnabled = false,
                        socksPort = 1080,
                        maxConcurrentClients = 1,
                    ),
                logger = {},
                transport = transport,
            )
        runtime.start()
        val first = Socket("127.0.0.1", port)
        try {
            val firstOutput = BufferedOutputStream(first.getOutputStream())
            val firstInput = BufferedInputStream(first.getInputStream())
            firstOutput.write("CONNECT 93.184.216.34:443 HTTP/1.1\r\nHost: 93.184.216.34:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            firstOutput.flush()
            assertTrue(readResponse(firstInput).startsWith("HTTP/1.1 200 Connection Established"))

            Socket("127.0.0.1", port).use { second ->
                val output = BufferedOutputStream(second.getOutputStream())
                val input = BufferedInputStream(second.getInputStream())
                output.write("CONNECT 93.184.216.35:443 HTTP/1.1\r\nHost: 93.184.216.35:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                assertTrue(readResponse(input).startsWith("HTTP/1.1 503 Service Unavailable"))
            }
            assertEquals(1, requests.size)
        } finally {
            releasePump.countDown()
            first.close()
            runtime.stop()
        }
    }

    @Test
    fun httpAllowsMoreThanLegacyClientLimitWhenClientCapIsNull() {
        val releasePump = CountDownLatch(1)
        val requests = AtomicInteger(0)
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.incrementAndGet()
                    return blockingSession(request, releasePump)
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config =
                    ProxyRuntimeConfig(
                        httpEnabled = true,
                        httpPort = port,
                        socksEnabled = false,
                        socksPort = 1080,
                        maxConcurrentClients = null,
                    ),
                logger = {},
                transport = transport,
            )
        runtime.start()
        val clients = ArrayList<Socket>()
        try {
            repeat(129) { index ->
                val client = Socket("127.0.0.1", port)
                clients += client
                val output = BufferedOutputStream(client.getOutputStream())
                val input = BufferedInputStream(client.getInputStream())
                output.write("CONNECT 93.184.216.${index + 1}:443 HTTP/1.1\r\nHost: 93.184.216.${index + 1}:443\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                assertTrue(readResponse(input).startsWith("HTTP/1.1 200 Connection Established"))
            }
            assertEquals(129, requests.get())
        } finally {
            releasePump.countDown()
            clients.forEach { it.close() }
            runtime.stop()
        }
    }

    @Test
    fun socksRejectsWhenClientLimitIsReachedWithoutOpeningTransport() {
        val releasePump = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<ProxyConnectRequest>()
        val transport =
            object : ProxyTransport {
                override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
                    requests.add(request)
                    return blockingSession(request, releasePump)
                }
            }
        val port = findFreePort()
        val runtime =
            ProxyServerRuntime(
                config =
                    ProxyRuntimeConfig(
                        httpEnabled = false,
                        httpPort = 8080,
                        socksEnabled = true,
                        socksPort = port,
                        maxConcurrentClients = 1,
                    ),
                logger = {},
                transport = transport,
            )
        runtime.start()
        val first = Socket("127.0.0.1", port)
        try {
            val firstOutput = BufferedOutputStream(first.getOutputStream())
            val firstInput = BufferedInputStream(first.getInputStream())
            firstOutput.write(byteArrayOf(0x05, 0x01, 0x00))
            firstOutput.flush()
            assertEquals(0x05, firstInput.read())
            assertEquals(0x00, firstInput.read())
            firstOutput.write(
                byteArrayOf(
                    0x05,
                    0x01,
                    0x00,
                    0x01,
                    93.toByte(),
                    184.toByte(),
                    216.toByte(),
                    34,
                ) + byteArrayOf(0x01, 0xbb.toByte()),
            )
            firstOutput.flush()
            assertEquals(0x05, firstInput.read())
            assertEquals(0x00, firstInput.read())

            Socket("127.0.0.1", port).use { second ->
                second.soTimeout = 1000
                val output = BufferedOutputStream(second.getOutputStream())
                val input = BufferedInputStream(second.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(-1, input.read())
            }
            assertEquals(1, requests.size)
        } finally {
            releasePump.countDown()
            first.close()
            runtime.stop()
        }
    }

    private fun successfulSession(request: ProxyConnectRequest): ProxyTransportSession =
        object : ProxyTransportSession {
            override val descriptor = ProxySessionDescriptor(1, request, 0)

            override fun awaitConnected(timeoutMs: Long) = Unit

            override fun pumpBidirectional(client: ProxyClientConnection) = Unit

            override fun close() = Unit
        }

    private fun blockingSession(request: ProxyConnectRequest, releasePump: CountDownLatch): ProxyTransportSession =
        object : ProxyTransportSession {
            override val descriptor = ProxySessionDescriptor(1, request, 0)

            override fun awaitConnected(timeoutMs: Long) = Unit

            override fun pumpBidirectional(client: ProxyClientConnection) {
                releasePump.await(5, TimeUnit.SECONDS)
            }

            override fun close() = Unit
        }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun readResponse(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        var match = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (true) {
            val next = input.read()
            if (next < 0) break
            val byte = next.toByte()
            bytes.add(byte)
            match = if (byte == terminator[match]) match + 1 else if (byte == terminator[0]) 1 else 0
            if (match == terminator.size) break
        }
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }

    private fun readResponseWithBody(input: BufferedInputStream): String {
        val headers = readResponse(input)
        val contentLength =
            Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                .find(headers)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0
        val body = if (contentLength > 0) input.readNBytes(contentLength) else ByteArray(0)
        return headers + body.toString(StandardCharsets.UTF_8)
    }

    private fun readForwardedRequest(
        input: InputStream,
        bodyBytes: Int = 0,
    ): String {
        val headerBytes = ArrayList<Byte>()
        var match = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (true) {
            val next = input.read()
            if (next < 0) break
            val byte = next.toByte()
            headerBytes.add(byte)
            match = if (byte == terminator[match]) match + 1 else if (byte == terminator[0]) 1 else 0
            if (match == terminator.size) break
        }
        val body = if (bodyBytes > 0) input.readNBytes(bodyBytes) else ByteArray(0)
        return (headerBytes.toByteArray() + body).toString(StandardCharsets.US_ASCII)
    }

    private object PassthroughSecureSocketFactory : ProxySecureSocketFactory {
        override fun create(socket: Socket, host: String, port: Int): Socket = socket
    }
}
