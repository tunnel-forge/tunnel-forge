package io.github.evokelektrique.tunnelforge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            assertTrue(forwarded.first().contains("Connection: close\r\n"))
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
            assertTrue(forwarded.first().contains("Connection: close\r\n"))
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
                assertTrue(response.startsWith("HTTP/1.1 503 Service Unavailable"))
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
                assertTrue(response.startsWith("HTTP/1.1 503 Service Unavailable"))
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
                assertTrue(response.startsWith("HTTP/1.1 503 Service Unavailable"))
            }
            assertEquals(1, requests.size)
            assertEquals("example.com", requests.first().host)
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

    private fun successfulSession(request: ProxyConnectRequest): ProxyTransportSession =
        object : ProxyTransportSession {
            override val descriptor = ProxySessionDescriptor(1, request, 0)

            override fun awaitConnected(timeoutMs: Long) = Unit

            override fun pumpBidirectional(client: ProxyClientConnection) = Unit

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
