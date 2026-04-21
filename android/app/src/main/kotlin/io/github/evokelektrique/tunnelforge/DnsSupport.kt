package io.github.evokelektrique.tunnelforge

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal enum class DnsProtocol(
    val wireValue: String,
    val shortLabel: String,
    val displayLabel: String,
    val defaultPort: Int,
) {
    dnsOverTcp("dnsOverTcp", "TCP", "DNS-over-TCP", 53),
    dnsOverUdp("dnsOverUdp", "UDP", "DNS-over-UDP", 53),
    dnsOverTls("dnsOverTls", "TLS", "DNS-over-TLS", 853),
    dnsOverHttps("dnsOverHttps", "HTTPS", "DNS-over-HTTPS", 443),
    ;

    val requiresHostname: Boolean
        get() = this == dnsOverTls || this == dnsOverHttps

    companion object {
        fun fromWireValue(raw: String?): DnsProtocol =
            entries.firstOrNull { it.wireValue == raw } ?: dnsOverUdp
    }
}

internal data class DnsServerConfig(
    val host: String,
    val protocol: DnsProtocol,
)

internal data class ResolvedDnsServerConfig(
    val host: String,
    val protocol: DnsProtocol,
    val resolvedIpv4: String,
) {
    val port: Int
        get() = protocol.defaultPort

    val dohPath: String
        get() = "/dns-query"
}

internal object DnsConfigSupport {
    fun sanitize(raw: List<DnsServerConfig>?): List<DnsServerConfig> {
        val out = mutableListOf<DnsServerConfig>()
        val seen = linkedSetOf<String>()
        raw.orEmpty().forEach { entry ->
            val host = entry.host.trim()
            if (host.isEmpty()) return@forEach
            if (!isValid(entry.copy(host = host))) return@forEach
            val fingerprint = "${entry.protocol.wireValue}:$host"
            if (!seen.add(fingerprint)) return@forEach
            out.add(entry.copy(host = host))
        }
        return out
    }

    fun negotiatedServers(primaryDns: IntArray, secondaryDns: IntArray): List<ResolvedDnsServerConfig> {
        val out = mutableListOf<ResolvedDnsServerConfig>()
        val seen = linkedSetOf<String>()
        listOfNotNull(ipv4FromOctets(primaryDns), ipv4FromOctets(secondaryDns)).forEach { ip ->
            if (seen.add(ip)) {
                out.add(
                    ResolvedDnsServerConfig(
                        host = ip,
                        protocol = DnsProtocol.dnsOverUdp,
                        resolvedIpv4 = ip,
                    ),
                )
            }
        }
        return out
    }

    @Throws(IOException::class)
    fun resolveUpstreamServers(servers: List<DnsServerConfig>): List<ResolvedDnsServerConfig> {
        val sanitized = sanitize(servers)
        val out = mutableListOf<ResolvedDnsServerConfig>()
        sanitized.forEach { server ->
            val resolvedIpv4 = server.host.toIpv4LiteralOrNull() ?: resolveIpv4Hostname(server.host)
            out.add(
                ResolvedDnsServerConfig(
                    host = server.host,
                    protocol = server.protocol,
                    resolvedIpv4 = resolvedIpv4,
                ),
            )
        }
        return out
    }

    fun isValid(server: DnsServerConfig): Boolean {
        val host = server.host.trim()
        if (host.isEmpty()) return false
        return if (server.protocol.requiresHostname) {
            host.toIpv4LiteralOrNull() == null && isValidHostname(host)
        } else {
            host.toIpv4LiteralOrNull() != null || isValidHostname(host)
        }
    }

    fun validationMessage(label: String, server: DnsServerConfig): String {
        val requirement =
            when (server.protocol) {
                DnsProtocol.dnsOverTcp, DnsProtocol.dnsOverUdp -> "a hostname or IPv4 address"
                DnsProtocol.dnsOverTls, DnsProtocol.dnsOverHttps -> "a hostname"
            }
        return "$label server \"${server.host}\" must be $requirement for ${server.protocol.displayLabel}"
    }

