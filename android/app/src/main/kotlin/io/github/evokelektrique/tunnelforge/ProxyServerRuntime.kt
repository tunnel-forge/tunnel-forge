package io.github.evokelektrique.tunnelforge

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ProxyRuntimeConfig(
    val httpEnabled: Boolean,
    val httpPort: Int,
    val socksEnabled: Boolean,
    val socksPort: Int,
    val allowLanConnections: Boolean = false,
    val maxConcurrentClients: Int? = ProxyServerRuntime.DEFAULT_MAX_CONCURRENT_CLIENTS,
    val exposure: ProxyExposureInfo =
        ProxyExposureInfo.loopback(
            httpPort = httpPort,
            socksPort = socksPort,
            lanRequested = false,
        ),
)

/**
 * Local proxy listeners shared by VPN and local-proxy modes.
 *
 * v1 runtime responsibility is listener lifecycle and protocol-safe failure responses until the
 * userspace transport is attached to [ProxyPacketBridge].
 */
class ProxyServerRuntime(
    private val config: ProxyRuntimeConfig,
    private val logger: (String) -> Unit = {},
    private val transport: ProxyTransport = StubProxyTransport(),
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val connectResponseTimeoutMs: Long = connectTimeoutMs,
    private val levelLogger: ((Int, String) -> Unit)? = null,
    private val secureSocketFactory: ProxySecureSocketFactory = SystemProxySecureSocketFactory,
) {
    internal enum class ProxyFailureReason {
        localServiceUnavailable,
        upstreamConnectFailed,
        upstreamTimeout,
        dnsFailed,
        networkUnreachable,
        protocolError,
        clientAborted,
    }

    private val running = AtomicBoolean(false)
    private val listenerThreads = CopyOnWriteArrayList<Thread>()
    private val serverSockets = CopyOnWriteArrayList<ServerSocket>()
    private val activeClientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val clientExecutor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "proxy-client-worker").apply { isDaemon = true }
        }
    private val activeClientWorkers = AtomicInteger(0)
    private val nextHttpErrorId = AtomicInteger(1)
    private val clientPermits = config.maxConcurrentClients?.let { Semaphore(it, true) }

    fun start() {
        if (!config.httpEnabled && !config.socksEnabled) {
            throw IllegalArgumentException("Enable HTTP or SOCKS5 before starting proxy listeners")
        }
        if (config.httpEnabled && config.socksEnabled && config.httpPort == config.socksPort) {
            throw IllegalArgumentException("HTTP and SOCKS5 ports must differ")
        }
        if (config.maxConcurrentClients != null && config.maxConcurrentClients < 1) {
            throw IllegalArgumentException("Proxy client limit must be at least 1")
        }
        if (!running.compareAndSet(false, true)) return
        val bindHosts = config.exposure.listenerBindAddresses()
        debug(
            "proxy runtime start binds=${bindHosts.joinToString("|")} display=${config.exposure.displayAddress} http=${if (config.httpEnabled) config.httpPort else "off"} socks=${if (config.socksEnabled) config.socksPort else "off"}",
        )
        try {
            for (bindHost in bindHosts) {
                if (config.httpEnabled) {
                    startListener("http", bindHost, config.httpPort, ::handleHttpClient)
                }
                if (config.socksEnabled) {
                    startListener("socks5", bindHost, config.socksPort, ::handleSocksClient)
                }
            }
        } catch (t: Throwable) {
            stop()
            throw t
        }
    }

    fun stop() {
        running.set(false)
        debug("proxy runtime stop listeners=${listenerThreads.size} clients=${activeClientWorkers.get()} sockets=${activeClientSockets.size}")
        for (socket in serverSockets) {
            closeQuietly(socket)
        }
        serverSockets.clear()
        for (socket in activeClientSockets) {
            closeQuietly(socket)
        }
        activeClientSockets.clear()
        for (thread in listenerThreads) {
            thread.interrupt()
        }
        listenerThreads.clear()
        clientExecutor.shutdownNow()
    }

    fun endpointSummary(): String {
        return config.exposure.endpointSummary(
            httpEnabled = config.httpEnabled,
            socksEnabled = config.socksEnabled,
        )
    }

    private fun startListener(
        name: String,
        bindHost: String,
        port: Int,
        handler: (Socket) -> Unit,
    ) {
        val socket = ServerSocket(port, LISTENER_BACKLOG, InetAddress.getByName(bindHost)).apply {
            reuseAddress = true
        }
        serverSockets.add(socket)
        debug("Listening on $name://$bindHost:$port")
        val thread = Thread(
            {
                try {
                    while (running.get() && !Thread.currentThread().isInterrupted) {
                        try {
                            val client = socket.accept()
                            client.soTimeout = CLIENT_SO_TIMEOUT_MS
                            startClientThread(name, client, handler)
                        } catch (_: SocketException) {
                            break
                        } catch (e: IOException) {
                            if (running.get()) {
                                warn("$name accept error: ${e.message}")
                            }
                            break
                        }
                    }
                } catch (t: Throwable) {
                    if (running.get()) {
                        warn("$name listener crash: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            },
            "proxy-$name-listener",
        )
        listenerThreads.add(thread)
        thread.start()
    }

    private fun startClientThread(
        name: String,
        client: Socket,
        handler: (Socket) -> Unit,
    ) {
        val permit = clientPermits
        if (permit != null && !permit.tryAcquire()) {
            rejectOverloadedClient(name, client)
            return
        }
        activeClientSockets.add(client)
        activeClientWorkers.incrementAndGet()
        try {
            clientExecutor.execute {
                try {
                    client.use {
                        try {
                            handler(client)
                        } catch (_: EOFException) {
                        } catch (e: IOException) {
                            warn("$name client error: ${e.message}")
                        }
                    }
                } catch (t: Throwable) {
                    if (running.get()) {
                        warn("$name client crash: ${t.javaClass.simpleName}: ${t.message}")
                    }
                } finally {
                    activeClientSockets.remove(client)
                    activeClientWorkers.decrementAndGet()
                    permit?.release()
                }
            }
        } catch (t: RuntimeException) {
            activeClientSockets.remove(client)
            activeClientWorkers.decrementAndGet()
            permit?.release()
            closeQuietly(client)
            throw t
        }
    }

    private fun rejectOverloadedClient(name: String, client: Socket) {
        warn("proxy reject proto=$name reason=too-many-clients limit=${config.maxConcurrentClients ?: "unlimited"}")
        try {
            client.use { socket ->
                if (name == "http") {
                    val output = BufferedOutputStream(socket.getOutputStream())
                    writeHttpError(
                        output,
                        503,
                        "Service Unavailable",
                        "Too many active proxy clients; try again shortly.",
                        HttpErrorLogContext(
                            protocol = name,
                            phase = "accept",
                            reason = "too-many-clients",
                            closeClient = true,
                            detail = "limit=${config.maxConcurrentClients ?: "unlimited"}",
                        ),
                    )
                }
            }
        } catch (e: IOException) {
            warn("proxy reject proto=$name reason=too-many-clients write-failed error=${e.message}")
        }
    }

    private fun handleHttpClient(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        var upstream: PlainHttpUpstream? = null
        try {
            client.soTimeout = CLIENT_SO_TIMEOUT_MS
            detectHttpProtocolMismatch(input)?.let { detected ->
                throw httpProtocolMismatch("detected=$detected")
            }
            while (running.get() && !client.isClosed) {
                val request = readHttpRequest(input) ?: return
                if (request.method == "CONNECT") {
                    upstream?.close()
                    handleHttpConnect(client, input, output, request)
                    return
                }
                val keepClientOpen = handleHttpForward(client, input, output, request, upstream)?.also {
                    upstream = it.upstream
                }?.keepClientOpen ?: false
                if (!keepClientOpen) return
                client.soTimeout = 0
            }
        } catch (e: HttpProxyRequestException) {
            warn(
                "proxy reject proto=${e.protocol} reason=${e.reason}${e.logDetail?.let { " $it" } ?: ""}",
            )
            writeHttpError(
                output,
                e.statusCode,
                e.statusText,
                e.publicMessage,
                HttpErrorLogContext(
                    protocol = e.protocol,
                    phase = "request-parse",
                    reason = e.reason,
                    closeClient = true,
                    detail = e.logDetail,
                ),
            )
        } finally {
            upstream?.close()
        }
    }

    private fun handleHttpConnect(
        client: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        request: ParsedHttpRequest,
    ) {
        val target =
            parseHttpConnectTarget(request.rawTarget)
                ?: throw HttpProxyRequestException(
                    protocol = "http-connect",
                    reason = "invalid-target",
                    statusCode = 400,
                    statusText = "Bad Request",
                    publicMessage = "Invalid CONNECT target.",
                    logDetail = "raw=${request.rawTarget}",
                )
        val connectRequest =
            ProxyConnectRequest(
                host = target.first,
                port = target.second,
                protocol = "http-connect",
            )
        if (connectRequest.host.isUnsupportedIpv6Target()) {
            throw HttpProxyRequestException(
                protocol = "http-connect",
                reason = "ipv6-unsupported",
                statusCode = 501,
                statusText = "Not Implemented",
                publicMessage = IPV6_UNSUPPORTED_MESSAGE,
                logDetail = "target=${connectRequest.host}:${connectRequest.port}",
            )
        }
        debug(
            "proxy request proto=http-connect method=${request.method} version=${request.version} target=${connectRequest.host}:${connectRequest.port} headers=${request.headers.namesForLog()} ${runtimeDiagnostics()}",
        )
        var sessionId: Int? = null
        var tunnelEstablished = false
        try {
            transport.openTcpSession(connectRequest).use { session ->
                sessionId = session.descriptor.sessionId
                session.awaitConnected(connectResponseTimeoutMs.coerceAtMost(connectTimeoutMs).coerceAtLeast(1L))
                debug("proxy connect ok proto=http-connect sid=${session.descriptor.sessionId} target=${connectRequest.host}:${connectRequest.port}")
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                tunnelEstablished = true
                // Keep a short timeout while parsing the proxy handshake, then return the tunnel socket
                // to blocking mode so CONNECT sessions can stay idle without tripping read timeouts.
                client.soTimeout = 0
                session.pumpBidirectional(
                    ProxyClientConnection(
                        socket = client,
                        input = input,
                        output = output,
                    ),
                )
            }
        } catch (e: IOException) {
            val failure = categorizeTransportFailure(e)
            val response = httpTransportFailureFor(failure)
            warn(
                "proxy fail proto=http-connect phase=${if (tunnelEstablished) "post-success" else "pre-success"} sid=${sessionId?.toString() ?: "pending"} target=${connectRequest.host}:${connectRequest.port} reason=${failure.reason} status=${if (tunnelEstablished) "none" else response.code.toString()} error=${failure.message} ${runtimeDiagnostics()}",
            )
            if (!tunnelEstablished) {
                writeHttpErrorOrLogClientAbort(
                    output = output,
                    code = response.code,
                    status = response.status,
                    message = response.message,
                    context =
                        HttpErrorLogContext(
                            protocol = "http-connect",
                            phase = "pre-success",
                            reason = failure.reason.name,
                            target = "${connectRequest.host}:${connectRequest.port}",
                            sessionId = sessionId,
                            closeClient = true,
                            detail = failure.message,
                        ),
                )
            }
        }
    }

    private fun handleHttpForward(
        client: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        request: ParsedHttpRequest,
        existingUpstream: PlainHttpUpstream?,
    ): PlainHttpForwardResult? {
        val target = parseHttpForwardTarget(request)
        val connectRequest =
            ProxyConnectRequest(
                host = target.host,
                port = target.port,
                protocol = if (target.secureUpstream) "https-proxy" else "http-proxy",
            )
        if (connectRequest.host.isUnsupportedIpv6Target()) {
            throw HttpProxyRequestException(
                protocol = connectRequest.protocol,
                reason = "ipv6-unsupported",
                statusCode = 501,
                statusText = "Not Implemented",
                publicMessage = IPV6_UNSUPPORTED_MESSAGE,
                logDetail = "target=${connectRequest.host}:${connectRequest.port}",
            )
        }
        val forwardedBytes = buildHttpForwardPrefix(request, target)
        var sessionId: Int? = null
        var upstream = existingUpstream?.takeIf { it.matches(target) && !it.closed.get() }
        if (upstream == null) {
            existingUpstream?.close()
        }
        try {
            if (upstream == null) {
                upstream = openPlainHttpUpstream(connectRequest, target)
                sessionId = upstream.descriptor.sessionId
                debug("proxy connect ok proto=${connectRequest.protocol} sid=${upstream.descriptor.sessionId} target=${connectRequest.host}:${connectRequest.port}")
            } else {
                sessionId = upstream.descriptor.sessionId
            }
            val keepClientOpen =
                relayPlainHttpExchange(
                    request = request,
                    target = target,
                    forwardedBytes = forwardedBytes,
                    clientInput = input,
                    clientOutput = output,
                    upstream = upstream,
                )
            return PlainHttpForwardResult(upstream = upstream.takeIf { keepClientOpen && !it.closed.get() }, keepClientOpen = keepClientOpen)
        } catch (e: IOException) {
            warn(
                "proxy fail proto=${connectRequest.protocol} sid=${sessionId?.toString() ?: "pending"} target=${connectRequest.host}:${connectRequest.port} error=${e.message ?: DEFAULT_TRANSPORT_FAILURE}",
            )
            if (e !is HttpProxyResponseStartedException) {
                writeHttpTransportFailure(
                    output,
                    e,
                    HttpErrorLogContext(
                        protocol = connectRequest.protocol,
                        phase = "pre-response",
                        reason = "transport-failure",
                        target = "${connectRequest.host}:${connectRequest.port}",
                        sessionId = sessionId,
                        closeClient = true,
                        detail = e.message,
                    ),
                )
            }
            upstream?.close()
            return PlainHttpForwardResult(upstream = null, keepClientOpen = false)
        }
    }

    private fun openPlainHttpUpstream(
        request: ProxyConnectRequest,
        target: HttpForwardTarget,
    ): PlainHttpUpstream {
        val connected = openConnectedTransportSocket(request)
        return try {
            val socket =
                if (target.secureUpstream) {
                    secureSocketFactory.create(connected.socket, request.host, request.port)
                } else {
                    connected.socket
                }
            PlainHttpUpstream(
                descriptor = connected.descriptor,
                target = target,
                connectedSocket = connected,
                socket = socket,
                input = BufferedInputStream(socket.getInputStream()),
                output = BufferedOutputStream(socket.getOutputStream()),
            )
        } catch (e: IOException) {
            connected.close()
            throw e
        }
    }

    private fun relayPlainHttpExchange(
        request: ParsedHttpRequest,
        target: HttpForwardTarget,
        forwardedBytes: ByteArray,
        clientInput: BufferedInputStream,
        clientOutput: BufferedOutputStream,
        upstream: PlainHttpUpstream,
    ): Boolean {
        var responseStarted = false
        try {
            upstream.output.write(forwardedBytes)
            relayHttpBody(
                framing = requestBodyFraming(request),
                input = clientInput,
                output = upstream.output,
            )
            upstream.output.flush()

            val response = readHttpResponse(upstream.input)
                ?: throw IOException("Upstream closed before sending an HTTP response.")
            val responseFraming = responseBodyFraming(request, response)
            writeHttpMessage(
                startLine = response.statusLine,
                headers = response.headers,
                output = clientOutput,
            )
            clientOutput.flush()
            responseStarted = true
            val upstreamReusable =
                relayHttpBody(
                    framing = responseFraming,
                    input = upstream.input,
                    output = clientOutput,
                )
            clientOutput.flush()

            val clientReusable = shouldKeepHttpConnectionOpen(request.version, request.headers)
            val responseReusable = shouldKeepHttpConnectionOpen(response.version, response.headers)
            val keepOpen = clientReusable && responseReusable && upstreamReusable
            if (!keepOpen) {
                upstream.close()
            }
            debug("proxy http exchange proto=${if (target.secureUpstream) "https-proxy" else "http-proxy"} sid=${upstream.descriptor.sessionId} target=${target.host}:${target.port} keepOpen=$keepOpen")
            return keepOpen
        } catch (e: IOException) {
            if (responseStarted) {
                throw HttpProxyResponseStartedException(e)
            }
            throw e
        }
    }

    private fun handleHttpsForward(
        client: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        request: ProxyConnectRequest,
        forwardedBytes: ByteArray,
    ) {
        var sessionId: Int? = null
        try {
            openConnectedTransportSocket(request).use { upstream ->
                sessionId = upstream.descriptor.sessionId
                debug("proxy connect ok proto=https-proxy sid=${upstream.descriptor.sessionId} target=${request.host}:${request.port}")
                secureSocketFactory.create(upstream.socket, request.host, request.port).use { secureSocket ->
                    client.soTimeout = 0
                    pumpBidirectional(
                        client = client,
                        clientInput = PrefixedInputStream(forwardedBytes, input),
                        clientOutput = output,
                        upstream = secureSocket,
                    )
                }
            }
        } catch (e: IOException) {
            warn(
                "proxy fail proto=https-proxy sid=${sessionId?.toString() ?: "pending"} target=${request.host}:${request.port} error=${e.message ?: DEFAULT_TRANSPORT_FAILURE}",
            )
            writeHttpTransportFailure(
                output,
                e,
                HttpErrorLogContext(
                    protocol = "https-proxy",
                    phase = "pre-response",
                    reason = "transport-failure",
                    target = "${request.host}:${request.port}",
                    sessionId = sessionId,
                    closeClient = true,
                    detail = e.message,
                ),
            )
        }
    }

    private fun handleSocksClient(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val version = input.read()
        if (version != 0x05) return
        val methodCount = input.read()
        if (methodCount <= 0) return
        repeat(methodCount) {
            if (input.read() < 0) return
        }
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        val reqVersion = input.read()
        val cmd = input.read()
        val reserved = input.read()
        val atyp = input.read()
        if (reqVersion != 0x05 || reserved != 0x00 || cmd < 0 || atyp < 0) {
            warn("proxy reject proto=socks5 reason=malformed-request version=$reqVersion cmd=$cmd atyp=$atyp")
            return
        }
        if (cmd != SOCKS_CMD_CONNECT && cmd != SOCKS_CMD_UDP_ASSOCIATE) {
            warn("proxy reject proto=socks5 reason=unsupported-command cmd=$cmd")
            writeSocksReply(output, SOCKS_REPLY_COMMAND_NOT_SUPPORTED)
            return
        }
        val host =
            when (atyp) {
                0x01 -> {
                    val bytes = ByteArray(4)
                    for (i in bytes.indices) {
                        val next = input.read()
                        if (next < 0) return
                        bytes[i] = next.toByte()
                    }
                    bytes.joinToString(".") { (it.toInt() and 0xff).toString() }
                }
            0x03 -> {
                val hostLen = input.read()
                if (hostLen <= 0) return
                val hostBytes = ByteArray(hostLen)
                for (i in hostBytes.indices) {
                    val next = input.read()
                    if (next < 0) return
                    hostBytes[i] = next.toByte()
                }
                hostBytes.toString(StandardCharsets.US_ASCII)
            }
                0x04 -> {
                    val bytes = ByteArray(16)
                    for (i in bytes.indices) {
                        val next = input.read()
                        if (next < 0) return
                        bytes[i] = next.toByte()
                    }
                    bytes.toIpv6Literal()
                }
            else -> return
        }
        val portHi = input.read()
        val portLo = input.read()
        if (portHi < 0 || portLo < 0) return
        val port = (portHi shl 8) or portLo
        if (cmd == SOCKS_CMD_UDP_ASSOCIATE) {
            handleSocksUdpAssociate(client, input, output, host, port)
            return
        }
        val request = ProxyConnectRequest(host = host, port = port, protocol = "socks5-connect")
        if (request.host.isUnsupportedIpv6Target()) {
            warn("proxy reject proto=socks5-connect reason=ipv6-unsupported target=${request.host}:${request.port}")
            writeSocksReply(output, SOCKS_REPLY_HOST_UNREACHABLE)
            return
        }
        var sessionId: Int? = null
        var handshakeSucceeded = false
        try {
            transport.openTcpSession(request).use { session ->
                sessionId = session.descriptor.sessionId
                session.awaitConnected(connectTimeoutMs)
                debug("proxy connect ok proto=socks5-connect sid=${session.descriptor.sessionId} target=${request.host}:${request.port}")
                writeSocksReply(output, SOCKS_REPLY_SUCCEEDED)
                handshakeSucceeded = true
                // Only the SOCKS handshake uses the listener timeout; the tunneled stream should block normally.
                client.soTimeout = 0
                session.pumpBidirectional(
                    ProxyClientConnection(
                        socket = client,
                        input = input,
                        output = output,
                    ),
                )
            }
        } catch (e: IOException) {
            warn(
                "proxy fail proto=socks5-connect phase=${if (handshakeSucceeded) "post-success" else "pre-success"} sid=${sessionId?.toString() ?: "pending"} target=${request.host}:${request.port} error=${e.message ?: DEFAULT_TRANSPORT_FAILURE}",
            )
            if (!handshakeSucceeded) {
                writeSocksReply(output, socksReplyForTransportFailure(e))
            }
        }
    }

    private fun handleSocksUdpAssociate(
        client: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        requestedHost: String,
        requestedPort: Int,
    ) {
        debug("SOCKS5 UDP ASSOCIATE request received: $requestedHost:$requestedPort")
        if (requestedHost.isUnsupportedIpv6Target()) {
            warn("proxy reject proto=socks5-udp reason=ipv6-unsupported target=$requestedHost:$requestedPort")
            writeSocksReply(output, SOCKS_REPLY_HOST_UNREACHABLE)
            return
        }
        var sessionId: Int? = null
        var associationEstablished = false
        try {
            val requestedClientEndpoint =
                requestedUdpClientEndpoint(
                    client = client,
                    requestedHost = requestedHost,
                    requestedPort = requestedPort,
                )
            transport.openUdpAssociation(
                ProxyConnectRequest(
                    host = requestedHost,
                    port = requestedPort,
                    protocol = "socks5-udp",
                ),
            ).use { association ->
                sessionId = association.descriptor.sessionId
                DatagramSocket(0, client.localAddress).use { relaySocket ->
                    relaySocket.soTimeout = UDP_RELAY_POLL_TIMEOUT_MS
                    writeSocksReply(
                        output = output,
                        status = SOCKS_REPLY_SUCCEEDED,
                        bindAddress = relaySocket.localAddress,
                        bindPort = relaySocket.localPort,
                    )
                    associationEstablished = true
                    debug("proxy udp associate ok sid=${association.descriptor.sessionId} relay=${relaySocket.localAddress.hostAddress}:${relaySocket.localPort}")
                    client.soTimeout = 0
                    val closed = AtomicBoolean(false)
                    val clientAddress = AtomicReference(requestedClientEndpoint)
                    val controlWatcher =
                        submitClientWorker {
                            try {
                                while (!closed.get() && input.read() >= 0) {
                                }
                            } catch (_: IOException) {
                            } finally {
                                closed.set(true)
                                relaySocket.close()
                            }
                        }
                    val remotePump =
                        submitClientWorker {
                            try {
                                while (!closed.get()) {
                                    val datagram = association.receive(UDP_RELAY_POLL_TIMEOUT_MS.toLong()) ?: continue
                                    val response = buildSocksUdpPacket(datagram) ?: continue
                                    val targetClient = clientAddress.get() ?: continue
                                    relaySocket.send(
                                        DatagramPacket(
                                            response,
                                            response.size,
                                            targetClient.address,
                                            targetClient.port,
                                        ),
                                    )
                                }
                            } catch (_: SocketException) {
                            } catch (e: IOException) {
                                if (!closed.get()) warn("proxy udp remote pump error: ${e.message}")
                            } finally {
                                closed.set(true)
                            }
                        }
                    val buffer = ByteArray(MAX_UDP_RELAY_PACKET_BYTES)
                    while (running.get() && !closed.get()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            relaySocket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        } catch (_: SocketException) {
                            break
                        }
                        val observedClient = java.net.InetSocketAddress(packet.address, packet.port)
                        val existingClient = clientAddress.get()
                        if (existingClient == null) {
                            if (packet.address != client.inetAddress) {
                                warn("proxy udp drop reason=unexpected-client source=${packet.address.hostAddress}:${packet.port}")
                                continue
                            }
                            clientAddress.compareAndSet(null, observedClient)
                        } else if (existingClient != observedClient) {
                            warn("proxy udp drop reason=client-endpoint-mismatch source=${packet.address.hostAddress}:${packet.port} expected=${existingClient.address.hostAddress}:${existingClient.port}")
                            continue
                        }
                        val datagram = parseSocksUdpPacket(packet.data, packet.offset, packet.length)
                        if (datagram == null) {
                            warn("proxy udp drop reason=malformed-or-unsupported len=${packet.length}")
                            continue
                        }
                        try {
                            association.send(datagram)
                        } catch (e: IOException) {
                            warn("proxy udp drop reason=send-failed target=${datagram.host}:${datagram.port} error=${e.message}")
                        }
                    }
                    closed.set(true)
                    controlWatcher.cancel(true)
                    remotePump.cancel(true)
                }
            }
        } catch (e: IOException) {
            warn(
                "proxy fail proto=socks5-udp phase=${if (associationEstablished) "post-success" else "pre-success"} sid=${sessionId?.toString() ?: "pending"} target=$requestedHost:$requestedPort error=${e.message ?: DEFAULT_TRANSPORT_FAILURE}",
            )
            if (!associationEstablished) {
                writeSocksReply(output, socksReplyForTransportFailure(e))
            }
        }
    }

    private fun requestedUdpClientEndpoint(
        client: Socket,
        requestedHost: String,
        requestedPort: Int,
    ): java.net.InetSocketAddress? {
        if (requestedPort == 0) return null
        if (requestedHost == "0.0.0.0") {
            return java.net.InetSocketAddress(client.inetAddress, requestedPort)
        }
        if (requestedHost.isMalformedIpv4LiteralCandidate()) {
            throw IOException("Invalid UDP client endpoint host: $requestedHost")
        }
        return java.net.InetSocketAddress(InetAddress.getByName(requestedHost), requestedPort)
    }

    private fun debug(message: String) = log(Log.DEBUG, message)

    private fun warn(message: String) = log(Log.WARN, message)

    private fun log(level: Int, message: String) {
        levelLogger?.invoke(level, message) ?: logger(message)
    }

    private fun writeHttpError(
        output: BufferedOutputStream,
        code: Int,
        status: String,
        message: String,
        context: HttpErrorLogContext,
    ) {
        logHttpError(code, status, message, context)
        val body = message.toByteArray(Charsets.UTF_8)
        output.write(
            (
                "HTTP/1.1 $code $status\r\n" +
                    "Connection: close\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.UTF_8),
        )
        output.write(body)
        output.flush()
    }

    private fun writeHttpErrorOrLogClientAbort(
        output: BufferedOutputStream,
        code: Int,
        status: String,
        message: String,
        context: HttpErrorLogContext,
    ) {
        try {
            writeHttpError(output, code, status, message, context)
        } catch (e: IOException) {
            warn(
                listOfNotNull(
                    "proxy fail proto=${context.protocol}",
                    "phase=${context.phase}",
                    "reason=${ProxyFailureReason.clientAborted.name}",
                    context.sessionId?.let { "sid=$it" } ?: "sid=pending",
                    context.target?.let { "target=$it" },
                    "status=none",
                    "error=${(e.message ?: "client closed before proxy response").sanitizeForLog()}",
                    runtimeDiagnostics(),
                ).joinToString(" "),
            )
        }
    }

    private fun writeHttpTransportFailure(
        output: BufferedOutputStream,
        failure: IOException,
        context: HttpErrorLogContext,
    ) {
        val endpointFailure = categorizeTransportFailure(failure)
        val response = httpTransportFailureFor(endpointFailure)
        writeHttpError(
            output,
            response.code,
            response.status,
            response.message,
            context.copy(
                reason = endpointFailure.reason.name,
                detail = context.detail ?: endpointFailure.message,
            ),
        )
    }

    private fun logHttpError(
        code: Int,
        status: String,
        message: String,
        context: HttpErrorLogContext,
    ) {
        val id = nextHttpErrorId.getAndIncrement()
        val fields =
            listOfNotNull(
                "proxy http error id=$id",
                "proto=${context.protocol}",
                "phase=${context.phase}",
                "status=$code",
                "statusText=${status.toLogToken()}",
                "reason=${context.reason}",
                context.sessionId?.let { "sid=$it" } ?: "sid=pending",
                context.target?.let { "target=$it" },
                "close=${context.closeClient}",
                context.detail?.let { "detail=${it.sanitizeForLog()}" },
                "message=${message.sanitizeForLog()}",
                runtimeDiagnostics(),
            )
        warn(fields.joinToString(" "))
    }

    private fun httpTransportFailureFor(failure: ProxyEndpointFailure): HttpFailureResponse {
        return when (failure.reason) {
            ProxyFailureReason.upstreamTimeout ->
                HttpFailureResponse(504, "Gateway Timeout", failure.message)
            ProxyFailureReason.localServiceUnavailable ->
                HttpFailureResponse(503, "Service Unavailable", failure.message)
            ProxyFailureReason.dnsFailed,
            ProxyFailureReason.networkUnreachable,
            ProxyFailureReason.upstreamConnectFailed,
            ProxyFailureReason.protocolError,
            ProxyFailureReason.clientAborted,
            ->
                HttpFailureResponse(502, "Bad Gateway", failure.message)
        }
    }

    private fun socksReplyForTransportFailure(failure: IOException): Int {
        return when (categorizeTransportFailure(failure).reason) {
            ProxyFailureReason.upstreamTimeout -> SOCKS_REPLY_TTL_EXPIRED
            ProxyFailureReason.dnsFailed -> SOCKS_REPLY_HOST_UNREACHABLE
            ProxyFailureReason.networkUnreachable -> SOCKS_REPLY_NETWORK_UNREACHABLE
            ProxyFailureReason.localServiceUnavailable,
            ProxyFailureReason.upstreamConnectFailed,
            ProxyFailureReason.protocolError,
            ProxyFailureReason.clientAborted,
            ->
                SOCKS_REPLY_GENERAL_FAILURE
        }
    }

    private fun categorizeTransportFailure(failure: IOException): ProxyEndpointFailure {
        if (failure is ProxyTransportException) {
            return ProxyEndpointFailure(
                reason =
                    when (failure.failureReason) {
                        ProxyTransportFailureReason.localServiceUnavailable -> ProxyFailureReason.localServiceUnavailable
                        ProxyTransportFailureReason.upstreamConnectFailed -> ProxyFailureReason.upstreamConnectFailed
                        ProxyTransportFailureReason.upstreamTimeout -> ProxyFailureReason.upstreamTimeout
                        ProxyTransportFailureReason.dnsFailed -> ProxyFailureReason.dnsFailed
                        ProxyTransportFailureReason.networkUnreachable -> ProxyFailureReason.networkUnreachable
                        ProxyTransportFailureReason.protocolError -> ProxyFailureReason.protocolError
                        ProxyTransportFailureReason.clientAborted -> ProxyFailureReason.clientAborted
                    },
                message = failure.message ?: DEFAULT_TRANSPORT_FAILURE,
            )
        }
        val message = failure.message ?: DEFAULT_TRANSPORT_FAILURE
        val normalized = message.lowercase()
        val reason =
            when {
                normalized.contains("timed out") -> ProxyFailureReason.upstreamTimeout
                normalized.contains("bridge is not active") ||
                    normalized.contains("forwarding is not attached") ||
                    normalized.contains("tunnel forwarding is not attached") ->
                    ProxyFailureReason.localServiceUnavailable
                normalized.contains("dns") || normalized.contains("resolve") ->
                    ProxyFailureReason.dnsFailed
                normalized.contains("network is unreachable") ->
                    ProxyFailureReason.networkUnreachable
                normalized.contains("failed to connect") ||
                    normalized.contains("connect failed") ||
                    normalized.contains("refused") ->
                    ProxyFailureReason.upstreamConnectFailed
                else -> ProxyFailureReason.protocolError
            }
        return ProxyEndpointFailure(reason = reason, message = message)
    }

    private fun writeSocksReply(
        output: BufferedOutputStream,
        status: Int,
        bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
        bindPort: Int = 0,
    ) {
        val address = bindAddress.address.takeIf { it.size == 4 } ?: byteArrayOf(0, 0, 0, 0)
        output.write(
            byteArrayOf(
                0x05,
                status.toByte(),
                0x00,
                0x01,
                address[0],
                address[1],
                address[2],
                address[3],
                ((bindPort ushr 8) and 0xff).toByte(),
                (bindPort and 0xff).toByte(),
            ),
        )
        output.flush()
    }

    private fun parseSocksUdpPacket(
        packet: ByteArray,
        offset: Int,
        length: Int,
    ): ProxyUdpDatagram? {
        if (length < SOCKS_UDP_FIXED_PREFIX_LEN) return null
        var cursor = offset
        val end = offset + length
        if (packet[cursor].toInt() != 0 || packet[cursor + 1].toInt() != 0) return null
        cursor += 2
        val frag = packet[cursor].toInt() and 0xff
        cursor += 1
        if (frag != 0) return null
        val atyp = packet[cursor].toInt() and 0xff
        cursor += 1
        val host =
            when (atyp) {
                0x01 -> {
                    if (cursor + 4 > end) return null
                    val value = packet.copyOfRange(cursor, cursor + 4).joinToString(".") { (it.toInt() and 0xff).toString() }
                    cursor += 4
                    value
                }
                0x03 -> {
                    if (cursor >= end) return null
                    val hostLen = packet[cursor].toInt() and 0xff
                    cursor += 1
                    if (hostLen <= 0 || cursor + hostLen > end) return null
                    val value = packet.copyOfRange(cursor, cursor + hostLen).toString(StandardCharsets.US_ASCII)
                    cursor += hostLen
                    value
                }
                0x04 -> return null
                else -> return null
            }
        if (cursor + 2 > end) return null
        val port = ((packet[cursor].toInt() and 0xff) shl 8) or (packet[cursor + 1].toInt() and 0xff)
        cursor += 2
        if (port !in 1..65535) return null
        return ProxyUdpDatagram(
            host = host,
            port = port,
            payload = packet.copyOfRange(cursor, end),
        )
    }

    private fun buildSocksUdpPacket(datagram: ProxyUdpDatagram): ByteArray? {
        val address = datagram.host.toIpv4LiteralOrNull()?.split('.')?.map { it.toInt().toByte() } ?: return null
        val header = ByteArray(10)
        header[3] = 0x01
        for (i in 0 until 4) {
            header[4 + i] = address[i]
        }
        header[8] = ((datagram.port ushr 8) and 0xff).toByte()
        header[9] = (datagram.port and 0xff).toByte()
        return header + datagram.payload
    }

    private fun readHttpRequest(input: BufferedInputStream): ParsedHttpRequest? {
        var requestLine = readAsciiLineRaw(input) ?: return null
        while (requestLine.text.isEmpty()) {
            requestLine = readAsciiLineRaw(input) ?: return null
        }
        if (!requestLine.isAsciiText) {
            throw httpProtocolMismatch("detected=non-http-request-line")
        }
        val parts = requestLine.text.trim().split(HTTP_REQUEST_LINE_WHITESPACE, limit = 3)
        if (parts.size < 3) {
            throw HttpProxyRequestException(
                protocol = "http",
                reason = "malformed-request-line",
                statusCode = 400,
                statusText = "Bad Request",
                publicMessage = "Malformed HTTP proxy request line.",
                logDetail = "line=${requestLine.text}",
            )
        }
        val headers = mutableListOf<HttpHeader>()
        while (true) {
            val line = readAsciiLineRaw(input) ?: break
            if (!line.isAsciiText) {
                throw httpProtocolMismatch("detected=non-http-header")
            }
            if (line.text.isEmpty()) break
            headers +=
                parseHttpHeader(line.text)
                    ?: throw HttpProxyRequestException(
                        protocol = "http",
                        reason = "malformed-header",
                        statusCode = 400,
                        statusText = "Bad Request",
                        publicMessage = "Malformed HTTP proxy header.",
                        logDetail = "line=${line.text}",
                    )
        }
        return ParsedHttpRequest(
            requestLine = requestLine.text,
            method = parts[0].uppercase(),
            rawTarget = parts[1],
            version = parts[2],
            headers = headers,
        )
    }

    private fun parseHttpHeader(line: String): HttpHeader? {
        val separator = line.indexOf(':')
        if (separator <= 0) return null
        val name = line.substring(0, separator).trim()
        if (name.isEmpty()) return null
        return HttpHeader(
            name = name,
            value = line.substring(separator + 1).trimStart(),
        )
    }

    private fun parseHttpConnectTarget(raw: String): Pair<String, Int>? =
        parseAuthority(raw)?.let { (host, port) ->
            if (port == null) null else host to port
        }

    private fun parseHttpForwardTarget(request: ParsedHttpRequest): HttpForwardTarget {
        val rawTarget = request.rawTarget.trim()
        if (rawTarget.isEmpty()) {
            throw HttpProxyRequestException(
                protocol = "http-proxy",
                reason = "invalid-target",
                statusCode = 400,
                statusText = "Bad Request",
                publicMessage = "Invalid HTTP proxy target.",
                logDetail = "raw=${request.rawTarget}",
            )
        }
        return if (rawTarget.startsWith("http://", ignoreCase = true) ||
            rawTarget.startsWith("https://", ignoreCase = true)
        ) {
            val uri =
                try {
                    URI(rawTarget)
                } catch (_: Exception) {
                    null
                }
                    ?: throw HttpProxyRequestException(
                        protocol = "http-proxy",
                        reason = "invalid-target",
                        statusCode = 400,
                        statusText = "Bad Request",
                        publicMessage = "Invalid HTTP proxy target.",
                        logDetail = "raw=${request.rawTarget}",
                    )
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" || uri.host.isNullOrEmpty()) {
                if (scheme == "https" && !uri.host.isNullOrEmpty()) {
                    val resolvedPort = if (uri.port in 1..65535) uri.port else 443
                    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
                    val originForm =
                        buildString {
                            append(path)
                            uri.rawQuery?.let { query ->
                                append('?')
                                append(query)
                            }
                        }
                    return HttpForwardTarget(
                        host = uri.host,
                        port = resolvedPort,
                        originForm = originForm,
                        hostHeaderValue =
                            formatHostHeader(
                                host = uri.host,
                                port = resolvedPort,
                                defaultPort = 443,
                            ),
                        rewriteHostHeader = true,
                        secureUpstream = true,
                    )
                }
                throw HttpProxyRequestException(
                    protocol = "http-proxy",
                    reason = "invalid-target",
                    statusCode = 400,
                    statusText = "Bad Request",
                    publicMessage = "Invalid HTTP proxy target.",
                    logDetail = "raw=${request.rawTarget}",
                )
            }
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            val originForm =
                buildString {
                    append(path)
                    uri.rawQuery?.let { query ->
                        append('?')
                        append(query)
                    }
                }
            HttpForwardTarget(
                host = uri.host,
                port = if (uri.port in 1..65535) uri.port else if (scheme == "https") 443 else 80,
                originForm = originForm,
                hostHeaderValue =
                    formatHostHeader(
                        host = uri.host,
                        port = if (uri.port in 1..65535) uri.port else if (scheme == "https") 443 else 80,
                        defaultPort = if (scheme == "https") 443 else 80,
                    ),
                rewriteHostHeader = true,
                secureUpstream = false,
            )
        } else {
            if (!rawTarget.startsWith("/") && rawTarget != "*") {
                throw HttpProxyRequestException(
                    protocol = "http-proxy",
                    reason = "invalid-target",
                    statusCode = 400,
                    statusText = "Bad Request",
                    publicMessage = "Invalid HTTP proxy target.",
                    logDetail = "raw=${request.rawTarget}",
                )
            }
            val hostHeader =
                request.headers.firstOrNull { it.hasName("Host") }?.value
                    ?: throw HttpProxyRequestException(
                        protocol = "http-proxy",
                        reason = "missing-host-header",
                        statusCode = 400,
                        statusText = "Bad Request",
                        publicMessage = "HTTP proxy requests require a Host header.",
                    )
            val authority =
                parseAuthority(hostHeader)
                    ?: throw HttpProxyRequestException(
                        protocol = "http-proxy",
                        reason = "invalid-host-header",
                        statusCode = 400,
                        statusText = "Bad Request",
                        publicMessage = "Invalid Host header.",
                        logDetail = "host=$hostHeader",
                    )
            HttpForwardTarget(
                host = authority.first,
                port = authority.second ?: 80,
                originForm = rawTarget,
                hostHeaderValue = hostHeader.trim(),
                rewriteHostHeader = false,
                secureUpstream = false,
            )
        }
    }

    private fun buildHttpForwardPrefix(
        request: ParsedHttpRequest,
        target: HttpForwardTarget,
    ): ByteArray {
        val connectionHeaderTokens =
            request.headers
                .filter { it.hasName("Connection") }
                .flatMap { header ->
                    header.value.split(',').mapNotNull { token ->
                        token.trim().lowercase().takeIf { it.isNotEmpty() }
                    }
                }.toSet()
        val forwardedHeaders = mutableListOf<HttpHeader>()
        var hasHostHeader = false
        for (header in request.headers) {
            if (header.hasName("Proxy-Connection") ||
                header.hasName("Proxy-Authorization") ||
                header.hasName("Connection")
            ) {
                continue
            }
            if (connectionHeaderTokens.contains(header.name.lowercase())) continue
            if (target.rewriteHostHeader && header.hasName("Host")) continue
            forwardedHeaders += header
            if (header.hasName("Host")) {
                hasHostHeader = true
            }
        }
        if (target.rewriteHostHeader || !hasHostHeader) {
            forwardedHeaders += HttpHeader("Host", target.hostHeaderValue)
        }
        val forwarded =
            buildString {
                append(request.method)
                append(' ')
                append(target.originForm)
                append(' ')
                append(request.version)
                append("\r\n")
                for (header in forwardedHeaders) {
                    append(header.name)
                    append(": ")
                    append(header.value)
                    append("\r\n")
                }
                append("\r\n")
        }
        return forwarded.toByteArray(StandardCharsets.US_ASCII)
    }

    private fun readHttpResponse(input: BufferedInputStream): ParsedHttpResponse? {
        val statusLine = readAsciiLineRaw(input) ?: return null
        if (!statusLine.isAsciiText) {
            throw IOException("Malformed upstream HTTP response status line.")
        }
        val parts = statusLine.text.trim().split(HTTP_REQUEST_LINE_WHITESPACE, limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/", ignoreCase = true)) {
            throw IOException("Malformed upstream HTTP response status line.")
        }
        val statusCode = parts[1].toIntOrNull() ?: throw IOException("Malformed upstream HTTP response status code.")
        val headers = mutableListOf<HttpHeader>()
        while (true) {
            val line = readAsciiLineRaw(input) ?: throw IOException("Upstream closed while reading HTTP response headers.")
            if (!line.isAsciiText) {
                throw IOException("Malformed upstream HTTP response header.")
            }
            if (line.text.isEmpty()) break
            headers += parseHttpHeader(line.text) ?: throw IOException("Malformed upstream HTTP response header.")
        }
        return ParsedHttpResponse(
            statusLine = statusLine.text,
            version = parts[0],
            statusCode = statusCode,
            headers = headers,
        )
    }

    private fun writeHttpMessage(
        startLine: String,
        headers: List<HttpHeader>,
        output: BufferedOutputStream,
    ) {
        output.write(startLine.toByteArray(StandardCharsets.US_ASCII))
        output.write(CRLF)
        for (header in headers) {
            output.write(header.name.toByteArray(StandardCharsets.US_ASCII))
            output.write(HEADER_SEPARATOR)
            output.write(header.value.toByteArray(StandardCharsets.US_ASCII))
            output.write(CRLF)
        }
        output.write(CRLF)
    }

    private fun requestBodyFraming(request: ParsedHttpRequest): HttpBodyFraming {
        if (request.headers.isChunked()) return HttpBodyFraming.chunked
        val contentLength = request.headers.contentLength()
        if (contentLength != null && contentLength > 0L) return HttpBodyFraming.fixed(contentLength)
        return HttpBodyFraming.none
    }

    private fun responseBodyFraming(
        request: ParsedHttpRequest,
        response: ParsedHttpResponse,
    ): HttpBodyFraming {
        if (request.method == "HEAD" ||
            response.statusCode in 100..199 ||
            response.statusCode == 204 ||
            response.statusCode == 304
        ) {
            return HttpBodyFraming.none
        }
        if (response.headers.isChunked()) return HttpBodyFraming.chunked
        val contentLength = response.headers.contentLength()
        if (contentLength != null) return if (contentLength > 0L) HttpBodyFraming.fixed(contentLength) else HttpBodyFraming.none
        return HttpBodyFraming.closeDelimited
    }

    private fun relayHttpBody(
        framing: HttpBodyFraming,
        input: BufferedInputStream,
        output: BufferedOutputStream,
    ): Boolean {
        return when (framing) {
            HttpBodyFraming.none -> true
            is HttpBodyFraming.fixed -> {
                copyExact(input, output, framing.length)
                true
            }
            HttpBodyFraming.chunked -> {
                copyChunkedBody(input, output)
                true
            }
            HttpBodyFraming.closeDelimited -> {
                copyUntilEof(input, output)
                false
            }
        }
    }

    private fun copyExact(
        input: InputStream,
        output: OutputStream,
        length: Long,
    ) {
        var remaining = length
        val buffer = ByteArray(RELAY_BUFFER_BYTES)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected EOF while relaying HTTP body.")
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun copyUntilEof(
        input: InputStream,
        output: OutputStream,
    ) {
        val buffer = ByteArray(RELAY_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            if (read == 0) continue
            output.write(buffer, 0, read)
        }
    }

    private fun copyChunkedBody(
        input: BufferedInputStream,
        output: OutputStream,
    ) {
        while (true) {
            val sizeLine = readAsciiLineRaw(input) ?: throw EOFException("Unexpected EOF while reading chunk size.")
            if (!sizeLine.isAsciiText) throw IOException("Malformed chunk size line.")
            output.write(sizeLine.text.toByteArray(StandardCharsets.US_ASCII))
            output.write(CRLF)
            val chunkSize =
                sizeLine.text.substringBefore(';').trim().toLongOrNull(16)
                    ?: throw IOException("Malformed chunk size.")
            if (chunkSize == 0L) {
                while (true) {
                    val trailer = readAsciiLineRaw(input) ?: throw EOFException("Unexpected EOF while reading chunk trailers.")
                    if (!trailer.isAsciiText) throw IOException("Malformed chunk trailer.")
                    output.write(trailer.text.toByteArray(StandardCharsets.US_ASCII))
                    output.write(CRLF)
                    if (trailer.text.isEmpty()) return
                }
            }
            copyExact(input, output, chunkSize)
            copyExact(input, output, CRLF.size.toLong())
        }
    }

    private fun shouldKeepHttpConnectionOpen(
        version: String,
        headers: List<HttpHeader>,
    ): Boolean {
        val connectionTokens = headers.connectionTokens()
        return when {
            connectionTokens.contains("close") -> false
            version.equals("HTTP/1.0", ignoreCase = true) -> connectionTokens.contains("keep-alive")
            else -> true
        }
    }

    private fun List<HttpHeader>.contentLength(): Long? {
        val values = filter { it.hasName("Content-Length") }.mapNotNull { it.value.trim().toLongOrNull() }
        if (values.isEmpty()) return null
        val first = values.first()
        if (values.any { it != first }) throw IOException("Conflicting Content-Length headers.")
        return first.coerceAtLeast(0L)
    }

    private fun List<HttpHeader>.isChunked(): Boolean =
        any {
            it.hasName("Transfer-Encoding") &&
                it.value.split(',').any { token -> token.trim().equals("chunked", ignoreCase = true) }
        }

    private fun List<HttpHeader>.connectionTokens(): Set<String> =
        filter { it.hasName("Connection") || it.hasName("Proxy-Connection") }
            .flatMap { header -> header.value.split(',') }
            .mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
            .toSet()

    private fun List<HttpHeader>.namesForLog(): String =
        if (isEmpty()) {
            "none"
        } else {
            joinToString(",") { header -> header.name.lowercase() }
        }

    private fun runtimeDiagnostics(): String =
        "clients=${activeClientWorkers.get()} sockets=${activeClientSockets.size} running=${running.get()}"

    private fun parseAuthority(raw: String): Pair<String, Int?>? {
        val authority = raw.trim()
        if (authority.isEmpty()) return null
        if (authority.startsWith("[")) {
            val closing = authority.indexOf(']')
            if (closing <= 1) return null
            val host = authority.substring(1, closing)
            val remainder = authority.substring(closing + 1)
            if (remainder.isEmpty()) return host to null
            if (!remainder.startsWith(":")) return null
            val port = remainder.substring(1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return host to port
        }
        if (authority.count { it == ':' } > 1) return null
        val separator = authority.lastIndexOf(':')
        if (separator < 0) {
            return authority to null
        }
        if (separator == 0 || separator == authority.lastIndex) return null
        val host = authority.substring(0, separator).trim()
        val port = authority.substring(separator + 1).trim().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) return null
        return host to port
    }

    private fun formatHostHeader(host: String, port: Int, defaultPort: Int): String {
        return if (port == defaultPort) host else "$host:$port"
    }

    private fun openConnectedTransportSocket(request: ProxyConnectRequest): ConnectedProxySocket {
        val session = transport.openTcpSession(request)
        try {
            session.awaitConnected(connectTimeoutMs)
            val listener = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST))
            try {
                val clientSocket = Socket(LOOPBACK_HOST, listener.localPort)
                val accepted = listener.accept()
                listener.close()
                submitClientWorker {
                    accepted.use { socket ->
                        session.use {
                            it.pumpBidirectional(
                                ProxyClientConnection(
                                    socket = socket,
                                    input = socket.getInputStream(),
                                    output = socket.getOutputStream(),
                                ),
                            )
                        }
                    }
                }
                return ConnectedProxySocket(
                    descriptor = session.descriptor,
                    socket = clientSocket,
                    onClose = {
                        closeQuietly(clientSocket)
                        session.close()
                    },
                )
            } catch (e: IOException) {
                try {
                    listener.close()
                } catch (_: IOException) {
                }
                session.close()
                throw e
            }
        } catch (e: IOException) {
            session.close()
            throw e
        }
    }

    private fun pumpBidirectional(
        client: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        upstream: Socket,
    ) {
        val tearingDown = AtomicBoolean(false)
        val failure = AtomicReference<IOException?>(null)
        val clientInputEnded = AtomicBoolean(false)
        val upstreamInputEnded = AtomicBoolean(false)
        val remoteToClient =
            submitClientWorker {
                relayStream(
                    input = upstream.getInputStream(),
                    output = clientOutput,
                    tearingDown = tearingDown,
                    failure = failure,
                    inputEnded = upstreamInputEnded,
                    onInputClosed = { shutdownSocketOutput(client) },
                    client = client,
                    upstream = upstream,
                )
            }
        relayStream(
            input = clientInput,
            output = upstream.getOutputStream(),
            tearingDown = tearingDown,
            failure = failure,
            inputEnded = clientInputEnded,
            onInputClosed = { shutdownSocketOutput(upstream) },
            client = client,
            upstream = upstream,
        )
        try {
            remoteToClient.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while relaying HTTPS proxy traffic.")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is IOException) throw cause
            throw IOException("HTTPS proxy relay failed.", cause)
        }
        if (failure.get() == null && tearingDown.compareAndSet(false, true)) {
            closeQuietly(client)
            closeQuietly(upstream)
        } else {
            failure.get()?.let { throw it }
        }
    }

    private fun submitClientWorker(block: () -> Unit): Future<*> =
        clientExecutor.submit {
            block()
        }

    private fun relayStream(
        input: InputStream,
        output: OutputStream,
        tearingDown: AtomicBoolean,
        failure: AtomicReference<IOException?>,
        inputEnded: AtomicBoolean,
        onInputClosed: () -> Unit,
        client: Socket,
        upstream: Socket,
    ) {
        val buffer = ByteArray(RELAY_BUFFER_BYTES)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                output.flush()
            }
            inputEnded.set(true)
            onInputClosed()
        } catch (e: IOException) {
            if (!tearingDown.get()) {
                failure.compareAndSet(null, e)
            }
        } finally {
            if (failure.get() != null && tearingDown.compareAndSet(false, true)) {
                closeQuietly(client)
                closeQuietly(upstream)
            }
        }
    }

    private fun shutdownSocketOutput(socket: Socket) {
        try {
            socket.shutdownOutput()
        } catch (_: IOException) {
        }
    }

    private fun detectHttpProtocolMismatch(input: BufferedInputStream): String? {
        if (!input.markSupported()) return null
        input.mark(HTTP_PROTOCOL_PEEK_BYTES)
        val prefix = ByteArray(HTTP_PROTOCOL_PEEK_BYTES)
        val read = input.read(prefix)
        input.reset()
        if (read <= 0) return null
        var start = 0
        while (start < read &&
            (prefix[start] == '\r'.code.toByte() || prefix[start] == '\n'.code.toByte())
        ) {
            start += 1
        }
        if (start >= read) return null
        val b0 = prefix[start].toInt() and 0xff
        val b1 = if (start + 1 < read) prefix[start + 1].toInt() and 0xff else -1
        val b2 = if (start + 2 < read) prefix[start + 2].toInt() and 0xff else -1
        if (b0 == 0x16 && b1 == 0x03 && b2 in 0x00..0x04) {
            return "tls-client-hello"
        }
        if (b0 == 0x05) {
            return "socks5-greeting"
        }
        for (index in start until read) {
            val byte = prefix[index].toInt() and 0xff
            if (byte == 0x00) return "binary-nul"
            if (byte < 0x20 && byte != '\r'.code && byte != '\n'.code && byte != '\t'.code) {
                return "binary-prefix"
            }
        }
        return null
    }

    private fun readAsciiLineRaw(input: BufferedInputStream): RawHttpLine? {
        val bytes = ArrayList<Byte>()
        var isAsciiText = true
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (bytes.isEmpty()) return null
                return RawHttpLine(
                    text = bytes.toByteArray().toString(Charsets.US_ASCII),
                    isAsciiText = isAsciiText,
                )
            }
            if (next == '\n'.code) {
                if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.removeAt(bytes.lastIndex)
                }
                return RawHttpLine(
                    text = bytes.toByteArray().toString(Charsets.US_ASCII),
                    isAsciiText = isAsciiText,
                )
            }
            if (next > 0x7e || (next < 0x20 && next != '\r'.code && next != '\t'.code)) {
                isAsciiText = false
            }
            bytes.add(next.toByte())
            if (bytes.size >= MAX_HEADER_LINE_LEN) {
                throw IOException("Header line too long")
            }
        }
    }

    private fun httpProtocolMismatch(detail: String): HttpProxyRequestException =
        HttpProxyRequestException(
            protocol = "http",
            reason = "protocol-mismatch",
            statusCode = 400,
            statusText = "Bad Request",
            publicMessage = HTTP_PROTOCOL_MISMATCH_MESSAGE,
            logDetail = detail,
        )

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        val DEFAULT_MAX_CONCURRENT_CLIENTS: Int? = null
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val LISTENER_BACKLOG = 1024
        private const val CLIENT_SO_TIMEOUT_MS = 5000
        private const val MAX_HEADER_LINE_LEN = 8192
        private const val DEFAULT_TRANSPORT_FAILURE = "Tunnel forwarding is not attached yet."
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        private const val HTTP_PROTOCOL_MISMATCH_MESSAGE = "HTTP proxy port expects a cleartext HTTP proxy request."
        private const val HTTP_PROTOCOL_PEEK_BYTES = 8
        private const val IPV6_UNSUPPORTED_MESSAGE = "IPv6 targets are not supported in proxy mode."
        private const val RELAY_BUFFER_BYTES = 8192
        private const val SOCKS_CMD_CONNECT = 0x01
        private const val SOCKS_CMD_UDP_ASSOCIATE = 0x03
        private const val SOCKS_REPLY_SUCCEEDED = 0x00
        private const val SOCKS_REPLY_GENERAL_FAILURE = 0x01
        private const val SOCKS_REPLY_NETWORK_UNREACHABLE = 0x03
        private const val SOCKS_REPLY_HOST_UNREACHABLE = 0x04
        private const val SOCKS_REPLY_TTL_EXPIRED = 0x06
        private const val SOCKS_REPLY_COMMAND_NOT_SUPPORTED = 0x07
        private const val SOCKS_UDP_FIXED_PREFIX_LEN = 4
        private const val UDP_RELAY_POLL_TIMEOUT_MS = 500
        private const val MAX_UDP_RELAY_PACKET_BYTES = 65535
        private val HTTP_REQUEST_LINE_WHITESPACE = Regex("\\s+")
        private val CRLF = "\r\n".toByteArray(StandardCharsets.US_ASCII)
        private val HEADER_SEPARATOR = ": ".toByteArray(StandardCharsets.US_ASCII)

        private fun ByteArray.toIpv6Literal(): String {
            val groups = ArrayList<String>(8)
            for (i in indices step 2) {
                val value = ((this[i].toInt() and 0xff) shl 8) or (this[i + 1].toInt() and 0xff)
                groups.add(value.toString(16))
            }
            return groups.joinToString(":")
        }
    }
}

