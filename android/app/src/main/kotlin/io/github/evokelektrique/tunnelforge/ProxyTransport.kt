package io.github.evokelektrique.tunnelforge

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class ProxyTransportFailureReason {
    localServiceUnavailable,
    upstreamConnectFailed,
    upstreamTimeout,
    dnsFailed,
    networkUnreachable,
    protocolError,
    clientAborted,
}

enum class GvisorTcpFailureCategory {
    cleanEof,
    connectionReset,
    readTimeout,
    writeTimeout,
    sessionClosedLocally,
    internalGvisorError,
}

internal fun gvisorTcpFailureCategory(error: IOException, writePath: Boolean = false): GvisorTcpFailureCategory {
    val message = error.message.orEmpty().lowercase()
    return when {
        "connection reset" in message || "reset by peer" in message -> GvisorTcpFailureCategory.connectionReset
        "session closed locally" in message || "already closed" in message -> GvisorTcpFailureCategory.sessionClosedLocally
        "timeout" in message || "timed out" in message ->
            if (writePath) GvisorTcpFailureCategory.writeTimeout else GvisorTcpFailureCategory.readTimeout
        else -> GvisorTcpFailureCategory.internalGvisorError
    }
}

class ProxyTransportException(
    val failureReason: ProxyTransportFailureReason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class ProxyConnectRequest(
    val host: String,
    val port: Int,
    val protocol: String,
)

data class ProxySessionDescriptor(
    val sessionId: Int,
    val request: ProxyConnectRequest,
    val openedAtMs: Long,
)

data class ProxyClientConnection(
    val socket: Socket,
    val input: InputStream,
    val output: OutputStream,
)

data class ProxyUdpDatagram(
    val host: String,
    val port: Int,
    val payload: ByteArray,
)

interface ProxyTransportSession : Closeable {
    val descriptor: ProxySessionDescriptor

    @Throws(IOException::class)
    fun awaitConnected(timeoutMs: Long)

    @Throws(IOException::class)
    fun readRemoteBytes(maxLen: Int, timeoutMs: Int): ByteArray? {
        throw ProxyTransportException(ProxyTransportFailureReason.localServiceUnavailable, "Streaming reads are not supported by this proxy transport.")
    }

    @Throws(IOException::class)
    fun writeClientBytes(bytes: ByteArray, timeoutMs: Int): Int {
        throw ProxyTransportException(ProxyTransportFailureReason.localServiceUnavailable, "Streaming writes are not supported by this proxy transport.")
    }

    @Throws(IOException::class)
    fun pumpBidirectional(client: ProxyClientConnection)
}

interface ProxyUdpAssociation : Closeable {
    val descriptor: ProxySessionDescriptor

    @Throws(IOException::class)
    fun send(datagram: ProxyUdpDatagram)

    @Throws(IOException::class)
    fun receive(timeoutMs: Long): ProxyUdpDatagram?
}

interface ProxyTransport {
    @Throws(IOException::class)
    fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession

    @Throws(IOException::class)
    fun openUdpAssociation(request: ProxyConnectRequest): ProxyUdpAssociation {
        throw ProxyTransportException(ProxyTransportFailureReason.localServiceUnavailable, "UDP forwarding is not attached yet.")
    }
}

class StubProxyTransport(
    private val reason: String = "Tunnel forwarding is not attached yet.",
) : ProxyTransport {
    override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
        throw ProxyTransportException(ProxyTransportFailureReason.localServiceUnavailable, reason)
    }
}

