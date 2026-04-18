package com.example.tunnel_forge

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

interface UserspaceTunnelStack {
    fun waitUntilReady(timeoutMs: Long = 4000, pollIntervalMs: Long = 100): Boolean

    fun openTcpSession(request: ProxyConnectRequest): UserspaceTunnelSession

    fun stop()
}

interface UserspaceTunnelSession : AutoCloseable {
    val descriptor: ProxySessionDescriptor

    @Throws(IOException::class)
    fun awaitEstablished(timeoutMs: Long)

    @Throws(IOException::class)
    fun pumpBidirectional(client: ProxyClientConnection)
}

enum class UserspaceSessionState {
    opening,
    active,
    established,
    failed,
    closed,
}

data class UserspaceSessionSnapshot(
    val descriptor: ProxySessionDescriptor,
    val state: UserspaceSessionState,
    val lastEvent: String,
)

class BridgeUserspaceTunnelStack(
    private val bridge: ProxyPacketBridge,
    private val clientIpv4: String = DEFAULT_CLIENT_IPV4,
    private val dnsServers: List<String> = listOf(DEFAULT_DNS_SERVER),
    private val linkMtu: Int = DEFAULT_LINK_MTU,
    private val logger: (Int, String) -> Unit = { level, message ->
        Log.println(level, TAG, message)
    },
    private val failureReason: String = DEFAULT_FAILURE_REASON,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
) : UserspaceTunnelStack {
    private val nextSessionId = AtomicInteger(1)
    private val nextLocalPort = AtomicInteger(30000)
    private val sessions = ConcurrentHashMap<Int, UserspaceSessionSnapshot>()
    private val tcpRuntimeBySessionId = ConcurrentHashMap<Int, TcpSessionRuntime>()
    private val clientAttachments = ConcurrentHashMap<Int, ClientAttachment>()
    private val dnsResolver =
        TunneledDnsResolver(
            bridge = bridge,
            clientIpv4 = clientIpv4,
            dnsServers = dnsServers,
            logger = logger,
        )
    private val maxTcpPayloadBytes = (linkMtu - IPV4_HEADER_LEN - TCP_HEADER_LEN).coerceAtLeast(1)
    private val advertisedMss = maxTcpPayloadBytes.coerceAtMost(MAX_TCP_OPTION_VALUE)

    @Volatile
    private var inboundPump: Thread? = null

    @Volatile
    private var running = false

    override fun waitUntilReady(timeoutMs: Long, pollIntervalMs: Long): Boolean {
        logger(Log.INFO, "userspace stack waitUntilReady timeoutMs=$timeoutMs pollIntervalMs=$pollIntervalMs")
        if (!bridge.waitUntilActive(timeoutMs = timeoutMs, pollIntervalMs = pollIntervalMs)) {
            logger(Log.WARN, "userspace stack bridge did not become active within timeout")
            return false
        }
        running = true
        if (inboundPump == null) {
            inboundPump =
                bridge.startInboundPump(
                    threadName = "userspace-stack-inbound",
                    onPacket = ::handleInboundPacket,
                    onError = { error ->
                        logger(Log.WARN, "userspace stack inbound error=${error.javaClass.simpleName}:${error.message}")
                    },
                )
            logger(
                Log.INFO,
                "userspace stack inbound pump started clientIpv4=$clientIpv4 dns=${dnsServers.joinToString(",")} mtu=$linkMtu mss=$advertisedMss",
            )
        }
        return true
    }

    override fun openTcpSession(request: ProxyConnectRequest): UserspaceTunnelSession {
        if (!running || !bridge.isRunning()) {
            throw IOException("Proxy packet bridge is not active.")
        }
        val descriptor =
            ProxySessionDescriptor(
                sessionId = nextSessionId.getAndIncrement(),
                request = request,
                openedAtMs = System.currentTimeMillis(),
            )
        logger(Log.INFO, "proxy session open sid=${descriptor.sessionId} target=${request.host}:${request.port}")
        sessions[descriptor.sessionId] =
            UserspaceSessionSnapshot(
                descriptor = descriptor,
                state = UserspaceSessionState.opening,
                lastEvent = "Session allocated; waiting for remote TCP endpoint",
            )
        try {
            val remoteIp = resolveRemoteIpv4(descriptor)
            queueSyntheticSyn(descriptor, remoteIp)
        } catch (e: IOException) {
            logger(Log.WARN, "proxy session open failed sid=${descriptor.sessionId} target=${request.host}:${request.port} error=${e.message}")
            sessions.remove(descriptor.sessionId)
            throw e
        }
        return BridgeUserspaceTunnelSession(
            descriptor = descriptor,
            onPumpClientTraffic = { client -> pumpSessionTraffic(descriptor.sessionId, client) },
            onAwaitEstablished = { timeoutMs -> awaitSessionEstablished(descriptor.sessionId, timeoutMs) },
            onStateChanged = { state, event -> updateSessionState(descriptor.sessionId, state, event) },
            onClose = {
                tcpRuntimeBySessionId.remove(descriptor.sessionId)?.signalRuntimeFailure("Session closed")
                clientAttachments.remove(descriptor.sessionId)?.close()
                updateSessionState(descriptor.sessionId, UserspaceSessionState.closed, "Session closed")
                sessions.remove(descriptor.sessionId)
            },
            failureReason = failureReason,
        )
    }

    fun activeSessions(): List<ProxySessionDescriptor> = sessions.values.sortedBy { it.descriptor.sessionId }.map { it.descriptor }

    fun sessionSnapshots(): List<UserspaceSessionSnapshot> = sessions.values.sortedBy { it.descriptor.sessionId }

    internal fun processInboundPacketForTesting(packet: ByteArray) = handleInboundPacket(packet)

    override fun stop() {
        running = false
        logger(Log.INFO, "userspace stack stop sessions=${sessions.size} runtimes=${tcpRuntimeBySessionId.size} attachments=${clientAttachments.size}")
        tcpRuntimeBySessionId.values.forEach { it.signalRuntimeFailure("Proxy packet bridge stopped.") }
        sessions.clear()
        tcpRuntimeBySessionId.clear()
        clientAttachments.values.forEach { it.close() }
        clientAttachments.clear()
        bridge.stop()
        inboundPump?.interrupt()
        inboundPump = null
    }

    private fun handleInboundPacket(packet: ByteArray) {
        val ipv4 = IpPacketParser.parseIpv4(packet)
        if (ipv4 == null) {
            logger(Log.DEBUG, "Dropped non-IPv4 bridge packet len=${packet.size}")
            return
        }
        when (ipv4.protocol) {
            IpPacketParser.IP_PROTOCOL_TCP -> handleInboundTcpPacket(packet, ipv4)
            IpPacketParser.IP_PROTOCOL_UDP -> handleInboundUdpPacket(packet, ipv4)
            IpPacketParser.IP_PROTOCOL_ICMP -> handleInboundIcmpPacket(packet, ipv4)
            else ->
                logger(
                    Log.DEBUG,
                    "Dropped IPv4 protocol=${ipv4.protocol} ${ipv4.sourceIp} -> ${ipv4.destinationIp} len=${ipv4.totalLength}; TCP forwarding only is planned",
                )
        }
    }

    private fun handleInboundIcmpPacket(packet: ByteArray, ipv4: ParsedIpv4Packet) {
        val icmp = IpPacketParser.parseIcmp(packet, ipv4)
        if (icmp == null) {
            logger(Log.DEBUG, "Dropped malformed ICMP packet ${ipv4.sourceIp} -> ${ipv4.destinationIp} len=${ipv4.totalLength}")
            return
        }
        if (icmp.type == ICMP_TYPE_DESTINATION_UNREACHABLE &&
            icmp.code == ICMP_CODE_FRAGMENTATION_NEEDED &&
            icmp.quotedIpv4 != null &&
            icmp.quotedTcp != null
        ) {
            val quotedIpv4 = icmp.quotedIpv4
            val quotedTcp = icmp.quotedTcp
            val runtime = tcpRuntimeBySessionId.values.firstOrNull {
                it.localPort == quotedTcp.sourcePort &&
                    it.remotePort == quotedTcp.destinationPort &&
                    it.remoteIp == quotedIpv4.destinationIp
            }
            if (runtime != null) {
                val pmtu = icmp.nextHopMtu ?: 0
                logger(
                    Log.WARN,
                    "proxy tcp pmtu sid=${runtime.sessionId} remote=${runtime.remoteIp}:${runtime.remotePort} nextHopMtu=$pmtu",
                )
                updateSessionState(
                    runtime.sessionId,
                    sessions[runtime.sessionId]?.state ?: UserspaceSessionState.opening,
                    "Observed ICMP fragmentation-needed nextHopMtu=$pmtu",
                )
                return
            }
        }
        logger(
            Log.DEBUG,
            "Dropped ICMP type=${icmp.type} code=${icmp.code} ${ipv4.sourceIp} -> ${ipv4.destinationIp} len=${ipv4.totalLength}",
        )
    }

    private fun handleInboundUdpPacket(packet: ByteArray, ipv4: ParsedIpv4Packet) {
        val udp = IpPacketParser.parseUdp(packet, ipv4)
        if (udp == null) {
            logger(Log.DEBUG, "Dropped malformed UDP datagram ${ipv4.sourceIp} -> ${ipv4.destinationIp} len=${ipv4.totalLength}")
            return
        }
        if (!dnsResolver.handleInboundPacket(ipv4, udp, packet)) {
            logger(
                Log.DEBUG,
                "Dropped IPv4 protocol=${ipv4.protocol} ${ipv4.sourceIp} -> ${ipv4.destinationIp} len=${ipv4.totalLength}; TCP forwarding only is planned",
            )
        }
    }

    private fun handleInboundTcpPacket(packet: ByteArray, ipv4: ParsedIpv4Packet) {
        val tcp = IpPacketParser.parseTcp(packet, ipv4)
        if (tcp == null) {
            logger(Log.DEBUG, "Dropped malformed TCP segment ${ipv4.sourceIp} -> ${ipv4.destinationIp} len=${ipv4.totalLength}")
            return
        }
        logger(
            Log.DEBUG,
            "Observed inbound TCP packet ${ipv4.sourceIp}:${tcp.sourcePort} -> ${ipv4.destinationIp}:${tcp.destinationPort} flags=0x${tcp.flags.toString(16)} seq=${tcp.sequenceNumber} payload=${tcp.payloadLength}",
        )
        val match = tcpRuntimeBySessionId.values.firstOrNull {
            it.localPort == tcp.destinationPort &&
                it.remotePort == tcp.sourcePort &&
                (it.remoteIp == ipv4.sourceIp || sessions[it.sessionId]?.state == UserspaceSessionState.opening)
        }
        if (match != null) {
            handleInboundTcpMatch(match, packet, ipv4, tcp)
        } else {
            logger(
                Log.DEBUG,
                "Dropped inbound TCP without session match ${ipv4.sourceIp}:${tcp.sourcePort} -> ${ipv4.destinationIp}:${tcp.destinationPort} flags=0x${tcp.flags.toString(16)}",
            )
        }
    }

    private fun handleInboundTcpMatch(
        runtime: TcpSessionRuntime,
        packet: ByteArray,
        ipv4: ParsedIpv4Packet,
        tcp: ParsedTcpSegment,
    ) {
        if ((tcp.flags and TcpPacketBuilder.TCP_FLAG_RST) != 0) {
            clientAttachments.remove(runtime.sessionId)?.close()
            val message = "Observed TCP RST from ${ipv4.sourceIp}:${tcp.sourcePort}"
            runtime.signalRuntimeFailure(message)
            logger(Log.INFO, "proxy tcp fail sid=${runtime.sessionId} reason=rst-in")
            updateSessionState(runtime.sessionId, UserspaceSessionState.failed, message)
            return
        }

        if ((tcp.flags and TcpPacketBuilder.TCP_FLAG_SYN) != 0 &&
            (tcp.flags and TcpPacketBuilder.TCP_FLAG_ACK) != 0 &&
            runtime.isConnectPending()
        ) {
            val ackPacket =
                runtime.withLock {
                    TcpPacketBuilder.buildIpv4TcpPacket(
                        sourceIp = clientIpv4,
                        destinationIp = ipv4.sourceIp,
                        sourcePort = localPort,
                        destinationPort = remotePort,
                        sequenceNumber = initialSequenceNumber + 1,
                        acknowledgementNumber = tcp.sequenceNumber + 1,
                        flags = TcpPacketBuilder.TCP_FLAG_ACK,
                    )
                }
            if (bridge.queueOutboundPacket(ackPacket)) {
                runtime.markEstablished(ipv4.sourceIp, tcp.sequenceNumber + 1)
                logger(Log.INFO, "proxy tcp established sid=${runtime.sessionId}")
                updateSessionState(
                    runtime.sessionId,
                    UserspaceSessionState.established,
                    "Queued TCP ACK for SYN/ACK from ${ipv4.sourceIp}:${tcp.sourcePort}",
                )
            } else {
                val message = "Observed SYN/ACK but failed to queue final TCP ACK"
                runtime.signalConnectFailure(message)
                updateSessionState(runtime.sessionId, UserspaceSessionState.failed, message)
            }
            return
        }

        val finConsumesByte = if ((tcp.flags and TcpPacketBuilder.TCP_FLAG_FIN) != 0) 1 else 0
        if (tcp.payloadLength > 0 || finConsumesByte != 0) {
            val expectedSequence = runtime.withLock { remoteSequenceNumber }
            if (tcp.sequenceNumber < expectedSequence) {
                queuePureAck(runtime, expectedSequence)
                logger(Log.INFO, "proxy tcp reack sid=${runtime.sessionId} reason=duplicate ack=$expectedSequence")
                updateSessionState(
                    runtime.sessionId,
                    UserspaceSessionState.established,
                    "Ignored duplicate inbound TCP payload seq=${tcp.sequenceNumber} expected=$expectedSequence",
                )
                return
            }
            if (tcp.sequenceNumber > expectedSequence) {
                queuePureAck(runtime, expectedSequence)
                logger(Log.INFO, "proxy tcp reack sid=${runtime.sessionId} reason=out-of-order ack=$expectedSequence")
                updateSessionState(
                    runtime.sessionId,
                    UserspaceSessionState.established,
                    "Ignored out-of-order inbound TCP payload seq=${tcp.sequenceNumber} expected=$expectedSequence",
                )
                return
            }
            if (tcp.payloadLength > 0) {
                val payload = packet.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
                val attachment = clientAttachments[runtime.sessionId]
                if (attachment == null) {
                    val message = "Inbound TCP payload arrived without attached client socket"
                    runtime.signalRuntimeFailure(message)
                    logger(Log.WARN, "proxy tcp fail sid=${runtime.sessionId} reason=no-client-attachment")
                    updateSessionState(runtime.sessionId, UserspaceSessionState.failed, message)
                    return
                }
                try {
                    attachment.write(payload)
                } catch (e: IOException) {
                    clientAttachments.remove(runtime.sessionId)?.close()
                    val message = e.message ?: "Failed writing inbound payload to client"
                    runtime.signalRuntimeFailure(message)
                    logger(Log.WARN, "proxy tcp fail sid=${runtime.sessionId} reason=client-write error=$message")
                    updateSessionState(runtime.sessionId, UserspaceSessionState.failed, message)
                    return
                }
                logger(Log.INFO, "proxy tcp in sid=${runtime.sessionId} bytes=${tcp.payloadLength}")
                updateSessionState(
                    runtime.sessionId,
                    UserspaceSessionState.established,
                    "Delivered inbound TCP payload len=${tcp.payloadLength} to local client",
                )
            }
            val nextExpected =
                runtime.withLock {
                    val consumedSequence = tcp.sequenceNumber + tcp.payloadLength + finConsumesByte
                    remoteSequenceNumber = maxOf(remoteSequenceNumber, consumedSequence)
                    remoteSequenceNumber
                }
            queuePureAck(runtime, nextExpected)
        } else {
            updateSessionState(
                runtime.sessionId,
                UserspaceSessionState.established,
                "Observed inbound TCP for session ${runtime.sessionId} from ${ipv4.sourceIp}:${tcp.sourcePort}",
            )
        }

        if (finConsumesByte != 0) {
            clientAttachments[runtime.sessionId]?.shutdownOutput()
            runtime.markRemoteClosed()
            logger(Log.INFO, "proxy tcp close sid=${runtime.sessionId} reason=fin-in")
            updateSessionState(
                runtime.sessionId,
                UserspaceSessionState.established,
                "Observed TCP FIN from ${ipv4.sourceIp}:${tcp.sourcePort}; awaiting local close",
            )
        }
    }

    private fun resolveRemoteIpv4(descriptor: ProxySessionDescriptor): String {
        descriptor.request.host.toIpv4LiteralOrNull()?.let {
            logger(Log.INFO, "proxy resolve sid=${descriptor.sessionId} host=${descriptor.request.host} mode=literal ip=$it")
            return it
        }
        if (descriptor.request.host.contains(':')) {
            throw IOException("IPv6 destinations are not supported in proxy mode.")
        }
        updateSessionState(
            descriptor.sessionId,
            UserspaceSessionState.opening,
            "Resolving ${descriptor.request.host} through tunneled DNS",
        )
        val resolved = dnsResolver.resolve(descriptor.request.host)
        logger(Log.INFO, "proxy resolve sid=${descriptor.sessionId} host=${descriptor.request.host} mode=dns ip=$resolved")
        return resolved
    }

    private fun queueSyntheticSyn(descriptor: ProxySessionDescriptor, remoteIp: String) {
        val localPort = nextLocalPort.getAndIncrement().coerceIn(1024, 65535)
        val sequenceNumber = descriptor.sessionId.toLong() shl 20
        val runtime =
            TcpSessionRuntime(
                sessionId = descriptor.sessionId,
                localPort = localPort,
                remoteIp = remoteIp,
                remotePort = descriptor.request.port,
                initialSequenceNumber = sequenceNumber,
            )
        tcpRuntimeBySessionId[descriptor.sessionId] = runtime
        updateSessionState(
            descriptor.sessionId,
            UserspaceSessionState.opening,
            "Queued synthetic TCP SYN $clientIpv4:$localPort -> $remoteIp:${descriptor.request.port}",
        )

        val synPacket =
            TcpPacketBuilder.buildIpv4TcpPacket(
                sourceIp = clientIpv4,
                destinationIp = remoteIp,
                sourcePort = localPort,
                destinationPort = descriptor.request.port,
                sequenceNumber = sequenceNumber,
                flags = TcpPacketBuilder.TCP_FLAG_SYN,
                mss = advertisedMss,
            )
        if (!bridge.queueOutboundPacket(synPacket)) {
            tcpRuntimeBySessionId.remove(descriptor.sessionId, runtime)
            runtime.signalConnectFailure("Failed to queue TCP SYN into proxy packet bridge.")
            logger(Log.WARN, "proxy tcp fail sid=${descriptor.sessionId} reason=syn-queue-failed")
            updateSessionState(descriptor.sessionId, UserspaceSessionState.failed, "Failed to queue TCP SYN into proxy packet bridge")
            throw IOException("Failed to queue outbound TCP SYN into proxy packet bridge.")
        }
        logger(Log.INFO, "proxy tcp syn sid=${descriptor.sessionId} sport=$localPort daddr=$remoteIp:${descriptor.request.port} mss=$advertisedMss")
    }

    private fun updateSessionState(sessionId: Int, state: UserspaceSessionState, event: String) {
        sessions.computeIfPresent(sessionId) { _, current ->
            current.copy(state = state, lastEvent = event)
        }
    }

    private fun pumpSessionTraffic(sessionId: Int, client: ProxyClientConnection) {
        clientAttachments[sessionId] = ClientAttachment(client)
        val runtime = tcpRuntimeBySessionId[sessionId] ?: throw IOException("TCP session runtime closed before activation.")
        logger(Log.INFO, "proxy session attach sid=$sessionId localPort=${runtime.localPort} remote=${runtime.remoteIp}:${runtime.remotePort}")
        awaitSessionEstablished(sessionId, connectTimeoutMs)
        logger(Log.INFO, "proxy session active sid=$sessionId localPort=${runtime.localPort} remote=${runtime.remoteIp}:${runtime.remotePort}")

        val input = client.input
        val buffer = ByteArray(maxTcpPayloadBytes)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            val payload = buffer.copyOf(read)
            val packet =
                runtime.withLock {
                    TcpPacketBuilder.buildIpv4TcpPacket(
                        sourceIp = clientIpv4,
                        destinationIp = remoteIp,
                        sourcePort = localPort,
                        destinationPort = remotePort,
                        sequenceNumber = nextSendSequenceNumber,
                        acknowledgementNumber = remoteSequenceNumber,
                        flags = TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_PSH,
                        payload = payload,
                )
            }
            if (!bridge.queueOutboundPacket(packet)) {
                val message = "Failed to queue outbound TCP payload."
                runtime.signalRuntimeFailure(message)
                logger(Log.WARN, "proxy tcp fail sid=$sessionId reason=payload-queue-failed bytes=$read")
                updateSessionState(sessionId, UserspaceSessionState.failed, "Failed to queue outbound TCP payload len=$read")
                throw IOException(message)
            }
            runtime.withLock {
                nextSendSequenceNumber += read
            }
            logger(Log.INFO, "proxy tcp out sid=$sessionId bytes=$read")
            updateSessionState(sessionId, UserspaceSessionState.established, "Queued outbound TCP payload len=$read")
        }

        if (!runtime.markLocalFinQueued()) {
            return
        }
        val finPacket =
            runtime.withLock {
                TcpPacketBuilder.buildIpv4TcpPacket(
                    sourceIp = clientIpv4,
                    destinationIp = remoteIp,
                    sourcePort = localPort,
                    destinationPort = remotePort,
                    sequenceNumber = nextSendSequenceNumber,
                    acknowledgementNumber = remoteSequenceNumber,
                    flags = TcpPacketBuilder.TCP_FLAG_ACK or TcpPacketBuilder.TCP_FLAG_FIN,
                )
            }
        if (!bridge.queueOutboundPacket(finPacket)) {
            val message = "Failed to queue outbound TCP FIN."
            runtime.signalRuntimeFailure(message)
            logger(Log.WARN, "proxy tcp fail sid=$sessionId reason=fin-queue-failed")
            updateSessionState(sessionId, UserspaceSessionState.failed, "Failed to queue outbound TCP FIN")
            throw IOException(message)
        }
        runtime.withLock {
            nextSendSequenceNumber += 1
        }
        logger(Log.INFO, "proxy tcp close sid=$sessionId reason=fin-out")
        updateSessionState(sessionId, UserspaceSessionState.established, "Queued outbound TCP FIN; awaiting remote close")

        when (runtime.awaitRemoteCloseOrFailure()) {
            SessionWaitState.remoteClosed -> {
                updateSessionState(sessionId, UserspaceSessionState.closed, "TCP session closed cleanly")
            }
            SessionWaitState.failed -> {
                val message = runtime.terminalFailureMessage ?: "TCP session closed with failure."
                updateSessionState(sessionId, UserspaceSessionState.failed, message)
                throw IOException(message)
            }
            SessionWaitState.pending -> {
                val message = "TCP session ended without terminal state."
                runtime.signalRuntimeFailure(message)
                updateSessionState(sessionId, UserspaceSessionState.failed, message)
                throw IOException(message)
            }
        }
    }

    private fun awaitSessionEstablished(sessionId: Int, timeoutMs: Long) {
        val runtime = tcpRuntimeBySessionId[sessionId] ?: throw IOException("TCP session runtime closed before activation.")
        when (runtime.awaitConnect(timeoutMs)) {
            ConnectWaitState.established -> Unit
            ConnectWaitState.failed -> {
                val message = runtime.connectFailureMessage ?: "TCP session failed to connect."
                logger(Log.WARN, "proxy tcp fail sid=$sessionId reason=connect-failed error=$message")
                updateSessionState(sessionId, UserspaceSessionState.failed, message)
                throw IOException(message)
            }
            ConnectWaitState.pending -> {
                val message = "TCP connect timed out."
                runtime.signalConnectFailure(message)
                logger(Log.WARN, "proxy tcp fail sid=$sessionId reason=connect-timeout timeoutMs=$timeoutMs")
                updateSessionState(sessionId, UserspaceSessionState.failed, message)
                throw IOException(message)
            }
        }
    }

    private fun queuePureAck(runtime: TcpSessionRuntime, acknowledgementNumber: Long) {
        val ackPacket =
            runtime.withLock {
                TcpPacketBuilder.buildIpv4TcpPacket(
                    sourceIp = clientIpv4,
                    destinationIp = remoteIp,
                    sourcePort = localPort,
                    destinationPort = remotePort,
                    sequenceNumber = nextSendSequenceNumber,
                    acknowledgementNumber = acknowledgementNumber,
                    flags = TcpPacketBuilder.TCP_FLAG_ACK,
                )
            }
        if (bridge.queueOutboundPacket(ackPacket)) {
            logger(Log.DEBUG, "proxy tcp ack sid=${runtime.sessionId} ack=$acknowledgementNumber")
            updateSessionState(runtime.sessionId, UserspaceSessionState.established, "Queued TCP ACK ack=$acknowledgementNumber")
        } else {
            updateSessionState(runtime.sessionId, UserspaceSessionState.failed, "Failed to queue TCP ACK ack=$acknowledgementNumber")
        }
    }

    companion object {
        private const val TAG = "UserspaceTunnelStack"
        private const val IPV4_HEADER_LEN = 20
        private const val TCP_HEADER_LEN = 20
        private const val ICMP_TYPE_DESTINATION_UNREACHABLE = 3
        private const val ICMP_CODE_FRAGMENTATION_NEEDED = 4
        private const val MAX_TCP_OPTION_VALUE = 0xffff
        private const val DEFAULT_CLIENT_IPV4 = "10.0.0.2"
        private const val DEFAULT_DNS_SERVER = "8.8.8.8"
        private const val DEFAULT_LINK_MTU = 1278
        private const val DEFAULT_FAILURE_REASON = "TCP forwarding over the proxy packet bridge is not attached yet."
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
    }
}

