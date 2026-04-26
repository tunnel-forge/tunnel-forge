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