class BridgeProxyTransport(
    private val stack: UserspaceTunnelStack,
) : ProxyTransport {
    override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
        val session = stack.openTcpSession(request)
        return object : ProxyTransportSession {
            override val descriptor: ProxySessionDescriptor = session.descriptor

            override fun awaitConnected(timeoutMs: Long) {
                session.awaitEstablished(timeoutMs)
            }

            override fun pumpBidirectional(client: ProxyClientConnection) {
                session.pumpBidirectional(client)
            }

            override fun close() {
                session.close()
            }
        }
    }

    override fun openUdpAssociation(request: ProxyConnectRequest): ProxyUdpAssociation {
        val association = stack.openUdpAssociation(request)
        logDebug("proxy udp open sid=${association.descriptor.sessionId}")
        return object : ProxyUdpAssociation {
            override val descriptor: ProxySessionDescriptor = association.descriptor

            override fun send(datagram: ProxyUdpDatagram) {
                association.send(datagram)
            }

            override fun receive(timeoutMs: Long): ProxyUdpDatagram? = association.receive(timeoutMs)

            override fun close() {
                logDebug("proxy udp close sid=${descriptor.sessionId}")
                association.close()
            }
        }
    }

    private companion object {
        private const val TAG = "BridgeProxyTransport"

        private fun logDebug(message: String) {
            try {
                AppLog.d(TAG, message)
            } catch (_: RuntimeException) {
                // Unit tests run without Android logging runtime.
            }
        }
    }
}

class DirectSocketProxyTransport(
    private val resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val socketFactory: () -> Socket = { Socket() },
    private val threadFactory: (String, Runnable) -> Thread = { name, task -> Thread(task, name) },
    private val logger: (Int, String) -> Unit = { _, _ -> },
) : ProxyTransport {
    private val nextSessionId = AtomicInteger(1)

    override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
        DirectSocketProxyTransportSession(
            descriptor = ProxySessionDescriptor(nextSessionId.getAndIncrement(), request, System.currentTimeMillis()),
            resolver = resolver,
            socket = socketFactory(),
            threadFactory = threadFactory,
            logger = logger,
        )

    override fun openUdpAssociation(request: ProxyConnectRequest): ProxyUdpAssociation =
        DirectSocketProxyUdpAssociation(
            descriptor = ProxySessionDescriptor(nextSessionId.getAndIncrement(), request, System.currentTimeMillis()),
            resolver = resolver,
            socket = DatagramSocket(),
            logger = logger,
        )
}

interface GvisorTcpBackend {
    /**
     * Opens a native gVisor TCP session.
     *
     * [openId] is the Android-side descriptor ID. The native bridge uses it for cancellation and
     * diagnostics until it returns a positive native session ID.
     */
    fun open(openId: Int, remoteIpv4: IntArray, port: Int, timeoutMs: Int): Int

    fun cancelOpen(openId: Int): Int = -2

    fun read(sessionId: Int, maxLen: Int, timeoutMs: Int): ByteArray?

    fun write(sessionId: Int, bytes: ByteArray, timeoutMs: Int): Int

    fun close(sessionId: Int)

    fun stats(): IntArray = IntArray(GVISOR_STATS_COUNT)

    fun openDiagnostics(openId: Int): String = ""
}

private const val GVISOR_STATS_COUNT = 17

/** Formats the positional native stats array; keep this order aligned with gvisor_bridge_stats. */
fun formatGvisorStats(stats: IntArray): String {
    fun at(index: Int): Int = stats.getOrElse(index) { -1 }
    return "gvisorStats running=${at(0)} activeTcp=${at(1)} outboundQueued=${at(2)} openAttempts=${at(3)} " +
        "openOk=${at(4)} openFailed=${at(5)} outboundPackets=${at(6)} inboundPackets=${at(7)} " +
        "pendingOpens=${at(8)} openTimeouts=${at(9)} openImmediate=${at(10)} openResets=${at(11)} " +
        "openInternal=${at(12)} openCanceled=${at(13)} openSynOut=${at(14)} openSynAckIn=${at(15)} openRstIn=${at(16)}"
}

object VpnBridgeGvisorTcpBackend : GvisorTcpBackend {
    override fun open(openId: Int, remoteIpv4: IntArray, port: Int, timeoutMs: Int): Int =
        VpnBridge.nativeGvisorTcpOpenCancelable(openId, remoteIpv4, port, timeoutMs)

    override fun cancelOpen(openId: Int): Int =
        VpnBridge.nativeGvisorTcpCancelOpen(openId)