private data class HttpFailureResponse(
    val code: Int,
    val status: String,
    val message: String,
)

private data class HttpErrorLogContext(
    val protocol: String,
    val phase: String,
    val reason: String,
    val target: String? = null,
    val sessionId: Int? = null,
    val closeClient: Boolean,
    val detail: String? = null,
)

private data class ProxyEndpointFailure(
    val reason: ProxyServerRuntime.ProxyFailureReason,
    val message: String,
)

private data class ParsedHttpRequest(
    val requestLine: String,
    val method: String,
    val rawTarget: String,
    val version: String,
    val headers: List<HttpHeader>,
)

private data class ParsedHttpResponse(
    val statusLine: String,
    val version: String,
    val statusCode: Int,
    val headers: List<HttpHeader>,
)

private data class RawHttpLine(
    val text: String,
    val isAsciiText: Boolean,
)

private data class HttpHeader(
    val name: String,
    val value: String,
) {
    fun hasName(expected: String): Boolean = name.equals(expected, ignoreCase = true)
}

private data class HttpForwardTarget(
    val host: String,
    val port: Int,
    val originForm: String,
    val hostHeaderValue: String,
    val rewriteHostHeader: Boolean,
    val secureUpstream: Boolean,
)

private data class PlainHttpForwardResult(
    val upstream: PlainHttpUpstream?,
    val keepClientOpen: Boolean,
)

