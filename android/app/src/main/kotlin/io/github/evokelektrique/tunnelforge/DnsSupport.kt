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
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
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
    val resolvedIpv4Candidates: List<String> = listOf(resolvedIpv4),
    val tlsHostname: String = host,
    val requestAuthority: String = host,
    val requestPath: String = DOH_PATH,
    val overridePort: Int? = null,
) {
    val port: Int
        get() = overridePort ?: protocol.defaultPort

    val dohPath: String
        get() = requestPath

    val ipv4Candidates: List<String>
        get() = resolvedIpv4Candidates.distinct().ifEmpty { listOf(resolvedIpv4) }
}

private data class ParsedDoHEndpoint(
    val normalized: String,
    val hostname: String,
    val authority: String,
    val requestTarget: String,
    val port: Int,
)

internal object DnsConfigSupport {
    fun sanitize(raw: List<DnsServerConfig>?): List<DnsServerConfig> {
        val out = mutableListOf<DnsServerConfig>()
        val seen = linkedSetOf<String>()
        raw.orEmpty().forEach { entry ->
            val host = normalizeHost(entry)
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
    fun resolveUpstreamServers(
        servers: List<DnsServerConfig>,
        hostnameResolver: (String) -> List<String> = ::resolveIpv4HostnameCandidates,
    ): List<ResolvedDnsServerConfig> {
        val sanitized = sanitize(servers)
        val out = mutableListOf<ResolvedDnsServerConfig>()
        sanitized.forEach { server ->
            val dohEndpoint =
                if (server.protocol == DnsProtocol.dnsOverHttps) {
                    parseDoHEndpoint(server.host)
                } else {
                    null
                }
            val resolveHost = dohEndpoint?.hostname ?: server.host
            val resolvedIpv4Candidates =
                resolveHost.toIpv4LiteralOrNull()?.let { listOf(it) }
                    ?: hostnameResolver(resolveHost).distinct()
            val resolvedIpv4 =
                resolvedIpv4Candidates.firstOrNull()
                    ?: throw IOException("Could not resolve IPv4 address for ${resolveHost.trim()}")
            out.add(
                ResolvedDnsServerConfig(
                    host = server.host,
                    protocol = server.protocol,
                    resolvedIpv4 = resolvedIpv4,
                    resolvedIpv4Candidates = resolvedIpv4Candidates,
                    tlsHostname = dohEndpoint?.hostname ?: server.host,
                    requestAuthority = dohEndpoint?.authority ?: server.host,
                    requestPath = dohEndpoint?.requestTarget ?: DOH_PATH,
                    overridePort = dohEndpoint?.port,
                ),
            )
        }
        return out
    }

    fun isValid(server: DnsServerConfig): Boolean {
        val host = normalizeHost(server)
        if (host.isEmpty()) return false
        if (server.protocol == DnsProtocol.dnsOverHttps) {
            return parseDoHEndpoint(host) != null
        }
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
                DnsProtocol.dnsOverTls -> "a hostname"
                DnsProtocol.dnsOverHttps -> "a hostname or HTTPS URL"
            }
        return "$label must be $requirement for ${server.protocol.displayLabel}"
    }

    private fun normalizeHost(server: DnsServerConfig): String {
        val host = server.host.trim()
        if (host.isEmpty()) return ""
        return if (server.protocol == DnsProtocol.dnsOverHttps) {
            parseDoHEndpoint(host)?.normalized ?: host
        } else {
            host
        }
    }

    private fun parseDoHEndpoint(value: String): ParsedDoHEndpoint? {
        val token = value.trim()
        if (token.isEmpty()) return null
        val hasScheme = token.contains("://")
        val uri =
            try {
                URI(if (hasScheme) token else "https://$token")
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (uri.scheme?.lowercase() != "https" || !uri.userInfo.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) {
            return null
        }
        val normalizedHost = uri.host ?: return null
        if (
            normalizedHost.isEmpty() ||
                normalizedHost.toIpv4LiteralOrNull() != null ||
                !isValidHostname(normalizedHost)
        ) {
            return null
        }
        val explicitTarget =
            hasScheme ||
                token.contains('/') ||
                token.contains('?') ||
                token.contains(':')
        val authority = buildString {
            append(normalizedHost)
            if (uri.port >= 0) {
                append(':')
                append(uri.port)
            }
        }
        val path =
            when {
                !uri.rawPath.isNullOrEmpty() -> uri.rawPath
                explicitTarget -> "/"
                else -> DOH_PATH
            }
        val query = if (uri.rawQuery.isNullOrEmpty()) "" else "?${uri.rawQuery}"
        val normalized =
            if (hasScheme) {
                "https://$authority$path$query"
            } else if (explicitTarget) {
                "$authority$path$query"
            } else {
                normalizedHost
            }
        return ParsedDoHEndpoint(
            normalized = normalized,
            hostname = normalizedHost,
            authority = authority,
            requestTarget = path + query,
            port = if (uri.port >= 0) uri.port else DnsProtocol.dnsOverHttps.defaultPort,
        )
    }

    private fun resolveIpv4HostnameCandidates(host: String): List<String> {
        val resolved =
            InetAddress.getAllByName(host.trim())
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .distinct()
        if (resolved.isEmpty()) {
            throw IOException("Could not resolve IPv4 address for ${host.trim()}")
        }
        return resolved
    }

    private fun resolveIpv4Hostname(host: String): String {
        val resolved =
            resolveIpv4HostnameCandidates(host).firstOrNull()
                ?: throw IOException("Could not resolve IPv4 address for ${host.trim()}")
        return resolved
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

internal interface DnsSocketProtector {
    @Throws(IOException::class)
    fun protect(socket: Socket): Boolean

    @Throws(IOException::class)
    fun protect(socket: DatagramSocket): Boolean

    object None : DnsSocketProtector {
        override fun protect(socket: Socket): Boolean = true

        override fun protect(socket: DatagramSocket): Boolean = true
    }
}

internal class DirectDnsExchangeClient(
    private val servers: List<ResolvedDnsServerConfig>,
    private val logger: (Int, String) -> Unit,
    private val socketProtector: DnsSocketProtector = DnsSocketProtector.None,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : DnsExchangeClient {
    private val protectFallbackLogged = AtomicBoolean(false)
    private val protectedRouteFailures = ConcurrentHashMap<DnsUpstreamKey, Long>()
    private val preferredUpstreams = ConcurrentHashMap<DnsServerKey, DnsUpstreamPreference>()

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
        var lastError: IOException? = null
        val candidateOrder = orderedCandidates(server)
        for ((index, candidateIp) in candidateOrder.withIndex()) {
            val routeOrder = orderedRoutes(server, candidateIp)
            for (route in routeOrder) {
                try {
                    val response = exchangeWithCandidate(query, server, candidateIp, route)
                    rememberSuccess(server, candidateIp, route)
                    return response
                } catch (e: IOException) {
                    lastError = e
                    val phase = e.failurePhase
                    if (route == DnsUpstreamRoute.Protected && phase == DnsFailurePhase.Connect) {
                        markProtectedRouteFailed(server, candidateIp)
                    }
                    if (route == DnsUpstreamRoute.Protected && shouldTryVpnFallback(server, e, routeOrder)) {
                        logger(
                            Log.WARN,
                            "dns upstream route failed protocol=${server.protocol.wireValue} host=${server.host} ip=$candidateIp candidate=${index + 1}/${candidateOrder.size} route=protected phase=${phase.logValue} error=${e.message}; retrying route=vpn",
                        )
                        continue
                    }
                    logger(
                        Log.WARN,
                        "dns upstream route failed protocol=${server.protocol.wireValue} host=${server.host} ip=$candidateIp candidate=${index + 1}/${candidateOrder.size} route=${route.logValue} phase=${phase.logValue} error=${e.message}",
                    )
                    break
                }
            }
        }
        throw lastError ?: IOException("DNS upstream ${server.host} has no IPv4 candidates.")
    }

    @Throws(IOException::class)
    private fun exchangeWithCandidate(
        query: ByteArray,
        server: ResolvedDnsServerConfig,
        candidateIp: String,
        route: DnsUpstreamRoute,
    ): ByteArray {
        return when (server.protocol) {
            DnsProtocol.dnsOverUdp -> exchangeUdp(query, server, candidateIp, route)
            DnsProtocol.dnsOverTcp -> exchangeTcp(query, server, candidateIp, route)
            DnsProtocol.dnsOverTls -> exchangeTls(query, server, candidateIp, route)
            DnsProtocol.dnsOverHttps -> exchangeHttps(query, server, candidateIp, route)
        }
    }

    @Throws(IOException::class)
    private fun exchangeUdp(
        query: ByteArray,
        server: ResolvedDnsServerConfig,
        candidateIp: String,
        route: DnsUpstreamRoute,
    ): ByteArray {
        DatagramSocket().use { socket ->
            if (route == DnsUpstreamRoute.Protected) {
                protectDatagramSocket(socket, server, candidateIp)
            }
            socket.soTimeout = DNS_READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(candidateIp, server.port))
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(MAX_DNS_PACKET_LEN)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            return packet.data.copyOf(packet.length)
        }
    }

    @Throws(IOException::class)
    private fun exchangeTcp(
        query: ByteArray,
        server: ResolvedDnsServerConfig,
        candidateIp: String,
        route: DnsUpstreamRoute,
    ): ByteArray =
        openPlainSocket(server, candidateIp, route).use { socket ->
            try {
                exchangeDnsOverStream(socket.getInputStream(), socket.getOutputStream(), query)
            } catch (e: IOException) {
                throw DnsUpstreamIOException(DnsFailurePhase.Exchange, "DNS-over-TCP exchange failed: ${e.message}", e)
            }
        }

    @Throws(IOException::class)
    private fun exchangeTls(
        query: ByteArray,
        server: ResolvedDnsServerConfig,
        candidateIp: String,
        route: DnsUpstreamRoute,
    ): ByteArray =
        openPlainSocket(server, candidateIp, route).use { plainSocket ->
            openTlsSocketForExchange(plainSocket, server).use { socket ->
                try {
                    exchangeDnsOverStream(socket.getInputStream(), socket.getOutputStream(), query)
                } catch (e: IOException) {
                    throw DnsUpstreamIOException(DnsFailurePhase.Exchange, "DNS-over-TLS exchange failed: ${e.message}", e)
                }
            }
        }

    @Throws(IOException::class)
    private fun exchangeHttps(
        query: ByteArray,
        server: ResolvedDnsServerConfig,
        candidateIp: String,
        route: DnsUpstreamRoute,
    ): ByteArray =
        openPlainSocket(server, candidateIp, route).use { plainSocket ->
            openTlsSocketForExchange(plainSocket, server).use { socket ->
                try {
                    exchangeDnsOverHttps(socket.getInputStream(), socket.getOutputStream(), query, server)
                } catch (e: IOException) {
                    throw DnsUpstreamIOException(DnsFailurePhase.Exchange, "DNS-over-HTTPS exchange failed: ${e.message}", e)
                }
            }
        }

    @Throws(IOException::class)
    private fun openPlainSocket(
        server: ResolvedDnsServerConfig,
        candidateIp: String,
        route: DnsUpstreamRoute,
    ): Socket {
        val socket = Socket()
        try {
            socket.bind(null)
            if (route == DnsUpstreamRoute.Protected) {
                protectSocket(socket, server, candidateIp)
            }
            try {
                socket.connect(InetSocketAddress(candidateIp, server.port), DNS_CONNECT_TIMEOUT_MS)
            } catch (e: IOException) {
                throw DnsUpstreamIOException(DnsFailurePhase.Connect, "connect failed: ${e.message}", e)
            }
            socket.soTimeout = DNS_READ_TIMEOUT_MS
            return socket
        } catch (e: IOException) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            throw e
        } catch (e: RuntimeException) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            throw e
        }
    }

    @Throws(IOException::class)
    private fun protectSocket(socket: Socket, server: ResolvedDnsServerConfig, candidateIp: String) {
        if (!socketProtector.protect(socket)) {
            logProtectFallback(server, candidateIp)
        }
    }

    @Throws(IOException::class)
    private fun protectDatagramSocket(socket: DatagramSocket, server: ResolvedDnsServerConfig, candidateIp: String) {
        if (!socketProtector.protect(socket)) {
            logProtectFallback(server, candidateIp)
        }
    }

    private fun logProtectFallback(server: ResolvedDnsServerConfig, candidateIp: String) {
        if (protectFallbackLogged.compareAndSet(false, true)) {
            logger(
                Log.WARN,
                "dns upstream protect failed protocol=${server.protocol.wireValue} host=${server.host} ip=$candidateIp route=protected phase=protect; routing upstream through VPN fallback",
            )
        }
    }

    @Throws(IOException::class)
    private fun openTlsSocketForExchange(socket: Socket, server: ResolvedDnsServerConfig): SSLSocket {
        return try {
            openTlsSocket(socket, server)
        } catch (e: IOException) {
            throw DnsUpstreamIOException(DnsFailurePhase.Tls, "TLS failed: ${e.message}", e)
        }
    }

    private fun orderedCandidates(server: ResolvedDnsServerConfig): List<String> {
        val candidates = server.ipv4Candidates
        val preferred = preferredUpstreams[server.key]?.candidateIp
        return if (preferred != null && candidates.contains(preferred)) {
            listOf(preferred) + candidates.filterNot { it == preferred }
        } else {
            candidates
        }
    }

    private fun orderedRoutes(server: ResolvedDnsServerConfig, candidateIp: String): List<DnsUpstreamRoute> {
        if (!server.protocol.supportsRouteFallback) return listOf(DnsUpstreamRoute.Protected)
        val preferred = preferredUpstreams[server.key]
        if (preferred?.candidateIp == candidateIp && preferred.route == DnsUpstreamRoute.UnprotectedFallback) {
            return listOf(DnsUpstreamRoute.UnprotectedFallback, DnsUpstreamRoute.Protected)
        }
        return if (isProtectedRoutePenalized(server, candidateIp)) {
            listOf(DnsUpstreamRoute.UnprotectedFallback)
        } else {
            listOf(DnsUpstreamRoute.Protected, DnsUpstreamRoute.UnprotectedFallback)
        }
    }

    private fun shouldTryVpnFallback(
        server: ResolvedDnsServerConfig,
        error: IOException,
        routeOrder: List<DnsUpstreamRoute>,
    ): Boolean =
        server.protocol.supportsRouteFallback &&
            error.failurePhase == DnsFailurePhase.Connect &&
            routeOrder.contains(DnsUpstreamRoute.UnprotectedFallback)

    private fun markProtectedRouteFailed(server: ResolvedDnsServerConfig, candidateIp: String) {
        protectedRouteFailures[server.upstreamKey(candidateIp)] = nowMs() + PROTECTED_ROUTE_FAILURE_TTL_MS
    }

    private fun rememberSuccess(server: ResolvedDnsServerConfig, candidateIp: String, route: DnsUpstreamRoute) {
        preferredUpstreams[server.key] = DnsUpstreamPreference(candidateIp, route)
        if (route == DnsUpstreamRoute.Protected) {
            protectedRouteFailures.remove(server.upstreamKey(candidateIp))
        }
    }

    private fun isProtectedRoutePenalized(server: ResolvedDnsServerConfig, candidateIp: String): Boolean {
        val key = server.upstreamKey(candidateIp)
        val expiresAt = protectedRouteFailures[key] ?: return false
        if (expiresAt > nowMs()) return true
        protectedRouteFailures.remove(key)
        return false
    }

    private val IOException.failurePhase: DnsFailurePhase
        get() = (this as? DnsUpstreamIOException)?.phase ?: DnsFailurePhase.Exchange

    private enum class DnsUpstreamRoute {
        Protected,
        UnprotectedFallback,
    }

    private val DnsUpstreamRoute.logValue: String
        get() =
            when (this) {
                DnsUpstreamRoute.Protected -> "protected"
                DnsUpstreamRoute.UnprotectedFallback -> "vpn"
            }

    private val DnsProtocol.supportsRouteFallback: Boolean
        get() = this == DnsProtocol.dnsOverTcp || this == DnsProtocol.dnsOverTls || this == DnsProtocol.dnsOverHttps

    private val ResolvedDnsServerConfig.key: DnsServerKey
        get() = DnsServerKey(host = host, protocol = protocol, port = port)

    private fun ResolvedDnsServerConfig.upstreamKey(candidateIp: String): DnsUpstreamKey =
        DnsUpstreamKey(host = host, protocol = protocol, port = port, candidateIp = candidateIp)

    private data class DnsServerKey(
        val host: String,
        val protocol: DnsProtocol,
        val port: Int,
    )

    private data class DnsUpstreamKey(
        val host: String,
        val protocol: DnsProtocol,
        val port: Int,
        val candidateIp: String,
    )

    private data class DnsUpstreamPreference(
        val candidateIp: String,
        val route: DnsUpstreamRoute,
    )

    private enum class DnsFailurePhase(val logValue: String) {
        Connect("connect"),
        Tls("tls"),
        Exchange("exchange"),
    }

    private class DnsUpstreamIOException(
        val phase: DnsFailurePhase,
        message: String,
        cause: IOException,
    ) : IOException(message, cause)

    companion object {
        private const val PROTECTED_ROUTE_FAILURE_TTL_MS = 60_000L
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
            append("Host: ${server.requestAuthority}\r\n")
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
    val sslSocket = factory.createSocket(socket, server.tlsHostname, server.port, true) as SSLSocket
    val parameters = sslSocket.sslParameters
    parameters.endpointIdentificationAlgorithm = "HTTPS"
    try {
        parameters.serverNames = listOf(SNIHostName(server.tlsHostname))
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
private const val DOH_PATH = "/dns-query"