    private fun resolveIpv4Hostname(host: String): String {
        val resolved =
            InetAddress.getAllByName(host.trim()).firstOrNull { it is Inet4Address }
                ?: throw IOException("Could not resolve IPv4 address for ${host.trim()}")
        return resolved.hostAddress ?: throw IOException("Resolved empty IPv4 address for ${host.trim()}")
    }

    private fun ipv4FromOctets(octets: IntArray?): String? {
        if (octets == null || octets.size < 4) return null
        val values = IntArray(4)
        for (i in 0..3) {
            val value = octets[i]
            if (value !in 0..255) return null
            values[i] = value
        }
        if (values.all { it == 0 }) return null
        return "${values[0]}.${values[1]}.${values[2]}.${values[3]}"
    }

    private fun isValidHostname(host: String): Boolean {
        if (host.length > 253 ||
            host.startsWith('.') ||
            host.endsWith('.') ||
            host.contains("://") ||
            host.contains('/') ||
            host.contains('?') ||
            host.contains('#') ||
            host.contains(':')
        ) {
            return false
        }
        val labels = host.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return false
        val labelPattern = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
        return labels.all { labelPattern.matches(it) }
    }
}

internal interface DnsExchangeClient {
    @Throws(IOException::class)
    fun exchange(query: ByteArray): ByteArray
}

internal class DirectDnsExchangeClient(
    private val servers: List<ResolvedDnsServerConfig>,
    private val logger: (Int, String) -> Unit,
) : DnsExchangeClient {
    @Throws(IOException::class)
    override fun exchange(query: ByteArray): ByteArray {
        var lastError: IOException? = null
        for ((index, server) in servers.withIndex()) {
            try {
                return exchangeWithServer(query, server)
            } catch (e: IOException) {
                lastError = e
                val hasNext = index < servers.lastIndex
                logger(
                    Log.WARN,
                    "dns upstream failover protocol=${server.protocol.wireValue} host=${server.host} ip=${server.resolvedIpv4} error=${e.message}${if (hasNext) " next=${servers[index + 1].host}" else ""}",
                )
            }
        }
        throw lastError ?: IOException("No DNS upstream servers are available.")
    }

    @Throws(IOException::class)
    private fun exchangeWithServer(query: ByteArray, server: ResolvedDnsServerConfig): ByteArray {
        return when (server.protocol) {
            DnsProtocol.dnsOverUdp -> exchangeUdp(query, server)
            DnsProtocol.dnsOverTcp -> exchangeTcp(query, server)
            DnsProtocol.dnsOverTls -> exchangeTls(query, server)
            DnsProtocol.dnsOverHttps -> exchangeHttps(query, server)
        }
    }

    @Throws(IOException::class)
    private fun exchangeUdp(query: ByteArray, server: ResolvedDnsServerConfig): ByteArray {
        DatagramSocket().use { socket ->
            socket.soTimeout = DNS_READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(server.resolvedIpv4, server.port))
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(MAX_DNS_PACKET_LEN)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            return packet.data.copyOf(packet.length)
        }
    }

    @Throws(IOException::class)
    private fun exchangeTcp(query: ByteArray, server: ResolvedDnsServerConfig): ByteArray =
        openPlainSocket(server).use { socket ->
            exchangeDnsOverStream(socket.getInputStream(), socket.getOutputStream(), query)
        }

    @Throws(IOException::class)
    private fun exchangeTls(query: ByteArray, server: ResolvedDnsServerConfig): ByteArray =
        openTlsSocket(openPlainSocket(server), server).use { socket ->
            exchangeDnsOverStream(socket.getInputStream(), socket.getOutputStream(), query)
        }

    @Throws(IOException::class)
    private fun exchangeHttps(query: ByteArray, server: ResolvedDnsServerConfig): ByteArray =
        openTlsSocket(openPlainSocket(server), server).use { socket ->
            exchangeDnsOverHttps(socket.getInputStream(), socket.getOutputStream(), query, server)
        }

    @Throws(IOException::class)
    private fun openPlainSocket(server: ResolvedDnsServerConfig): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(server.resolvedIpv4, server.port), DNS_CONNECT_TIMEOUT_MS)
        socket.soTimeout = DNS_READ_TIMEOUT_MS
        return socket
    }
}