private sealed class HttpBodyFraming {
    data object none : HttpBodyFraming()
    data object chunked : HttpBodyFraming()
    data object closeDelimited : HttpBodyFraming()
    data class fixed(val length: Long) : HttpBodyFraming()
}

private class HttpProxyRequestException(
    val protocol: String,
    val reason: String,
    val statusCode: Int,
    val statusText: String,
    val publicMessage: String,
    val logDetail: String? = null,
) : Exception(publicMessage)

private class HttpProxyResponseStartedException(
    cause: IOException,
) : IOException(cause.message, cause)

private fun String.sanitizeForLog(): String =
    replace('\r', ' ')
        .replace('\n', ' ')
        .replace('\t', ' ')
        .trim()
        .ifEmpty { "-" }
        .let { value ->
            if (value.length <= 240) value else value.take(237) + "..."
        }

private fun String.toLogToken(): String = sanitizeForLog().replace(' ', '-')

private class PrefixedInputStream(
    private val prefix: ByteArray,
    private val delegate: InputStream,
) : InputStream() {
    private var index = 0

    override fun read(): Int {
        if (index < prefix.size) {
            return prefix[index++].toInt() and 0xff
        }
        return delegate.read()
    }

    override fun read(
        bytes: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        if (index < prefix.size) {
            val count = minOf(len, prefix.size - index)
            prefix.copyInto(bytes, off, index, index + count)
            index += count
            return count
        }
        return delegate.read(bytes, off, len)
    }

    override fun available(): Int = (prefix.size - index) + delegate.available()
}