private enum class ConnectWaitState {
    pending,
    established,
    failed,
}

private enum class SessionWaitState {
    pending,
    remoteClosed,
    failed,
}

private class TcpSessionRuntime(
    val sessionId: Int,
    val localPort: Int,
    var remoteIp: String,
    val remotePort: Int,
    val initialSequenceNumber: Long,
) {
    var remoteSequenceNumber: Long = 0
    var handshakeAckQueued: Boolean = false
    var nextSendSequenceNumber: Long = initialSequenceNumber + 1
    var localFinQueued: Boolean = false
    var connectFailureMessage: String? = null
        private set
    var terminalFailureMessage: String? = null
        private set

    private var connectState: ConnectWaitState = ConnectWaitState.pending
    private var sessionWaitState: SessionWaitState = SessionWaitState.pending
    private val connectLatch = CountDownLatch(1)
    private val sessionWaitLatch = CountDownLatch(1)

    @Synchronized
    fun <T> withLock(block: TcpSessionRuntime.() -> T): T = block()

    @Synchronized
    fun isConnectPending(): Boolean = connectState == ConnectWaitState.pending

    fun awaitConnect(timeoutMs: Long): ConnectWaitState =
        try {
            if (connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                synchronized(this) { connectState }
            } else {
                ConnectWaitState.pending
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            signalConnectFailure("Proxy session interrupted while waiting for connect.")
            ConnectWaitState.failed
        }

    @Synchronized
    fun markEstablished(establishedRemoteIp: String, establishedRemoteSequence: Long) {
        if (connectState != ConnectWaitState.pending) return
        handshakeAckQueued = true
        remoteIp = establishedRemoteIp
        remoteSequenceNumber = establishedRemoteSequence
        connectState = ConnectWaitState.established
        connectLatch.countDown()
    }

    @Synchronized
    fun signalConnectFailure(message: String) {
        if (connectState != ConnectWaitState.pending) return
        connectFailureMessage = message
        connectState = ConnectWaitState.failed
        connectLatch.countDown()
    }

    @Synchronized
    fun markLocalFinQueued(): Boolean {
        if (localFinQueued) return false
        localFinQueued = true
        return true
    }

    @Synchronized
    fun markRemoteClosed() {
        if (sessionWaitState != SessionWaitState.pending) return
        sessionWaitState = SessionWaitState.remoteClosed
        sessionWaitLatch.countDown()
    }

    fun awaitRemoteCloseOrFailure(): SessionWaitState {
        return try {
            sessionWaitLatch.await()
            synchronized(this) { sessionWaitState }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            signalRuntimeFailure("Proxy session interrupted while waiting for remote close.")
            SessionWaitState.failed
        }
    }

    @Synchronized
    fun signalRuntimeFailure(message: String) {
        if (connectState == ConnectWaitState.pending) {
            connectFailureMessage = message
            connectState = ConnectWaitState.failed
            connectLatch.countDown()
        }
        if (sessionWaitState != SessionWaitState.pending) return
        terminalFailureMessage = message
        sessionWaitState = SessionWaitState.failed
        sessionWaitLatch.countDown()
    }
}

private class ClientAttachment(
    client: ProxyClientConnection,
) {
    private val socket = client.socket
    private val output: OutputStream = client.output

    @Synchronized
    fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    fun shutdownOutput() {
        try {
            socket.shutdownOutput()
        } catch (_: IOException) {
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

internal fun String.toIpv4LiteralOrNull(): String? {
    val parts = split('.')
    if (parts.size != 4) return null
    return if (parts.all { it.toIntOrNull() in 0..255 }) this else null
}

private class BridgeUserspaceTunnelSession(
    override val descriptor: ProxySessionDescriptor,
    private val onPumpClientTraffic: (ProxyClientConnection) -> Unit,
    private val onAwaitEstablished: (Long) -> Unit,
    private val onStateChanged: (UserspaceSessionState, String) -> Unit,
    private val onClose: () -> Unit,
    private val failureReason: String,
) : UserspaceTunnelSession {
    private var closed = false

    override fun awaitEstablished(timeoutMs: Long) {
        if (closed) {
            throw IOException("Proxy session ${descriptor.sessionId} is closed.")
        }
        try {
            onAwaitEstablished(timeoutMs)
        } catch (e: IOException) {
            onStateChanged(UserspaceSessionState.failed, e.message ?: failureReason)
            throw e
        }
    }

    override fun pumpBidirectional(client: ProxyClientConnection) {
        if (closed) {
            throw IOException("Proxy session ${descriptor.sessionId} is closed.")
        }
        try {
            onPumpClientTraffic(client)
        } catch (e: IOException) {
            onStateChanged(UserspaceSessionState.failed, e.message ?: failureReason)
            throw e
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}