internal class LocalDnsServer(
    private val exchangeClient: DnsExchangeClient,
    private val logger: (Int, String) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val threads = CopyOnWriteArrayList<Thread>()
    private var udpSocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null

    @Throws(IOException::class)
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            udpSocket = DatagramSocket(InetSocketAddress(LOCALHOST_IPV4, DNS_PORT)).apply {
                soTimeout = LOCAL_SERVER_POLL_TIMEOUT_MS
                reuseAddress = true
            }
            tcpServerSocket = ServerSocket(DNS_PORT, 50, InetAddress.getByName(LOCALHOST_IPV4)).apply {
                reuseAddress = true
                soTimeout = LOCAL_SERVER_POLL_TIMEOUT_MS
            }
        } catch (e: IOException) {
            close()
            throw e
        }
        threads += loopThread("local-dns-udp", ::runUdpLoop)
        threads += loopThread("local-dns-tcp", ::runTcpLoop)
    }

    override fun close() {
        running.set(false)
        try {
            udpSocket?.close()
        } catch (_: IOException) {
        }
        udpSocket = null
        try {
            tcpServerSocket?.close()
        } catch (_: IOException) {
        }
        tcpServerSocket = null
        threads.forEach { it.interrupt() }
        threads.clear()
    }

    private fun loopThread(name: String, block: () -> Unit): Thread =
        Thread(block, name).also { it.start() }

    private fun runUdpLoop() {
        val socket = udpSocket ?: return
        val buffer = ByteArray(MAX_DNS_PACKET_LEN)
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val request = DatagramPacket(buffer, buffer.size)
                socket.receive(request)
                val query = request.data.copyOf(request.length)
                val response = exchangeClient.exchange(query)
                socket.send(
                    DatagramPacket(
                        response,
                        response.size,
                        request.address,
                        request.port,
                    ),
                )
            } catch (e: IOException) {
                if (!running.get()) break
                logger(Log.WARN, "local dns udp error=${e.message}")
            }
        }
    }

    private fun runTcpLoop() {
        val serverSocket = tcpServerSocket ?: return
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val client = serverSocket.accept()
                client.soTimeout = DNS_READ_TIMEOUT_MS
                loopThread("local-dns-tcp-client") {
                    client.use { socket ->
                        handleTcpClient(socket)
                    }
                }
            } catch (e: IOException) {
                if (!running.get()) break
                logger(Log.WARN, "local dns tcp accept error=${e.message}")
            }
        }
    }

    private fun handleTcpClient(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val query = readFramedDnsMessage(input) ?: break
                val response = exchangeClient.exchange(query)
                writeFramedDnsMessage(output, response)
                output.flush()
            } catch (_: EOFException) {
                break
            } catch (e: IOException) {
                logger(Log.WARN, "local dns tcp client error=${e.message}")
                break
            }
        }
    }

    companion object {
        const val LOCALHOST_IPV4 = "127.0.0.1"
        private const val DNS_PORT = 53
        private const val LOCAL_SERVER_POLL_TIMEOUT_MS = 1000
    }
}

internal fun exchangeDnsOverStream(
    input: InputStream,
    output: OutputStream,
    query: ByteArray,
): ByteArray {
    writeFramedDnsMessage(output, query)
    output.flush()
    return readFramedDnsMessage(input) ?: throw EOFException("DNS-over-stream response was empty.")
}

internal fun writeFramedDnsMessage(output: OutputStream, payload: ByteArray) {
    val length = payload.size
    output.write((length ushr 8) and 0xff)
    output.write(length and 0xff)
    output.write(payload)
}