private class PlainHttpUpstream(
    val descriptor: ProxySessionDescriptor,
    private val target: HttpForwardTarget,
    private val connectedSocket: ConnectedProxySocket,
    private val socket: Socket,
    val input: BufferedInputStream,
    val output: BufferedOutputStream,
) : Closeable {
    val closed = AtomicBoolean(false)

    fun matches(nextTarget: HttpForwardTarget): Boolean =
        !closed.get() &&
            target.secureUpstream == nextTarget.secureUpstream &&
            target.host.equals(nextTarget.host, ignoreCase = true) &&
            target.port == nextTarget.port

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (_: IOException) {
        }
        connectedSocket.close()
    }
}

private class ConnectedProxySocket(
    val descriptor: ProxySessionDescriptor,
    val socket: Socket,
    private val onClose: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onClose()
    }
}

interface ProxySecureSocketFactory {
    @Throws(IOException::class)
    fun create(socket: Socket, host: String, port: Int): Socket
}

object SystemProxySecureSocketFactory : ProxySecureSocketFactory {
    override fun create(socket: Socket, host: String, port: Int): Socket {
        val secureSocket =
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as? SSLSocket)
                ?: throw IOException("Failed to create secure upstream socket.")
        secureSocket.useClientMode = true
        secureSocket.sslParameters =
            secureSocket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
        secureSocket.startHandshake()
        return secureSocket
    }
}

private fun String.isUnsupportedIpv6Target(): Boolean = contains(':') && toIpv4LiteralOrNull() == null

private fun String.isMalformedIpv4LiteralCandidate(): Boolean =
    count { it == '.' } == 3 &&
        all { it == '.' || it.isDigit() } &&
        toIpv4LiteralOrNull() == null
