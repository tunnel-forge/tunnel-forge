package io.github.evokelektrique.tunnelforge

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

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
        logInfo("proxy transport open sid=${session.descriptor.sessionId} target=${request.host}:${request.port}")
        return object : ProxyTransportSession {
            override val descriptor: ProxySessionDescriptor = session.descriptor

            override fun awaitConnected(timeoutMs: Long) {
                session.awaitEstablished(timeoutMs)
            }

            override fun pumpBidirectional(client: ProxyClientConnection) {
                session.pumpBidirectional(client)
            }

            override fun close() {
                logInfo("proxy transport close sid=${descriptor.sessionId}")
                session.close()
            }
        }
    }

    private companion object {
        private const val TAG = "BridgeProxyTransport"

        private fun logInfo(message: String) {
            try {
                Log.i(TAG, message)
            } catch (_: RuntimeException) {
                // Unit tests run without Android logging runtime.
            }
        }
    }
}