    override fun read(sessionId: Int, maxLen: Int, timeoutMs: Int): ByteArray? =
        VpnBridge.nativeGvisorTcpRead(sessionId, maxLen, timeoutMs)

    override fun write(sessionId: Int, bytes: ByteArray, timeoutMs: Int): Int =
        VpnBridge.nativeGvisorTcpWrite(sessionId, bytes, timeoutMs)

    override fun close(sessionId: Int) {
        VpnBridge.nativeGvisorTcpClose(sessionId)
    }

    override fun stats(): IntArray = VpnBridge.nativeGvisorStats()

    override fun openDiagnostics(openId: Int): String = VpnBridge.nativeGvisorOpenDiagnostics(openId)
}

/**
 * Proxy transport backed by the native gVisor TCP bridge.
 *
 * Hostname resolution may be provided by [tunneledResolver] so proxy-only mode does not leak DNS
 * to Android. The resulting IPv4 endpoint is opened through native gVisor; this class only adapts
 * listener sessions to the bridge API and owns Android-side cancellation.
 */
class GvisorProxyTransport(
    private val resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val tunneledResolver: ((String) -> String)? = null,
    private val requireTunneledDns: Boolean = false,
    private val backend: GvisorTcpBackend = VpnBridgeGvisorTcpBackend,
    private val threadFactory: (String, Runnable) -> Thread = { name, task -> Thread(task, name) },
    private val logger: (Int, String) -> Unit = { _, _ -> },
) : ProxyTransport {
    private val nextSessionId = AtomicInteger(1)

    override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession =
        GvisorProxyTransportSession(
            descriptor = ProxySessionDescriptor(nextSessionId.getAndIncrement(), request, System.currentTimeMillis()),
            resolver = resolver,
            tunneledResolver = tunneledResolver,
            requireTunneledDns = requireTunneledDns,
            backend = backend,
            threadFactory = threadFactory,
            logger = logger,
        )
}