internal fun readFramedDnsMessage(input: InputStream): ByteArray? {
    val hi = input.read()
    if (hi < 0) return null
    val lo = input.read()
    if (lo < 0) throw EOFException("Truncated DNS-over-stream length prefix.")
    val length = (hi shl 8) or lo
    return readFully(input, length)
}

internal fun exchangeDnsOverHttps(
    input: InputStream,
    output: OutputStream,
    query: ByteArray,
    server: ResolvedDnsServerConfig,
): ByteArray {
    val headers =
        buildString {
            append("POST ${server.dohPath} HTTP/1.1\r\n")
            append("Host: ${server.host}\r\n")
            append("Accept: application/dns-message\r\n")
            append("Content-Type: application/dns-message\r\n")
            append("Content-Length: ${query.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
    output.write(headers)
    output.write(query)
    output.flush()

    val buffered = BufferedInputStream(input)
    val statusLine = readHttpLine(buffered)
    val parts = statusLine.split(' ')
    if (parts.size < 2) {
        throw IOException("Malformed DNS-over-HTTPS status line: $statusLine")
    }
    val statusCode =
        parts[1].toIntOrNull()
            ?: throw IOException("Malformed DNS-over-HTTPS status code: $statusLine")
    if (statusCode != 200) {
        throw IOException("DNS-over-HTTPS server returned HTTP $statusCode")
    }

    val headersMap = linkedMapOf<String, String>()
    while (true) {
        val line = readHttpLine(buffered)
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.substring(0, colon).trim().lowercase()
        val value = line.substring(colon + 1).trim()
        headersMap[name] = value
    }

    val transferEncoding = headersMap["transfer-encoding"]?.lowercase()
    if (transferEncoding == "chunked") {
        return readChunkedBody(buffered)
    }
    val contentLength = headersMap["content-length"]?.toIntOrNull()
    return if (contentLength != null) {
        readFully(buffered, contentLength)
    } else {
        buffered.readBytes()
    }
}

internal fun openTlsSocket(socket: Socket, server: ResolvedDnsServerConfig): SSLSocket {
    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
    val sslSocket = factory.createSocket(socket, server.host, server.port, true) as SSLSocket
    val parameters = sslSocket.sslParameters
    parameters.endpointIdentificationAlgorithm = "HTTPS"
    try {
        parameters.serverNames = listOf(SNIHostName(server.host))
    } catch (_: IllegalArgumentException) {
    }
    sslSocket.sslParameters = parameters
    sslSocket.soTimeout = DNS_READ_TIMEOUT_MS
    sslSocket.startHandshake()
    return sslSocket
}

internal fun readHttpLine(input: InputStream): String {
    val buffer = ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        if (next < 0) throw EOFException("Unexpected EOF while reading HTTP line.")
        if (next == '\n'.code) break
        if (next != '\r'.code) buffer.write(next)
    }
    return buffer.toString(StandardCharsets.US_ASCII.name())
}

internal fun readChunkedBody(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    while (true) {
        val sizeLine = readHttpLine(input)
        val chunkSize = sizeLine.substringBefore(';').trim().toInt(16)
        if (chunkSize == 0) {
            while (readHttpLine(input).isNotEmpty()) {
            }
            return output.toByteArray()
        }
        output.write(readFully(input, chunkSize))
        val cr = input.read()
        val lf = input.read()
        if (cr != '\r'.code || lf != '\n'.code) {
            throw IOException("Malformed chunked DNS-over-HTTPS body.")
        }
    }
}

internal fun readFully(input: InputStream, length: Int): ByteArray {
    val out = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = input.read(out, offset, length - offset)
        if (read < 0) throw EOFException("Unexpected EOF while reading $length bytes.")
        offset += read
    }
    return out
}

private const val DNS_CONNECT_TIMEOUT_MS = 5_000
private const val DNS_READ_TIMEOUT_MS = 5_000
private const val MAX_DNS_PACKET_LEN = 65_535
