package com.example.tunnel_forge

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class ProxyRuntimeConfig(
    val httpEnabled: Boolean,
    val httpPort: Int,
    val socksEnabled: Boolean,
    val socksPort: Int,
)

/**
 * Loopback-only local proxy listeners.
 *
 * v1 runtime responsibility is listener lifecycle and protocol-safe failure responses until the
 * userspace transport is attached to [ProxyPacketBridge].
 */
class ProxyServerRuntime(
    private val config: ProxyRuntimeConfig,
    private val logger: (String) -> Unit,
    private val transport: ProxyTransport = StubProxyTransport(),
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
) {
    private val running = AtomicBoolean(false)
    private val listenerThreads = CopyOnWriteArrayList<Thread>()
    private val clientThreads = CopyOnWriteArrayList<Thread>()
    private val serverSockets = CopyOnWriteArrayList<ServerSocket>()

    fun start() {
        if (!config.httpEnabled && !config.socksEnabled) {
            throw IllegalArgumentException("Enable HTTP or SOCKS5 before starting proxy listeners")
        }
        if (config.httpEnabled && config.socksEnabled && config.httpPort == config.socksPort) {
            throw IllegalArgumentException("HTTP and SOCKS5 ports must differ")
        }
        if (!running.compareAndSet(false, true)) return
        logger("proxy runtime start http=${if (config.httpEnabled) config.httpPort else "off"} socks=${if (config.socksEnabled) config.socksPort else "off"}")
        try {
            if (config.httpEnabled) {
                startListener("http", config.httpPort, ::handleHttpClient)
            }
            if (config.socksEnabled) {
                startListener("socks5", config.socksPort, ::handleSocksClient)
            }
        } catch (t: Throwable) {
            stop()
            throw t
        }
    }

    fun stop() {
        running.set(false)
        logger("proxy runtime stop listeners=${listenerThreads.size} clients=${clientThreads.size}")
        for (socket in serverSockets) {
            closeQuietly(socket)
        }
        serverSockets.clear()
        for (thread in listenerThreads) {
            thread.interrupt()
        }
        for (thread in clientThreads) {
            thread.interrupt()
        }
        listenerThreads.clear()
        clientThreads.clear()
    }

    fun endpointSummary(): String {
        val endpoints = mutableListOf<String>()
        if (config.httpEnabled) endpoints.add("HTTP 127.0.0.1:${config.httpPort}")
        if (config.socksEnabled) endpoints.add("SOCKS5 127.0.0.1:${config.socksPort}")
        return endpoints.joinToString(", ")
    }

    private fun startListener(
        name: String,
        port: Int,
        handler: (Socket) -> Unit,
    ) {
        val socket = ServerSocket(port, 50, InetAddress.getByName(LOOPBACK_HOST)).apply {
            reuseAddress = true
        }
        serverSockets.add(socket)
        logger("Listening on $name://$LOOPBACK_HOST:$port")
        val thread = Thread(
            {
                try {
                    while (running.get() && !Thread.currentThread().isInterrupted) {
                        try {
                            val client = socket.accept()
                            client.soTimeout = CLIENT_SO_TIMEOUT_MS
                            logger("proxy accept proto=$name from=${client.inetAddress.hostAddress}:${client.port}")
                            startClientThread(name, client, handler)
                        } catch (_: SocketException) {
                            break
                        } catch (e: IOException) {
                            if (running.get()) {
                                logger("$name accept error: ${e.message}")
                            }
                            break
                        }
                    }
                } catch (t: Throwable) {
                    if (running.get()) {
                        logger("$name listener crash: ${t.javaClass.simpleName}: ${t.message}")
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
        val thread = Thread(
            {
                try {
                    client.use {
                        try {
                            handler(client)
                        } catch (_: EOFException) {
                        } catch (e: IOException) {
                            logger("$name client error: ${e.message}")
                        }
                    }
                } catch (t: Throwable) {
                    if (running.get()) {
                        logger("$name client crash: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            },
            "proxy-$name-client",
        )
        clientThreads.add(thread)
        thread.start()
    }

    private fun handleHttpClient(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val firstLine = readAsciiLine(input) ?: return
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
        }
        logger("HTTP proxy request received: $firstLine")
        val parts = firstLine.split(' ')
        if (parts.size < 3) {
            logger("proxy reject proto=http-connect reason=malformed-request-line line=$firstLine")
            writeHttpError(output, 400, "Bad Request", "Malformed HTTP proxy request line.")
            return
        }
        val method = parts[0].uppercase()
        if (method != "CONNECT") {
            logger("proxy reject proto=http method=$method reason=unsupported-method")
            writeHttpError(output, 501, "Not Implemented", "Only HTTP CONNECT is supported in proxy mode.")
            return
        }
        val target = parseHttpConnectTarget(parts[1])
        if (target == null) {
            logger("proxy reject proto=http-connect reason=invalid-target raw=${parts[1]}")
            writeHttpError(output, 400, "Bad Request", "Invalid CONNECT target.")
            return
        }
        val request = ProxyConnectRequest(host = target.first, port = target.second, protocol = "http-connect")
        logger("proxy request proto=http-connect target=${request.host}:${request.port}")
        if (request.host.isUnsupportedIpv6Target()) {
            logger("proxy reject proto=http-connect reason=ipv6-unsupported target=${request.host}:${request.port}")
            writeHttpError(output, 501, "Not Implemented", IPV6_UNSUPPORTED_MESSAGE)
            return
        }
        var sessionId: Int? = null
        try {
            transport.openTcpSession(request).use { session ->
                sessionId = session.descriptor.sessionId
                session.awaitConnected(connectTimeoutMs)
                logger("proxy connect ok proto=http-connect sid=${session.descriptor.sessionId} target=${request.host}:${request.port}")
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
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
            logger(
                "proxy fail proto=http-connect sid=${sessionId?.toString() ?: "pending"} target=${request.host}:${request.port} error=${e.message ?: DEFAULT_TRANSPORT_FAILURE}",
            )
            writeHttpError(output, 503, "Service Unavailable", e.message ?: DEFAULT_TRANSPORT_FAILURE)
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
            logger("proxy reject proto=socks5 reason=malformed-request version=$reqVersion cmd=$cmd atyp=$atyp")
            return
        }
        if (cmd != SOCKS_CMD_CONNECT) {
            logger("proxy reject proto=socks5 reason=unsupported-command cmd=$cmd")
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
        logger("SOCKS5 CONNECT request received: $host:$port")
        val request = ProxyConnectRequest(host = host, port = port, protocol = "socks5-connect")
        logger("proxy request proto=socks5-connect target=${request.host}:${request.port}")
        if (request.host.isUnsupportedIpv6Target()) {
            logger("proxy reject proto=socks5-connect reason=ipv6-unsupported target=${request.host}:${request.port}")
            writeSocksReply(output, SOCKS_REPLY_HOST_UNREACHABLE)
            return
        }
        var sessionId: Int? = null
        try {
            transport.openTcpSession(request).use { session ->
                sessionId = session.descriptor.sessionId
                session.awaitConnected(connectTimeoutMs)
                logger("proxy connect ok proto=socks5-connect sid=${session.descriptor.sessionId} target=${request.host}:${request.port}")
                writeSocksReply(output, SOCKS_REPLY_SUCCEEDED)
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
            logger(
                "proxy fail proto=socks5-connect sid=${sessionId?.toString() ?: "pending"} target=${request.host}:${request.port} error=${e.message ?: DEFAULT_TRANSPORT_FAILURE}",
            )
            writeSocksReply(output, SOCKS_REPLY_GENERAL_FAILURE)
        }
    }

    private fun writeHttpError(output: BufferedOutputStream, code: Int, status: String, message: String) {
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

    private fun writeSocksReply(output: BufferedOutputStream, status: Int) {
        output.write(byteArrayOf(0x05, status.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        output.flush()
    }

    private fun parseHttpConnectTarget(raw: String): Pair<String, Int>? {
        val separator = raw.lastIndexOf(':')
        if (separator <= 0 || separator >= raw.lastIndex) return null
        val host = raw.substring(0, separator).trim().removePrefix("[").removeSuffix("]")
        val port = raw.substring(separator + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val next = input.read()
            if (next < 0) {
                return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            if (next == '\n'.code) {
                if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.removeAt(bytes.lastIndex)
                }
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            bytes.add(next.toByte())
            if (bytes.size >= MAX_HEADER_LINE_LEN) {
                throw IOException("Header line too long")
            }
        }
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val CLIENT_SO_TIMEOUT_MS = 5000
        private const val MAX_HEADER_LINE_LEN = 8192
        private const val DEFAULT_TRANSPORT_FAILURE = "Tunnel forwarding is not attached yet."
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        private const val IPV6_UNSUPPORTED_MESSAGE = "IPv6 targets are not supported in proxy mode."
        private const val SOCKS_CMD_CONNECT = 0x01
        private const val SOCKS_REPLY_SUCCEEDED = 0x00
        private const val SOCKS_REPLY_GENERAL_FAILURE = 0x01
        private const val SOCKS_REPLY_HOST_UNREACHABLE = 0x04
        private const val SOCKS_REPLY_COMMAND_NOT_SUPPORTED = 0x07

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

private fun String.isUnsupportedIpv6Target(): Boolean = contains(':') && toIpv4LiteralOrNull() == null