private class GvisorProxyTransportSession(
    override val descriptor: ProxySessionDescriptor,
    private val resolver: (String) -> List<InetAddress>,
    private val tunneledResolver: ((String) -> String)?,
    private val requireTunneledDns: Boolean,
    private val backend: GvisorTcpBackend,
    private val threadFactory: (String, Runnable) -> Thread,
    private val logger: (Int, String) -> Unit,
) : ProxyTransportSession {
    private val connected = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val tearingDown = AtomicBoolean(false)
    private val nativeSessionId = AtomicInteger(0)
    private val openStarted = AtomicBoolean(false)

    /**
     * Resolves the target and opens the native gVisor session.
     *
     * Native returns <= 0 for classified open failures and > 0 for a live session. If the client
     * closes while native open is pending, [close] cancels by descriptor ID and this method reports
     * the result as [ProxyTransportFailureReason.clientAborted].
     */
    override fun awaitConnected(timeoutMs: Long) {
        if (closed.get()) throw IOException("Proxy transport session is already closed.")
        if (connected.get()) return
        val resolveStartMs = System.currentTimeMillis()
        val remoteAddress = resolveIpv4Target()
        val resolveElapsedMs = System.currentTimeMillis() - resolveStartMs
        val octets = remoteAddress.address.map { it.toInt() and 0xff }.toIntArray()
        val timeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        logger(
            Log.DEBUG,
            "proxy gvisor connect sid=${descriptor.sessionId} target=${descriptor.request.host}:${descriptor.request.port} " +
                "ip=${remoteAddress.hostAddress} timeoutMs=$timeout resolveMs=$resolveElapsedMs thread=${Thread.currentThread().name} " +
                formatGvisorStats(backend.stats()),
        )
        val openStartMs = System.currentTimeMillis()
        openStarted.set(true)
        val id = backend.open(descriptor.sessionId, octets, descriptor.request.port, timeout)
        val openElapsedMs = System.currentTimeMillis() - openStartMs
        if (id <= 0) {
            val reason = failureReasonForOpenCode(id)
            val diagnostics = backend.openDiagnostics(descriptor.sessionId).takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            if (reason != ProxyTransportFailureReason.clientAborted) {
                logger(
                    Log.WARN,
                    "proxy gvisor connect failed sid=${descriptor.sessionId} target=${descriptor.request.host}:${descriptor.request.port} " +
                        "ip=${remoteAddress.hostAddress} openMs=$openElapsedMs code=$id reason=$reason$diagnostics " + formatGvisorStats(backend.stats()),
                )
            }
            throw ProxyTransportException(
                reason,
                "gVisor TCP connect failed code=$id.${diagnostics.ifBlank { "" }}",
            )
        }
        if (closed.get()) {
            backend.close(id)
            throw ProxyTransportException(
                ProxyTransportFailureReason.clientAborted,
                "gVisor TCP connect canceled because the client closed before establishment.",
            )
        }
        nativeSessionId.set(id)
        connected.set(true)
        val diagnostics = backend.openDiagnostics(descriptor.sessionId).takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        logger(
            Log.DEBUG,
            "proxy gvisor connect ok sid=${descriptor.sessionId} native=$id target=${descriptor.request.host}:${descriptor.request.port} " +
                "ip=${remoteAddress.hostAddress} openMs=$openElapsedMs$diagnostics " + formatGvisorStats(backend.stats()),
        )
    }

    override fun pumpBidirectional(client: ProxyClientConnection) {
        if (!connected.get()) throw IOException("TCP session is not connected.")
        val failure = AtomicReference<IOException?>(null)
        /*
         * The native bridge exposes blocking read/write operations, so each direction owns a
         * daemon thread. Timeouts are polling points for shutdown, not user-visible failures.
         */
        val remoteToClient =
            threadFactory(
                "proxy-gvisor-remote-${descriptor.sessionId}",
                Runnable {
                    try {
                        while (!tearingDown.get()) {
                            val chunk = backend.read(nativeSessionId.get(), RELAY_BUFFER_BYTES, READ_TIMEOUT_MS)
                            if (chunk == null) continue
                            if (chunk.isEmpty()) {
                                logGvisorClose(GvisorTcpFailureCategory.cleanEof, "remote->client")
                                break
                            }
                            client.output.write(chunk)
                            client.output.flush()
                        }
                    } catch (e: IOException) {
                        if (!tearingDown.get()) {
                            val category = gvisorTcpFailureCategory(e)
                            logGvisorFailure(category, "remote->client", e)
                            if (category != GvisorTcpFailureCategory.connectionReset &&
                                category != GvisorTcpFailureCategory.sessionClosedLocally
                            ) {
                                failure.compareAndSet(null, e)
                            }
                        }
                    } finally {
                        shutdownSocketOutput(client.socket)
                    }
                },
            )
        remoteToClient.start()
        val buffer = ByteArray(RELAY_BUFFER_BYTES)
        try {
            while (!tearingDown.get()) {
                val read = client.input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                var offset = 0
                while (offset < read) {
                    val chunk = buffer.copyOfRange(offset, read)
                    val written = backend.write(nativeSessionId.get(), chunk, WRITE_TIMEOUT_MS)
                    if (written <= 0) throw gvisorWriteFailure(written)
                    offset += written
                }
            }
        } catch (e: IOException) {
            if (!tearingDown.get()) {
                val category = gvisorTcpFailureCategory(e, writePath = true)
                logGvisorFailure(category, "client->remote", e)
                if (category != GvisorTcpFailureCategory.connectionReset &&
                    category != GvisorTcpFailureCategory.sessionClosedLocally
                ) {
                    failure.compareAndSet(null, e)
                }
            }
        } finally {
            tearingDown.set(true)
            shutdownSocketOutput(client.socket)
        }
        try {
            remoteToClient.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while relaying proxy traffic.")
        } finally {
            close()
        }
        failure.get()?.let { throw it }
    }

    override fun readRemoteBytes(maxLen: Int, timeoutMs: Int): ByteArray? {
        if (!connected.get()) throw IOException("TCP session is not connected.")
        if (tearingDown.get()) return ByteArray(0)
        val chunk = backend.read(nativeSessionId.get(), maxLen, timeoutMs)
        if (chunk == null) return null
        if (chunk.isEmpty()) {
            tearingDown.set(true)
        }
        return chunk
    }

    override fun writeClientBytes(bytes: ByteArray, timeoutMs: Int): Int {
        if (!connected.get()) throw IOException("TCP session is not connected.")
        if (bytes.isEmpty()) return 0
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, bytes.size)
            val written = backend.write(nativeSessionId.get(), chunk, timeoutMs)
            if (written <= 0) throw gvisorWriteFailure(written)
            offset += written
        }
        return offset
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        tearingDown.set(true)
        val id = nativeSessionId.getAndSet(0)
        if (id > 0) {
            backend.close(id)
        } else if (openStarted.get() && !connected.get()) {
            backend.cancelOpen(descriptor.sessionId)
        }
    }

    private fun resolveIpv4Target(): InetAddress {
        descriptor.request.host.toIpv4LiteralOrNull()?.let { literal ->
            return InetAddress.getByName(literal)
        }
        val tunneledResolve = tunneledResolver
        if (tunneledResolve != null) {
            try {
                val resolvedIpv4 = tunneledResolve(descriptor.request.host)
                logger(
                    Log.DEBUG,
                    "proxy gvisor resolve sid=${descriptor.sessionId} host=${descriptor.request.host} mode=tunneled ip=$resolvedIpv4",
                )
                return InetAddress.getByName(resolvedIpv4)
            } catch (e: IOException) {
                throw ProxyTransportException(
                    ProxyTransportFailureReason.dnsFailed,
                    e.message ?: "Tunneled DNS lookup failed.",
                    e,
                )
            } catch (e: RuntimeException) {
                throw ProxyTransportException(
                    ProxyTransportFailureReason.dnsFailed,
                    e.message ?: "Tunneled DNS lookup failed.",
                    e,
                )
            }
        }
        if (requireTunneledDns) {
            throw ProxyTransportException(
                ProxyTransportFailureReason.dnsFailed,
                "Proxy-only mode requires tunneled DNS for hostname targets.",
            )
        }
        val resolved =
            resolver(descriptor.request.host).firstOrNull { it is Inet4Address }
                ?: throw ProxyTransportException(ProxyTransportFailureReason.networkUnreachable, "IPv6 destinations are not supported in proxy mode.")
        logger(
            Log.DEBUG,
            "proxy gvisor resolve sid=${descriptor.sessionId} host=${descriptor.request.host} ip=${resolved.hostAddress}",
        )
        return resolved
    }

    private fun shutdownSocketOutput(socket: Socket) {
        try {
            socket.shutdownOutput()
        } catch (_: IOException) {
        }
    }

    private fun failureReasonForOpenCode(code: Int): ProxyTransportFailureReason =
        when (code) {
            -4 -> ProxyTransportFailureReason.upstreamTimeout
            -7 -> ProxyTransportFailureReason.clientAborted
            -6 -> ProxyTransportFailureReason.networkUnreachable
            -2 -> ProxyTransportFailureReason.localServiceUnavailable
            -1 -> ProxyTransportFailureReason.protocolError
            else -> ProxyTransportFailureReason.upstreamConnectFailed
        }

    private fun gvisorWriteFailure(code: Int): IOException =
        when (code) {
            -4 -> IOException("gVisor TCP write failed: write timeout.")
            -5 -> IOException("gVisor TCP write failed: connection reset.")
            -2 -> IOException("gVisor TCP write failed: session closed locally.")
            else -> IOException("gVisor TCP write failed: internal gVisor error code=$code.")
        }

    private fun logGvisorClose(category: GvisorTcpFailureCategory, direction: String) {
        logger(
            Log.DEBUG,
            "proxy gvisor tcp close sid=${descriptor.sessionId} native=${nativeSessionId.get()} category=$category " +
                "direction=$direction target=${descriptor.request.host}:${descriptor.request.port} ageMs=${System.currentTimeMillis() - descriptor.openedAtMs}",
        )
    }

    private fun logGvisorFailure(category: GvisorTcpFailureCategory, direction: String, error: IOException) {
        val level =
            when (category) {
                GvisorTcpFailureCategory.connectionReset,
                GvisorTcpFailureCategory.sessionClosedLocally,
                -> Log.DEBUG
                else -> Log.WARN
            }
        logger(
            level,
            "proxy gvisor tcp close sid=${descriptor.sessionId} native=${nativeSessionId.get()} category=$category " +
                "direction=$direction target=${descriptor.request.host}:${descriptor.request.port} " +
                "ageMs=${System.currentTimeMillis() - descriptor.openedAtMs} error=${error.message ?: error.javaClass.simpleName}",
        )
    }

    private companion object {
        private const val RELAY_BUFFER_BYTES = 8192
        private const val READ_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 30000
    }
}

