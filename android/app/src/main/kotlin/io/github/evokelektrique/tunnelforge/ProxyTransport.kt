package io.github.evokelektrique.tunnelforge

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

interface ProxyTransportSession : Closeable {
    val descriptor: ProxySessionDescriptor

    @Throws(IOException::class)
    fun awaitConnected(timeoutMs: Long)

    @Throws(IOException::class)
    fun pumpBidirectional(client: ProxyClientConnection)
}

interface ProxyTransport {
    @Throws(IOException::class)
    fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession
}

class StubProxyTransport(
    private val reason: String = "Tunnel forwarding is not attached yet.",
) : ProxyTransport {
    override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
        throw IOException(reason)
    }
}

class BridgeProxyTransport(
    private val stack: UserspaceTunnelStack,
) : ProxyTransport {
    override fun openTcpSession(request: ProxyConnectRequest): ProxyTransportSession {
        val session = stack.openTcpSession(request)
        logDebug("proxy transport open sid=${session.descriptor.sessionId} target=${request.host}:${request.port}")
        return object : ProxyTransportSession {
            override val descriptor: ProxySessionDescriptor = session.descriptor

            override fun awaitConnected(timeoutMs: Long) {
                session.awaitEstablished(timeoutMs)
            }

            override fun pumpBidirectional(client: ProxyClientConnection) {
                session.pumpBidirectional(client)
            }

            override fun close() {
                logDebug("proxy transport close sid=${descriptor.sessionId}")
                session.close()
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
        } catch (e: IOException) {
            closeQuietly(socket)
            throw IOException(e.message ?: "TCP connect failed.", e)
        }
    }

    override fun pumpBidirectional(client: ProxyClientConnection) {
        if (!connected.get()) {
            throw IOException("TCP session is not connected.")
        }
        val failure = AtomicReference<IOException?>(null)
        val remoteToClient =
            threadFactory(
                "proxy-direct-remote-${descriptor.sessionId}",
                Runnable {
                    relay(
                        input = socket.getInputStream(),
                        output = client.output,
                        failure = failure,
                        clientSocket = client.socket,
                    )
                },
            )
        remoteToClient.start()
        relay(
            input = client.input,
            output = socket.getOutputStream(),
            failure = failure,
            clientSocket = client.socket,
        )
        try {
            remoteToClient.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while relaying proxy traffic.")
        }
        failure.get()?.let { throw it }
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
                ?: throw IOException("IPv6 destinations are not supported in proxy mode.")
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
        } catch (e: IOException) {
            if (!tearingDown.get()) {
                failure.compareAndSet(null, e)
            }
        } finally {
            tearDown(clientSocket)
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