private class DirectSocketProxyUdpAssociation(
    override val descriptor: ProxySessionDescriptor,
    private val resolver: (String) -> List<InetAddress>,
    private val socket: DatagramSocket,
    private val logger: (Int, String) -> Unit,
) : ProxyUdpAssociation {
    private val closed = AtomicBoolean(false)
    private val remotes = ConcurrentHashMap.newKeySet<InetSocketAddress>()

    override fun send(datagram: ProxyUdpDatagram) {
        if (closed.get()) throw IOException("UDP association is already closed.")
        val remoteAddress = resolveIpv4Target(datagram.host)
        val remote = InetSocketAddress(remoteAddress, datagram.port)
        val addedRemote = remotes.add(remote)
        val packet = DatagramPacket(datagram.payload, datagram.payload.size, remoteAddress, datagram.port)
        logger(
            Log.DEBUG,
            "proxy direct udp out sid=${descriptor.sessionId} target=${datagram.host}:${datagram.port} ip=${remoteAddress.hostAddress} bytes=${datagram.payload.size}",
        )
        try {
            socket.send(packet)
        } catch (e: IOException) {
            if (addedRemote) remotes.remove(remote)
            throw e
        }
    }

    override fun receive(timeoutMs: Long): ProxyUdpDatagram? {
        if (closed.get()) return null
        val deadlineNs = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * NANOS_PER_MILLI
        val buffer = ByteArray(MAX_UDP_PACKET_BYTES)
        while (!closed.get()) {
            val remainingMs = ((deadlineNs - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(1L)
            socket.soTimeout = remainingMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                val remote = InetSocketAddress(packet.address, packet.port)
                if (!remotes.contains(remote)) {
                    logger(
                        Log.DEBUG,
                        "proxy direct udp drop sid=${descriptor.sessionId} reason=source-mismatch source=${packet.address.hostAddress}:${packet.port}",
                    )
                    if (System.nanoTime() >= deadlineNs) return null
                    continue
                }
                return ProxyUdpDatagram(
                    host = packet.address.hostAddress ?: packet.address.hostName,
                    port = packet.port,
                    payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                )
            } catch (_: java.net.SocketTimeoutException) {
                return null
            }
        }
        return null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket.close()
    }

    private fun resolveIpv4Target(host: String): InetAddress {
        host.toIpv4LiteralOrNull()?.let { literal ->
            return InetAddress.getByName(literal)
        }
        val resolved =
            resolver(host).firstOrNull { it is Inet4Address }
                ?: throw ProxyTransportException(ProxyTransportFailureReason.networkUnreachable, "IPv6 destinations are not supported in proxy mode.")
        logger(
            Log.DEBUG,
            "proxy direct udp resolve sid=${descriptor.sessionId} host=$host ip=${resolved.hostAddress}",
        )
        return resolved
    }

    private companion object {
        private const val MAX_UDP_PACKET_BYTES = 65535
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

private class DirectSocketProxyTransportSession(
    override val descriptor: ProxySessionDescriptor,
    private val resolver: (String) -> List<InetAddress>,
    private val socket: Socket,
    private val threadFactory: (String, Runnable) -> Thread,
    private val logger: (Int, String) -> Unit,
) : ProxyTransportSession {
    private val connected = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val tearingDown = AtomicBoolean(false)

    override fun awaitConnected(timeoutMs: Long) {
        if (closed.get()) {
            throw IOException("Proxy transport session is already closed.")
        }
        if (connected.get()) return
        val remoteAddress = resolveIpv4Target()
        val timeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        logger(
            Log.DEBUG,
            "proxy direct connect sid=${descriptor.sessionId} target=${descriptor.request.host}:${descriptor.request.port} ip=${remoteAddress.hostAddress} timeoutMs=$timeout",
        )
        try {
            socket.connect(InetSocketAddress(remoteAddress, descriptor.request.port), timeout)
            socket.tcpNoDelay = true
            connected.set(true)
        } catch (e: SocketTimeoutException) {
            closeQuietly(socket)
            throw ProxyTransportException(ProxyTransportFailureReason.upstreamTimeout, e.message ?: "TCP connect timed out.", e)
        } catch (e: IOException) {
            closeQuietly(socket)
            throw ProxyTransportException(ProxyTransportFailureReason.upstreamConnectFailed, e.message ?: "TCP connect failed.", e)
        }
    }

    override fun pumpBidirectional(client: ProxyClientConnection) {
        if (!connected.get()) {
            throw IOException("TCP session is not connected.")
        }
        val failure = AtomicReference<IOException?>(null)
        val clientInputEnded = AtomicBoolean(false)
        val remoteInputEnded = AtomicBoolean(false)
        val remoteToClient =
            threadFactory(
                "proxy-direct-remote-${descriptor.sessionId}",
                Runnable {
                    relay(
                        input = socket.getInputStream(),
                        output = client.output,
                        failure = failure,
                        inputEnded = remoteInputEnded,
                        onInputClosed = { shutdownSocketOutput(client.socket) },
                        clientSocket = client.socket,
                    )
                },
            )
        remoteToClient.start()
        relay(
            input = client.input,
            output = socket.getOutputStream(),
            failure = failure,
            inputEnded = clientInputEnded,
            onInputClosed = { shutdownSocketOutput(socket) },
            clientSocket = client.socket,
        )
        try {
            remoteToClient.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while relaying proxy traffic.")
        }
        failure.get()?.let { throw it }
        if (clientInputEnded.get() && remoteInputEnded.get()) {
            tearDown(client.socket)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        tearingDown.set(true)
        closeQuietly(socket)
    }

    private fun resolveIpv4Target(): InetAddress {
        descriptor.request.host.toIpv4LiteralOrNull()?.let { literal ->
            return InetAddress.getByName(literal)
        }
        val resolved =
            resolver(descriptor.request.host).firstOrNull { it is Inet4Address }
                ?: throw ProxyTransportException(ProxyTransportFailureReason.networkUnreachable, "IPv6 destinations are not supported in proxy mode.")
        logger(
            Log.DEBUG,
            "proxy direct resolve sid=${descriptor.sessionId} host=${descriptor.request.host} ip=${resolved.hostAddress}",
        )
        return resolved
    }

    private fun relay(
        input: InputStream,
        output: OutputStream,
        failure: AtomicReference<IOException?>,
        inputEnded: AtomicBoolean,
        onInputClosed: () -> Unit,
        clientSocket: Socket,
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
            if (failure.get() != null) {
                tearDown(clientSocket)
            }
        }
    }

    private fun shutdownSocketOutput(socket: Socket) {
        try {
            socket.shutdownOutput()
        } catch (_: IOException) {
        }
    }

    private fun tearDown(clientSocket: Socket) {
        if (!tearingDown.compareAndSet(false, true)) return
        closeQuietly(clientSocket)
        closeQuietly(socket)
    }

    private companion object {
        private const val RELAY_BUFFER_BYTES = 8192

        private fun closeQuietly(socket: Socket) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }
}
