package io.github.evokelektrique.tunnelforge

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.TreeMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

interface UserspaceTunnelStack {
    fun waitUntilReady(timeoutMs: Long = 4000, pollIntervalMs: Long = 100): Boolean

    fun openTcpSession(request: ProxyConnectRequest): UserspaceTunnelSession

    fun openUdpAssociation(request: ProxyConnectRequest): UserspaceUdpAssociation

    fun stop()
}

interface UserspaceTunnelSession : AutoCloseable {
    val descriptor: ProxySessionDescriptor

    @Throws(IOException::class)
    fun awaitEstablished(timeoutMs: Long)

    @Throws(IOException::class)
    fun pumpBidirectional(client: ProxyClientConnection)
}

interface UserspaceUdpAssociation : AutoCloseable {
    val descriptor: ProxySessionDescriptor

    @Throws(IOException::class)
    fun send(datagram: ProxyUdpDatagram)

    @Throws(IOException::class)
    fun receive(timeoutMs: Long): ProxyUdpDatagram?
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

internal class BridgeUserspaceTunnelStack(
    private val bridge: ProxyPacketBridge,
    private val clientIpv4: String = DEFAULT_CLIENT_IPV4,
    private val dnsServers: List<ResolvedDnsServerConfig> = emptyList(),
    private val linkMtu: Int = DEFAULT_LINK_MTU,
    private val logger: (Int, String) -> Unit = { level, message ->
        AppLog.println(level, TAG, message)
    },
    private val failureReason: String = DEFAULT_FAILURE_REASON,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val synRetransmitDelaysMs: List<Long> = DEFAULT_SYN_RETRANSMIT_DELAYS_MS,
    private val maxTcpSessions: Int? = DEFAULT_MAX_TCP_SESSIONS,
    private val maxPendingTcpConnects: Int? = DEFAULT_MAX_PENDING_TCP_CONNECTS,
    private val synPacingIntervalMs: Long = DEFAULT_SYN_PACING_INTERVAL_MS,
    private val tcpFinDrainTimeoutMs: Long = DEFAULT_TCP_FIN_DRAIN_TIMEOUT_MS,
    private val noReplyFailureCacheTtlMs: Long = DEFAULT_NO_REPLY_FAILURE_CACHE_TTL_MS,
    private val tcpPersistProbeDelaysMs: List<Long> = DEFAULT_TCP_PERSIST_PROBE_DELAYS_MS,
) : UserspaceTunnelStack {
    init {
        require(maxTcpSessions == null || maxTcpSessions >= 1) { "TCP session limit must be at least 1" }
        require(maxPendingTcpConnects == null || maxPendingTcpConnects >= 1) { "Pending TCP connect limit must be at least 1" }
        require(synPacingIntervalMs >= 0) { "SYN pacing interval must not be negative" }
        require(tcpFinDrainTimeoutMs >= 1) { "TCP FIN drain timeout must be at least 1ms" }
        require(tcpPersistProbeDelaysMs.isNotEmpty()) { "TCP persist probe delays must not be empty" }
        require(tcpPersistProbeDelaysMs.all { it >= 1 }) { "TCP persist probe delays must be positive" }
    }

    private val nextSessionId = AtomicInteger(1)
    private val nextLocalPort = AtomicInteger(TCP_LOCAL_PORT_MIN)
    private val nextUdpLocalPort = AtomicInteger(20000)
    private val tcpSessionPermits = maxTcpSessions?.let { Semaphore(it, true) }
    private val pendingTcpConnectPermits = maxPendingTcpConnects?.let { Semaphore(it, true) }
    private val pendingTcpConnectPermitsByBudgetKey = ConcurrentHashMap<String, Semaphore>()
    private val sessions = ConcurrentHashMap<Int, UserspaceSessionSnapshot>()
    private val tcpRuntimeBySessionId = ConcurrentHashMap<Int, TcpSessionRuntime>()
    private val tcpRuntimeByLocalPort = ConcurrentHashMap<Int, TcpSessionRuntime>()
    private val noReplyFailureCache = ConcurrentHashMap<NoReplyTargetKey, CachedNoReplyFailure>()
    private val udpRuntimeBySessionId = ConcurrentHashMap<Int, UdpAssociationRuntime>()
    private val udpRuntimeByLocalPort = ConcurrentHashMap<Int, UdpAssociationRuntime>()
    private val clientAttachments = ConcurrentHashMap<Int, ClientAttachment>()
    private val nextSynSendAtMs = AtomicLong(0)
    private val tcpScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "proxy-tcp-scheduler").apply { isDaemon = true }
        }
    private val dnsResolver =
        TunneledDnsResolver(
            bridge = bridge,
            clientIpv4 = clientIpv4,
            dnsServers = dnsServers,
            openTunneledSocket = ::openTunneledSocket,
            logger = logger,
        )
    private val maxTcpPayloadBytes = (linkMtu - IPV4_HEADER_LEN - TCP_HEADER_LEN).coerceAtLeast(1)
    private val maxUdpPayloadBytes = (linkMtu - IPV4_HEADER_LEN - UDP_HEADER_LEN).coerceAtLeast(1)
    private val advertisedMss = maxTcpPayloadBytes.coerceAtMost(MAX_TCP_OPTION_VALUE)
    private val receiveWindowBytes = DEFAULT_TCP_RECEIVE_WINDOW_BYTES.coerceAtMost(MAX_TCP_OPTION_VALUE)

    @Volatile
    private var inboundPump: Thread? = null

    @Volatile
    private var running = false

    override fun waitUntilReady(timeoutMs: Long, pollIntervalMs: Long): Boolean {
        logger(Log.DEBUG, "userspace stack waitUntilReady timeoutMs=$timeoutMs pollIntervalMs=$pollIntervalMs")
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
                Log.DEBUG,
                "userspace stack inbound pump started clientIpv4=$clientIpv4 dns=${dnsServers.joinToString(",") { "${it.host}[${it.protocol.shortLabel}]=>${it.resolvedIpv4}" }} mtu=$linkMtu mss=$advertisedMss",
            )
        }
        return true
    }

    override fun openTcpSession(request: ProxyConnectRequest): UserspaceTunnelSession {
        if (!running || !bridge.isRunning()) {
            throw ProxyTransportException(ProxyTransportFailureReason.localServiceUnavailable, "Proxy packet bridge is not active.")
        }
        if (tcpSessionPermits?.tryAcquire() == false) {
            logger(
                Log.WARN,
                "proxy session reject reason=too-many-tcp-sessions limit=${maxTcpSessions ?: "unlimited"} active=${sessions.size} states=${sessionStateSummary()} target=${request.host}:${request.port}",
            )
            throw ProxyTransportException(ProxyTransportFailureReason.localServiceUnavailable, "Too many active proxy TCP sessions; try again shortly.")
        }
        val descriptor =
            ProxySessionDescriptor(
                sessionId = nextSessionId.getAndIncrement(),
                request = request,
                openedAtMs = System.currentTimeMillis(),
            )
        sessions[descriptor.sessionId] =
            UserspaceSessionSnapshot(
                descriptor = descriptor,
                state = UserspaceSessionState.opening,
                lastEvent = "Session allocated; waiting for remote TCP endpoint",
            )
        var pendingConnectLease: PendingConnectLease? = null
        try {
            val remoteIp = resolveRemoteIpv4(descriptor)
            rejectCachedNoReplyFailure(descriptor, remoteIp)
            val connectBudget = pendingConnectBudgetFor(descriptor.request)
            val connectLease = acquirePendingConnectLease(descriptor.request, connectBudget)
            pendingConnectLease = connectLease
            if (!running || !bridge.isRunning()) {
                throw ProxyTransportException(ProxyTransportFailureReason.localServiceUnavailable, "Proxy packet bridge is not active.")
            }
            queueSyntheticSyn(
                descriptor = descriptor,
                remoteIp = remoteIp,
                pendingConnectLease = connectLease,
                effectiveConnectTimeoutMs = connectBudget.connectTimeoutMs,
            )
        } catch (e: IOException) {
            logger(Log.WARN, "proxy session open failed sid=${descriptor.sessionId} target=${request.host}:${request.port} error=${e.message}")
            sessions.remove(descriptor.sessionId)
            removeTcpRuntime(descriptor.sessionId)?.let { runtime ->
                releasePendingConnectPermit(runtime)
            } ?: pendingConnectLease?.release()
            tcpSessionPermits?.release()
            throw e
        }
        return BridgeUserspaceTunnelSession(
            descriptor = descriptor,
            onPumpClientTraffic = { client -> pumpSessionTraffic(descriptor.sessionId, client) },
            onAwaitEstablished = { timeoutMs -> awaitSessionEstablished(descriptor.sessionId, timeoutMs) },
            onStateChanged = { state, event -> updateSessionState(descriptor.sessionId, state, event) },
            onClose = {
                removeTcpRuntime(descriptor.sessionId)?.let { runtime ->
                    runtime.signalRuntimeFailure("Session closed")
                    releasePendingConnectPermit(runtime)
                }
                clientAttachments.remove(descriptor.sessionId)?.close()
                updateSessionState(descriptor.sessionId, UserspaceSessionState.closed, "Session closed")
                sessions.remove(descriptor.sessionId)
                tcpSessionPermits?.release()
            },
            failureReason = failureReason,
        )
    }

    override fun openUdpAssociation(request: ProxyConnectRequest): UserspaceUdpAssociation {
        if (!running || !bridge.isRunning()) {
            throw ProxyTransportException(ProxyTransportFailureReason.localServiceUnavailable, "Proxy packet bridge is not active.")
        }
        val descriptor =
            ProxySessionDescriptor(
                sessionId = nextSessionId.getAndIncrement(),
                request = request,
                openedAtMs = System.currentTimeMillis(),
            )
        val localPort = allocateUdpLocalPort()
        val runtime =
            UdpAssociationRuntime(
                sessionId = descriptor.sessionId,
                localPort = localPort,
            )
        udpRuntimeBySessionId[descriptor.sessionId] = runtime
        udpRuntimeByLocalPort[localPort] = runtime
        sessions[descriptor.sessionId] =
            UserspaceSessionSnapshot(
                descriptor = descriptor,
                state = UserspaceSessionState.established,
                lastEvent = "UDP association ready on $clientIpv4:$localPort",
            )
        logger(Log.DEBUG, "proxy udp association open sid=${descriptor.sessionId} local=$clientIpv4:$localPort")
        return BridgeUserspaceUdpAssociation(
            descriptor = descriptor,
            onSend = { datagram -> sendUdpDatagram(runtime, datagram) },
            onReceive = { timeoutMs -> runtime.receive(timeoutMs) },
            onClose = {
                runtime.close()
                udpRuntimeBySessionId.remove(descriptor.sessionId, runtime)
                udpRuntimeByLocalPort.remove(localPort, runtime)
                sessions.remove(descriptor.sessionId)
                logger(Log.DEBUG, "proxy udp association close sid=${descriptor.sessionId} local=$clientIpv4:$localPort")
            },
        )
    }

    fun activeSessions(): List<ProxySessionDescriptor> = sessions.values.sortedBy { it.descriptor.sessionId }.map { it.descriptor }

    fun sessionSnapshots(): List<UserspaceSessionSnapshot> = sessions.values.sortedBy { it.descriptor.sessionId }

    internal fun processInboundPacketForTesting(packet: ByteArray) = handleInboundPacket(packet)

    override fun stop() {
        running = false
        val tcpRuntimeCount = tcpRuntimeBySessionId.size
        val attachmentCount = clientAttachments.size
        logger(Log.DEBUG, "userspace stack stop sessions=${sessions.size} runtimes=$tcpRuntimeCount attachments=$attachmentCount")
        tcpRuntimeBySessionId.values.toList().forEach {
            failTcpRuntime(
                runtime = it,
                reason = "bridge-stopped",
                message = "Proxy packet bridge stopped.",
                closeClient = true,
                logWarning = false,
            )
        }
        if (tcpRuntimeCount > 0 || attachmentCount > 0) {
            logger(Log.DEBUG, "proxy tcp stop summary runtimes=$tcpRuntimeCount attachments=$attachmentCount")
        }
        udpRuntimeBySessionId.values.forEach { it.close() }
        sessions.clear()
        tcpRuntimeBySessionId.clear()
        tcpRuntimeByLocalPort.clear()
        udpRuntimeBySessionId.clear()
        udpRuntimeByLocalPort.clear()
        clientAttachments.values.forEach { it.close() }
        clientAttachments.clear()
        tcpScheduler.shutdownNow()
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
                    "Dropped unsupported IPv4 protocol=${ipv4.protocol} ${ipv4.sourceIp} -> ${ipv4.destinationIp} len=${ipv4.totalLength}",
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
            val runtime = tcpRuntimeByLocalPort[quotedTcp.sourcePort]
                ?.takeIf {
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
        if (icmp.type == ICMP_TYPE_DESTINATION_UNREACHABLE &&
            icmp.code != ICMP_CODE_FRAGMENTATION_NEEDED &&
            icmp.quotedIpv4 != null &&
            icmp.quotedTcp != null
        ) {
            val quotedIpv4 = icmp.quotedIpv4
            val quotedTcp = icmp.quotedTcp
            val runtime = tcpRuntimeByLocalPort[quotedTcp.sourcePort]
                ?.takeIf {
                    it.remotePort == quotedTcp.destinationPort &&
                        it.remoteIp == quotedIpv4.destinationIp
                }
            if (runtime != null) {
                runtime.noteIcmpUnreachable()
                clearNoReplyFailure(runtime)
                val message = "Observed ICMP destination unreachable code=${icmp.code} from ${ipv4.sourceIp} for ${runtime.describeConnectTarget()}."
                failTcpRuntime(
                    runtime = runtime,
                    reason = "icmp-unreachable code=${icmp.code}",
                    message = message,
                    closeClient = !runtime.isConnectPending(),
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
            handleInboundProxyUdpPacket(packet, ipv4, udp)
        }
    }

    private fun handleInboundProxyUdpPacket(
        packet: ByteArray,
        ipv4: ParsedIpv4Packet,
        udp: ParsedUdpDatagram,
    ) {
        val runtime = udpRuntimeByLocalPort[udp.destinationPort]
        if (runtime == null) {
            return
        }
        if (!runtime.acceptsRemote(ipv4.sourceIp, udp.sourcePort)) {
            logger(
                Log.DEBUG,
                "Dropped inbound UDP source mismatch sid=${runtime.sessionId} source=${ipv4.sourceIp}:${udp.sourcePort} local=${udp.destinationPort}",
            )
            return
        }
        val payload = packet.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        if (runtime.enqueue(ProxyUdpDatagram(host = ipv4.sourceIp, port = udp.sourcePort, payload = payload))) {
            logger(Log.DEBUG, "proxy udp in sid=${runtime.sessionId} source=${ipv4.sourceIp}:${udp.sourcePort} bytes=${udp.payloadLength}")
            updateSessionState(
                runtime.sessionId,
                UserspaceSessionState.established,
                "Queued inbound UDP payload len=${udp.payloadLength}",
            )
        } else {
            logger(Log.WARN, "proxy udp drop sid=${runtime.sessionId} reason=receive-queue-full")
            updateSessionState(runtime.sessionId, UserspaceSessionState.failed, "UDP receive queue is full")
        }
    }

    private fun handleInboundTcpPacket(packet: ByteArray, ipv4: ParsedIpv4Packet) {
        val tcp = IpPacketParser.parseTcp(packet, ipv4)
        if (tcp == null) {
            logger(Log.DEBUG, "Dropped malformed TCP segment ${ipv4.sourceIp} -> ${ipv4.destinationIp} len=${ipv4.totalLength}")
            return
        }
        val runtimeForPort = tcpRuntimeByLocalPort[tcp.destinationPort]
        val match = runtimeForPort?.takeIf {
            it.remotePort == tcp.sourcePort &&
                it.remoteIp == ipv4.sourceIp
        }
        if (match != null) {
            match.noteInboundTcp()
            handleInboundTcpMatch(match, packet, ipv4, tcp)
        } else {
            if (runtimeForPort == null) {
                logger(
                    Log.DEBUG,
                    "Dropped inbound TCP no local runtime ${ipv4.sourceIp}:${tcp.sourcePort} -> ${ipv4.destinationIp}:${tcp.destinationPort} flags=0x${tcp.flags.toString(16)}",
                )
            } else {
                runtimeForPort.noteSourceMismatch()
                logger(
                    Log.DEBUG,
                    "Dropped inbound TCP source mismatch sid=${runtimeForPort.sessionId} expected=${runtimeForPort.remoteIp}:${runtimeForPort.remotePort} actual=${ipv4.sourceIp}:${tcp.sourcePort} local=${tcp.destinationPort} flags=0x${tcp.flags.toString(16)}",
                )
            }
        }
    }

    private fun handleInboundTcpMatch(
        runtime: TcpSessionRuntime,
        packet: ByteArray,
        ipv4: ParsedIpv4Packet,
        tcp: ParsedTcpSegment,
    ) {
        if ((tcp.flags and TcpPacketBuilder.TCP_FLAG_RST) != 0) {
            runtime.noteRst()
            clearNoReplyFailure(runtime)
            val message = "Observed TCP RST from ${ipv4.sourceIp}:${tcp.sourcePort}"
            failTcpRuntime(
                runtime = runtime,
                reason = "rst-in",
                message = message,
                closeClient = !runtime.isConnectPending(),
            )
            return
        }

        if ((tcp.flags and TcpPacketBuilder.TCP_FLAG_ACK) != 0) {
            val ackUpdate = runtime.acknowledgeOutbound(tcp.acknowledgementNumber, tcp.windowSize)
            if (ackUpdate.acknowledgedSegments > 0 || ackUpdate.windowChanged || ackUpdate.sendResumed) {
                updateSessionState(
                    runtime.sessionId,
                    UserspaceSessionState.established,
                    "TCP ACK ack=${tcp.acknowledgementNumber} acknowledged=${ackUpdate.acknowledgedSegments} peerWindow=${tcp.windowSize} outstanding=${ackUpdate.outstandingBytes} sendResumed=${ackUpdate.sendResumed}",
                )
            }
            ackUpdate.fastRetransmitStartSequence?.let { startSequence ->
                retransmitOutstandingTcpSegment(runtime, startSequence, reason = "fast-duplicate-ack")
            }
        }

        if ((tcp.flags and TcpPacketBuilder.TCP_FLAG_SYN) != 0 &&
            (tcp.flags and TcpPacketBuilder.TCP_FLAG_ACK) != 0 &&
            runtime.isConnectPending()
        ) {
            runtime.noteSynAckMatched()
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
                        windowSize = advertisedReceiveWindow(runtime),
                    )
                }
            if (bridge.queueOutboundPacket(ackPacket)) {
                clearNoReplyFailure(runtime)
                if (runtime.markEstablished(ipv4.sourceIp, tcp.sequenceNumber + 1, tcp.windowSize)) {
                    releasePendingConnectPermit(runtime)
                }
                logger(Log.DEBUG, "proxy tcp established sid=${runtime.sessionId}")
                updateSessionState(
                    runtime.sessionId,
                    UserspaceSessionState.established,
                    "Queued TCP ACK for SYN/ACK from ${ipv4.sourceIp}:${tcp.sourcePort}",
                )
            } else {
                val message = "Observed SYN/ACK but failed to queue final TCP ACK"
                failTcpRuntime(runtime, reason = "ack-queue-failed", message = message, closeClient = false)
            }
            return
        }

        val finConsumesByte = if ((tcp.flags and TcpPacketBuilder.TCP_FLAG_FIN) != 0) 1 else 0
        var deliveredFin = false
        if (tcp.payloadLength > 0 || finConsumesByte != 0) {
            val expectedSequence = runtime.withLock { remoteSequenceNumber }
            if (tcp.sequenceNumber < expectedSequence) {
                queuePureAck(runtime, expectedSequence)
                updateSessionState(
                    runtime.sessionId,
                    UserspaceSessionState.established,
                    "Ignored duplicate inbound TCP payload seq=${tcp.sequenceNumber} expected=$expectedSequence",
                )
                return
            }
            if (tcp.sequenceNumber > expectedSequence) {
                val payload =
                    if (tcp.payloadLength > 0) {
                        packet.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
                    } else {
                        byteArrayOf()
                    }
                val buffered = runtime.bufferOutOfOrderInbound(tcp.sequenceNumber, payload, finConsumesByte != 0)
                queuePureAck(runtime, expectedSequence)
                updateSessionState(
                    runtime.sessionId,
                    UserspaceSessionState.established,
                    if (buffered) {
                        "Buffered out-of-order inbound TCP seq=${tcp.sequenceNumber} expected=$expectedSequence buffered=${runtime.bufferedInboundBytes()}"
                    } else {
                        "Dropped out-of-order inbound TCP seq=${tcp.sequenceNumber} expected=$expectedSequence buffered=${runtime.bufferedInboundBytes()}"
                    },
                )
                return
            }
            val payload =
                if (tcp.payloadLength > 0) {
                    packet.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
                } else {
                    byteArrayOf()
                }
            val delivery =
                deliverInboundTcpSegments(
                    runtime = runtime,
                    firstSegment = InboundTcpSegment(tcp.sequenceNumber, payload, finConsumesByte != 0),
                )
            if (delivery.failed) return
            if (delivery.blocked) {
                queuePureAck(runtime, expectedSequence)
                return
            }
            deliveredFin = delivery.deliveredFin
            queuePureAck(runtime, runtime.withLock { remoteSequenceNumber })
        } else {
            updateSessionState(
                runtime.sessionId,
                UserspaceSessionState.established,
                "Observed inbound TCP for session ${runtime.sessionId} from ${ipv4.sourceIp}:${tcp.sourcePort}",
            )
        }

        if (deliveredFin) {
            clientAttachments[runtime.sessionId]?.shutdownOutput()
            runtime.markRemoteClosed()
            logger(Log.DEBUG, "proxy tcp close sid=${runtime.sessionId} reason=fin-in")
            updateSessionState(
                runtime.sessionId,
                UserspaceSessionState.established,
                "Observed TCP FIN from ${ipv4.sourceIp}:${tcp.sourcePort}; awaiting local close",
            )
        }
    }

    private fun deliverInboundTcpSegments(runtime: TcpSessionRuntime, firstSegment: InboundTcpSegment): InboundDeliveryResult {
        val attachment = clientAttachments[runtime.sessionId]
        if (attachment == null && firstSegment.payload.isNotEmpty()) {
            val message = "Inbound TCP payload arrived without attached client socket"
            failTcpRuntime(runtime, reason = "no-client-attachment", message = message, closeClient = true)
            return InboundDeliveryResult(deliveredFin = false, blocked = false, failed = true)
        }

        var current: InboundTcpSegment? = firstSegment
        var deliveredFin = false
        while (current != null) {
            val segment = current
            val expected = runtime.withLock { remoteSequenceNumber }
            if (segment.sequenceNumber != expected) break
            if (segment.payload.isNotEmpty()) {
                val target = attachment ?: clientAttachments[runtime.sessionId]
                if (target == null) {
                    val message = "Inbound TCP payload arrived without attached client socket"
                    failTcpRuntime(runtime, reason = "no-client-attachment", message = message, closeClient = true)
                    return InboundDeliveryResult(deliveredFin = deliveredFin, blocked = false, failed = true)
                }
                if (!target.enqueue(segment.payload)) {
                    updateSessionState(
                        runtime.sessionId,
                        UserspaceSessionState.established,
                        "Paused inbound TCP delivery len=${segment.payload.size} queued=${target.queuedBytes()} window=${target.advertisedWindow()}",
                    )
                    return InboundDeliveryResult(deliveredFin = deliveredFin, blocked = true, failed = false)
                }
                updateSessionState(
                    runtime.sessionId,
                    UserspaceSessionState.established,
                    "Queued inbound TCP payload len=${segment.payload.size} deliveryQueued=${target.queuedBytes()} window=${target.advertisedWindow()}",
                )
            }
            runtime.withLock {
                remoteSequenceNumber += segment.payload.size + if (segment.fin) 1 else 0
            }
            deliveredFin = deliveredFin || segment.fin
            if (current === firstSegment) {
                current = runtime.peekContiguousBufferedInbound()
            } else {
                runtime.removeBufferedInbound(segment.sequenceNumber)
                current = runtime.peekContiguousBufferedInbound()
            }
        }
        return InboundDeliveryResult(deliveredFin = deliveredFin, blocked = false, failed = false)
    }

    private fun resolveRemoteIpv4(descriptor: ProxySessionDescriptor): String {
        descriptor.request.host.toIpv4LiteralOrNull()?.let {
            logger(Log.DEBUG, "proxy resolve sid=${descriptor.sessionId} host=${descriptor.request.host} mode=literal ip=$it")
            return it
        }
        if (descriptor.request.host.contains(':')) {
            throw ProxyTransportException(ProxyTransportFailureReason.networkUnreachable, "IPv6 destinations are not supported in proxy mode.")
        }
        updateSessionState(
            descriptor.sessionId,
            UserspaceSessionState.opening,
            "Resolving ${descriptor.request.host} through tunneled DNS",
        )
        val resolved = dnsResolver.resolve(descriptor.request.host)
        logger(Log.DEBUG, "proxy resolve sid=${descriptor.sessionId} host=${descriptor.request.host} mode=dns ip=$resolved")
        return resolved
    }

    private fun queueSyntheticSyn(
        descriptor: ProxySessionDescriptor,
        remoteIp: String,
        pendingConnectLease: PendingConnectLease,
        effectiveConnectTimeoutMs: Long,
    ) {
        val sequenceNumber = descriptor.sessionId.toLong() shl 20
        val runtime = reserveTcpRuntime(descriptor, remoteIp, sequenceNumber, pendingConnectLease, effectiveConnectTimeoutMs)
        tcpRuntimeBySessionId[descriptor.sessionId] = runtime
        updateSessionState(
            descriptor.sessionId,
            UserspaceSessionState.opening,
            "Queued synthetic TCP SYN $clientIpv4:${runtime.localPort} -> $remoteIp:${descriptor.request.port}",
        )

        if (!scheduleSyntheticSynPacket(runtime, reason = "initial", delayMs = 0L)) {
            if (!running || !bridge.isRunning()) {
                val message = "Proxy packet bridge is not active."
                runtime.signalRuntimeFailure(message)
                updateSessionState(descriptor.sessionId, UserspaceSessionState.failed, message)
                if (runtime.markFailureLogged()) {
                    logger(Log.WARN, "proxy tcp fail sid=${descriptor.sessionId} reason=bridge-inactive error=$message")
                }
                throw ProxyTransportException(ProxyTransportFailureReason.localServiceUnavailable, message)
            }
            logger(
                Log.DEBUG,
                "proxy tcp queue retry sid=${descriptor.sessionId} reason=syn-queue-backpressure ${runtime.describeConnectTarget()}",
            )
            scheduleSyntheticSynPacket(runtime, reason = "queue-retry", delayMs = SYN_QUEUE_RETRY_DELAY_MS)
        }
        startConnectTimers(runtime)
    }

    private fun reserveTcpRuntime(
        descriptor: ProxySessionDescriptor,
        remoteIp: String,
        sequenceNumber: Long,
        pendingConnectLease: PendingConnectLease,
        effectiveConnectTimeoutMs: Long,
    ): TcpSessionRuntime {
        repeat(TCP_LOCAL_PORT_SPAN) {
            val candidate = nextTcpLocalPortCandidate()
            val runtime =
                TcpSessionRuntime(
                    sessionId = descriptor.sessionId,
                    targetHost = descriptor.request.host,
                    localPort = candidate,
                    remoteIp = remoteIp,
                    remotePort = descriptor.request.port,
                    initialSequenceNumber = sequenceNumber,
                    connectTimeoutMs = effectiveConnectTimeoutMs,
                    pendingConnectLease = pendingConnectLease,
                    inboundReorderLimitBytes = receiveWindowBytes,
                    persistProbeDelaysMs = tcpPersistProbeDelaysMs,
                )
            if (tcpRuntimeByLocalPort.putIfAbsent(candidate, runtime) == null) {
                return runtime
            }
        }
        throw IOException("No local proxy TCP source ports are available.")
    }

    private fun nextTcpLocalPortCandidate(): Int =
        nextLocalPort.getAndUpdate {
            if (it >= TCP_LOCAL_PORT_MAX) TCP_LOCAL_PORT_MIN else it + 1
        }

    private fun scheduleSyntheticSynPacket(runtime: TcpSessionRuntime, reason: String, delayMs: Long): Boolean {
        if (delayMs > 0L) {
            val task =
                tcpScheduler.schedule(
                    {
                        if (running && runtime.isConnectPending() && !scheduleSyntheticSynPacket(runtime, reason, delayMs = 0L)) {
                            if (!bridge.isRunning()) {
                                val message = "Proxy packet bridge is not active."
                                failTcpRuntime(runtime, reason = "bridge-inactive", message = message, closeClient = false)
                            } else {
                                logger(Log.DEBUG, "proxy tcp queue retry sid=${runtime.sessionId} reason=syn-$reason ${runtime.describeConnectTarget()}")
                                scheduleSyntheticSynPacket(runtime, reason = "queue-retry", delayMs = SYN_QUEUE_RETRY_DELAY_MS)
                            }
                        }
                    },
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
            runtime.attachScheduledTask(task)
            return true
        }
        val effectiveDelayMs = pacedSynDelayMs()
        if (effectiveDelayMs <= 0L) {
            return queueSyntheticSynPacket(runtime, reason)
        }
        val task =
            tcpScheduler.schedule(
                {
                    if (running && runtime.isConnectPending() && !queueSyntheticSynPacket(runtime, reason)) {
                        if (!bridge.isRunning()) {
                            val message = "Proxy packet bridge is not active."
                            failTcpRuntime(runtime, reason = "bridge-inactive", message = message, closeClient = false)
                        } else {
                            logger(Log.DEBUG, "proxy tcp queue retry sid=${runtime.sessionId} reason=syn-$reason ${runtime.describeConnectTarget()}")
                            scheduleSyntheticSynPacket(runtime, reason = "queue-retry", delayMs = SYN_QUEUE_RETRY_DELAY_MS)
                        }
                    }
                },
                effectiveDelayMs,
                TimeUnit.MILLISECONDS,
            )
        runtime.attachScheduledTask(task)
        return true
    }

    private fun pacedSynDelayMs(): Long {
        if (synPacingIntervalMs == 0L) return 0L
        val now = System.currentTimeMillis()
        while (true) {
            val current = nextSynSendAtMs.get()
            val sendAtMs = maxOf(now, current)
            val next = sendAtMs + synPacingIntervalMs
            if (nextSynSendAtMs.compareAndSet(current, next)) {
                return (sendAtMs - now).coerceAtLeast(0L)
            }
        }
    }

    private fun queueSyntheticSynPacket(runtime: TcpSessionRuntime, reason: String): Boolean {
        val attempt = runtime.recordSynAttempt()
        val synPacket =
            runtime.withLock {
                TcpPacketBuilder.buildIpv4TcpPacket(
                    sourceIp = clientIpv4,
                    destinationIp = remoteIp,
                    sourcePort = localPort,
                    destinationPort = remotePort,
                    sequenceNumber = initialSequenceNumber,
                    flags = TcpPacketBuilder.TCP_FLAG_SYN,
                    mss = advertisedMss,
                    windowSize = receiveWindowBytes,
                )
            }
        if (!bridge.queueOutboundPacket(synPacket)) {
            return false
        }
        logger(
            Log.DEBUG,
            "proxy tcp syn sid=${runtime.sessionId} reason=$reason attempt=$attempt ${runtime.describeConnectTarget()} seq=${runtime.initialSequenceNumber}",
        )
        if (runtime.isConnectPending()) {
            updateSessionState(
                runtime.sessionId,
                UserspaceSessionState.opening,
                "Queued TCP SYN attempt=$attempt ${runtime.describeConnectTarget()}",
            )
        }
        return true
    }

    private fun startConnectTimers(runtime: TcpSessionRuntime) {
        for (delayMs in synRetransmitDelaysMs.filter { it < runtime.connectTimeoutMs.coerceAtLeast(1L) }) {
            scheduleSyntheticSynPacket(runtime, reason = "retransmit", delayMs = delayMs.coerceAtLeast(1L))
        }
        runtime.attachScheduledTask(
            tcpScheduler.schedule(
                {
                    if (running && runtime.isConnectPending()) {
                        val message = buildConnectTimeoutMessage(runtime)
                        recordNoReplyFailureIfApplicable(runtime)
                        failTcpRuntime(runtime, reason = "connect-timeout timeoutMs=${runtime.connectTimeoutMs}", message = message, closeClient = false)
                    }
                },
                runtime.connectTimeoutMs.coerceAtLeast(1L),
                TimeUnit.MILLISECONDS,
            ),
        )
    }

    private fun acquirePendingConnectLease(
        request: ProxyConnectRequest,
        budget: PendingConnectBudget,
    ): PendingConnectLease {
        val releases = ArrayList<() -> Unit>(2)
        try {
            pendingTcpConnectPermits?.let { permit ->
                acquireConnectPermit(
                    permit = permit,
                    request = request,
                    timeoutMs = budget.waitTimeoutMs,
                    reason = "pending-tcp-connects",
                    limitLabel = maxPendingTcpConnects?.toString() ?: "unlimited",
                    scopeLabel = budget.label,
                )
                releases += { permit.release() }
            }
            if (budget.key != null && budget.limit != null) {
                val permit = pendingTcpConnectPermitsByBudgetKey.computeIfAbsent(budget.key) { Semaphore(budget.limit, true) }
                acquireConnectPermit(
                    permit = permit,
                    request = request,
                    timeoutMs = budget.waitTimeoutMs,
                    reason = "pending-destination-connects",
                    limitLabel = budget.limit.toString(),
                    scopeLabel = budget.label,
                )
                releases += { permit.release() }
            }
            return PendingConnectLease(releases)
        } catch (e: IOException) {
            releases.asReversed().forEach { it() }
            throw e
        }
    }

    private fun acquireConnectPermit(
        permit: Semaphore,
        request: ProxyConnectRequest,
        timeoutMs: Long,
        reason: String,
        limitLabel: String,
        scopeLabel: String,
    ) {
        if (permit.tryAcquire()) return

        logger(
            Log.DEBUG,
            "proxy session wait reason=$reason scope=$scopeLabel limit=$limitLabel timeoutMs=$timeoutMs active=${sessions.size} states=${sessionStateSummary()} target=${request.host}:${request.port}",
        )
        val acquired =
            try {
                permit.tryAcquire(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger(
                    Log.DEBUG,
                    "proxy session wait interrupted reason=$reason scope=$scopeLabel limit=$limitLabel active=${sessions.size} states=${sessionStateSummary()} target=${request.host}:${request.port}",
                )
                throw IOException("Interrupted while waiting for a pending proxy TCP connection slot.", e)
            }
        if (acquired) return

        val timeoutReason = if (reason.endsWith("s")) reason.dropLast(1) else reason
        logger(
            Log.WARN,
            "proxy session reject reason=${timeoutReason}-wait-timeout scope=$scopeLabel limit=$limitLabel timeoutMs=$timeoutMs active=${sessions.size} states=${sessionStateSummary()} target=${request.host}:${request.port}",
        )
        throw ProxyTransportException(ProxyTransportFailureReason.localServiceUnavailable, "Timed out while waiting for a pending proxy TCP connection slot.")
    }

    private fun pendingConnectBudgetFor(request: ProxyConnectRequest): PendingConnectBudget {
        val label =
            when {
                request.protocol.startsWith("dnsOver") -> "infrastructure"
                request.port == 443 -> "https"
                request.port == 80 -> "http"
                else -> "tcp"
            }
        return PendingConnectBudget(
            key = null,
            limit = null,
            label = label,
            connectTimeoutMs = connectTimeoutMs,
            waitTimeoutMs = (connectTimeoutMs / 2).coerceAtLeast(1L),
        )
    }

    private fun updateSessionState(sessionId: Int, state: UserspaceSessionState, event: String) {
        sessions.computeIfPresent(sessionId) { _, current ->
            current.copy(state = state, lastEvent = event)
        }
    }

    private fun releasePendingConnectPermit(runtime: TcpSessionRuntime) {
        runtime.releasePendingConnectPermit()
    }

    private fun removeTcpRuntime(sessionId: Int): TcpSessionRuntime? {
        val runtime = tcpRuntimeBySessionId.remove(sessionId) ?: return null
        tcpRuntimeByLocalPort.remove(runtime.localPort, runtime)
        runtime.cancelScheduledTasks()
        return runtime
    }

    private fun failTcpRuntime(
        runtime: TcpSessionRuntime,
        reason: String,
        message: String,
        closeClient: Boolean,
        logWarning: Boolean = true,
    ) {
        runtime.signalRuntimeFailure(message)
        releasePendingConnectPermit(runtime)
        removeTcpRuntime(runtime.sessionId)
        if (closeClient) {
            clientAttachments.remove(runtime.sessionId)?.close()
        }
        updateSessionState(runtime.sessionId, UserspaceSessionState.failed, message)
        if (logWarning && runtime.markFailureLogged()) {
            logger(Log.WARN, "proxy tcp fail sid=${runtime.sessionId} reason=$reason error=$message")
        }
    }

    private fun sessionStateSummary(): String {
        val counts = sessions.values.groupingBy { it.state }.eachCount()
        return UserspaceSessionState.values().joinToString(",") { state ->
            "${state.name}=${counts[state] ?: 0}"
        }
    }

    private fun pumpSessionTraffic(sessionId: Int, client: ProxyClientConnection) {
        val runtime = tcpRuntimeBySessionId[sessionId] ?: throw IOException("TCP session runtime closed before activation.")
        val attachment =
            ClientAttachment(
                client = client,
                receiveWindowBytes = receiveWindowBytes,
                onWriteFailure = { message ->
                    failTcpRuntime(runtime, reason = "client-write", message = message, closeClient = true)
                },
                onWindowDrained = {
                    queuePureAck(runtime, runtime.withLock { remoteSequenceNumber })
                },
            )
        clientAttachments[sessionId] = attachment
        awaitSessionEstablished(sessionId, connectTimeoutMs)
        logger(Log.DEBUG, "proxy session active sid=$sessionId localPort=${runtime.localPort} remote=${runtime.remoteIp}:${runtime.remotePort}")

        val input = client.input
        val buffer = ByteArray(maxTcpPayloadBytes)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            var offset = 0
            while (offset < read) {
                if (runtime.currentSendCredit() <= 0) {
                    schedulePersistProbe(runtime, buffer[offset])
                }
                val creditWait = runtime.awaitSendCredit(DATA_SEND_WINDOW_WAIT_MS)
                var credit = creditWait.credit
                if (credit <= 0) {
                    val message = creditWait.failureMessage
                        ?: if (!running || !bridge.isRunning()) {
                            "Proxy packet bridge stopped."
                        } else {
                            "Timed out waiting for remote TCP receive window sid=$sessionId outstanding=${runtime.outstandingBytes()} peerWindow=${runtime.peerWindowSize()}."
                        }
                    failTcpRuntime(runtime, reason = "send-window-timeout", message = message, closeClient = true)
                    throw IOException(message)
                }
                val alreadyAcknowledged = runtime.consumeAlreadyAcknowledgedPending(read - offset)
                if (alreadyAcknowledged > 0) {
                    offset += alreadyAcknowledged
                    credit = runtime.currentSendCredit()
                    if (offset >= read) continue
                    if (credit <= 0) continue
                }
                val chunkSize = minOf(read - offset, maxTcpPayloadBytes, credit)
                val payload = buffer.copyOfRange(offset, offset + chunkSize)
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
                            windowSize = advertisedReceiveWindow(runtime),
                            payload = payload,
                        )
                    }
                if (!bridge.queueOutboundPacket(packet)) {
                    val message = "Failed to queue outbound TCP payload."
                    failTcpRuntime(runtime, reason = "payload-queue-failed bytes=$chunkSize", message = message, closeClient = true)
                    throw IOException(message)
                }
                val startSequence =
                    runtime.withLock {
                        nextSendSequenceNumber
                    }
                runtime.withLock {
                    nextSendSequenceNumber += chunkSize
                }
                trackOutboundTcpSegment(
                    runtime = runtime,
                    packet = packet,
                    startSequence = startSequence,
                    endSequence = startSequence + chunkSize,
                    label = "payload bytes=$chunkSize",
                )
                offset += chunkSize
                updateSessionState(
                    sessionId,
                    UserspaceSessionState.established,
                    "Queued outbound TCP payload len=$chunkSize outstanding=${runtime.outstandingBytes()} peerWindow=${runtime.peerWindowSize()}",
                )
            }
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
                    windowSize = advertisedReceiveWindow(runtime),
                )
            }
        if (!bridge.queueOutboundPacket(finPacket)) {
            val message = "Failed to queue outbound TCP FIN."
            failTcpRuntime(runtime, reason = "fin-queue-failed", message = message, closeClient = true)
            throw IOException(message)
        }
        val finSequence =
            runtime.withLock {
                nextSendSequenceNumber
            }
        runtime.withLock {
            nextSendSequenceNumber += 1
        }
        trackOutboundTcpSegment(
            runtime = runtime,
            packet = finPacket,
            startSequence = finSequence,
            endSequence = finSequence + 1,
            label = "fin",
        )
        logger(Log.DEBUG, "proxy tcp close sid=$sessionId reason=fin-out")
        updateSessionState(sessionId, UserspaceSessionState.established, "Queued outbound TCP FIN; awaiting remote close")

        when (runtime.awaitRemoteCloseOrFailure(tcpFinDrainTimeoutMs)) {
            SessionWaitState.remoteClosed -> {
                updateSessionState(sessionId, UserspaceSessionState.closed, "TCP session closed cleanly")
            }
            SessionWaitState.drainTimedOut -> {
                removeTcpRuntime(sessionId)
                clientAttachments.remove(sessionId)?.close()
                updateSessionState(sessionId, UserspaceSessionState.closed, "TCP session closed after FIN drain timeout")
                logger(Log.DEBUG, "proxy tcp close sid=$sessionId reason=fin-drain-timeout timeoutMs=$tcpFinDrainTimeoutMs")
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

    private fun trackOutboundTcpSegment(
        runtime: TcpSessionRuntime,
        packet: ByteArray,
        startSequence: Long,
        endSequence: Long,
        label: String,
    ) {
        runtime.recordOutstandingSegment(
            OutstandingTcpSegment(
                startSequence = startSequence,
                endSequence = endSequence,
                packet = packet,
                label = label,
                firstSentAtMs = System.currentTimeMillis(),
                lastSentAtMs = System.currentTimeMillis(),
            ),
        )
        scheduleOutboundTcpRetransmit(runtime, startSequence)
    }

    private fun scheduleOutboundTcpRetransmit(
        runtime: TcpSessionRuntime,
        startSequence: Long,
    ) {
        val delayMs = runtime.currentRtoMs()
        val task =
            tcpScheduler.schedule(
                {
                    if (runtime.outstandingSegmentForRetransmit(startSequence) == null) return@schedule
                    if (!running || !bridge.isRunning()) return@schedule
                    retransmitOutstandingTcpSegment(runtime, startSequence, reason = "rto")
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        runtime.attachOutstandingRetransmit(startSequence, task)
    }

    private fun retransmitOutstandingTcpSegment(runtime: TcpSessionRuntime, startSequence: Long, reason: String) {
        val segment = runtime.outstandingSegmentForRetransmit(startSequence) ?: return
        if (!running || !bridge.isRunning()) return
        if (bridge.queueOutboundPacket(segment.packet)) {
            val attempt = runtime.markOutstandingRetransmitted(startSequence)
            logger(
                Log.DEBUG,
                "proxy tcp retransmit sid=${runtime.sessionId} seq=${segment.startSequence} end=${segment.endSequence} attempt=$attempt reason=$reason ${segment.label}",
            )
            scheduleOutboundTcpRetransmit(runtime, startSequence)
        } else {
            val message = "Failed to queue outbound TCP retransmit."
            failTcpRuntime(runtime, reason = "payload-retransmit-queue-failed", message = message, closeClient = true)
        }
    }

    private fun schedulePersistProbe(runtime: TcpSessionRuntime, payloadByte: Byte) {
        val delayMs = runtime.preparePersistProbeDelay() ?: return
        val task =
            tcpScheduler.schedule(
                {
                    if (!running || !bridge.isRunning()) return@schedule
                    if (runtime.currentSendCredit() > 0) {
                        runtime.cancelPersistProbe()
                        return@schedule
                    }
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
                                windowSize = advertisedReceiveWindow(runtime),
                                payload = byteArrayOf(payloadByte),
                            )
                        }
                    if (bridge.queueOutboundPacket(packet)) {
                        logger(
                            Log.DEBUG,
                            "proxy tcp persist-probe sid=${runtime.sessionId} seq=${runtime.withLock { nextSendSequenceNumber }} peerWindow=${runtime.peerWindowSize()}",
                        )
                        runtime.markPersistProbeSent()
                        schedulePersistProbe(runtime, payloadByte)
                    } else {
                        val message = "Failed to queue TCP persist probe."
                        failTcpRuntime(runtime, reason = "persist-probe-queue-failed", message = message, closeClient = true)
                    }
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        runtime.attachPersistProbe(task)
    }

    private fun awaitSessionEstablished(sessionId: Int, timeoutMs: Long) {
        val runtime =
            tcpRuntimeBySessionId[sessionId]
                ?: sessions[sessionId]
                    ?.takeIf { it.state == UserspaceSessionState.failed }
                    ?.let { throw tcpFailureFromSessionSnapshot(it) }
                ?: throw IOException("TCP session runtime closed before activation.")
        when (runtime.awaitConnect(timeoutMs)) {
            ConnectWaitState.established -> Unit
            ConnectWaitState.failed -> {
                val message = runtime.connectFailureMessage ?: "TCP session failed to connect."
                releasePendingConnectPermit(runtime)
                updateSessionState(sessionId, UserspaceSessionState.failed, message)
                throw runtime.connectFailure(message)
            }
            ConnectWaitState.pending -> {
                val message = buildConnectTimeoutMessage(runtime)
                recordNoReplyFailureIfApplicable(runtime)
                failTcpRuntime(runtime, reason = "connect-timeout timeoutMs=$timeoutMs", message = message, closeClient = false)
                throw ProxyTransportException(ProxyTransportFailureReason.upstreamTimeout, message)
            }
        }
    }

    private fun tcpFailureFromSessionSnapshot(snapshot: UserspaceSessionSnapshot): IOException {
        val message = snapshot.lastEvent
        val normalized = message.lowercase()
        val reason =
            when {
                normalized.contains("timed out") -> ProxyTransportFailureReason.upstreamTimeout
                normalized.contains("bridge") || normalized.contains("queue") -> ProxyTransportFailureReason.localServiceUnavailable
                else -> ProxyTransportFailureReason.upstreamConnectFailed
            }
        return ProxyTransportException(reason, message)
    }

    private fun buildConnectTimeoutMessage(runtime: TcpSessionRuntime): String =
        "TCP connect timed out ${runtime.describeConnectTarget()} ${runtime.describeConnectDiagnostics()}."

    private fun rejectCachedNoReplyFailure(descriptor: ProxySessionDescriptor, remoteIp: String) {
        if (noReplyFailureCacheTtlMs <= 0L) return
        val key = NoReplyTargetKey(remoteIp, descriptor.request.port)
        val cached = noReplyFailureCache[key] ?: return
        val now = System.currentTimeMillis()
        val ageMs = now - cached.createdAtMs
        if (ageMs >= noReplyFailureCacheTtlMs) {
            noReplyFailureCache.remove(key, cached)
            return
        }
        val message =
            "Recent TCP connect had no reply through tunnel host=${descriptor.request.host} ip=$remoteIp port=${descriptor.request.port} " +
                "upstreamReply=none-through-tunnel cachedAgeMs=$ageMs ttlMs=$noReplyFailureCacheTtlMs previousSynAttempts=${cached.synAttempts}."
        logger(
            Log.WARN,
            "proxy session reject reason=upstream-no-reply-cache sid=${descriptor.sessionId} target=${descriptor.request.host}:${descriptor.request.port} " +
                "ip=$remoteIp cachedAgeMs=$ageMs ttlMs=$noReplyFailureCacheTtlMs",
        )
        throw ProxyTransportException(ProxyTransportFailureReason.upstreamTimeout, message)
    }

    private fun recordNoReplyFailureIfApplicable(runtime: TcpSessionRuntime) {
        if (noReplyFailureCacheTtlMs <= 0L || !runtime.isNoReplyThroughTunnel()) return
        val key = NoReplyTargetKey(runtime.remoteIp, runtime.remotePort)
        noReplyFailureCache[key] =
            CachedNoReplyFailure(
                createdAtMs = System.currentTimeMillis(),
                synAttempts = runtime.synAttemptCount(),
            )
    }

    private fun clearNoReplyFailure(runtime: TcpSessionRuntime) {
        if (noReplyFailureCache.isEmpty()) return
        noReplyFailureCache.remove(NoReplyTargetKey(runtime.remoteIp, runtime.remotePort))
    }

    private fun advertisedReceiveWindow(runtime: TcpSessionRuntime): Int =
        clientAttachments[runtime.sessionId]?.advertisedWindow() ?: receiveWindowBytes

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
                    windowSize = advertisedReceiveWindow(runtime),
                )
            }
        if (bridge.queueOutboundPacket(ackPacket)) {
            updateSessionState(runtime.sessionId, UserspaceSessionState.established, "Queued TCP ACK ack=$acknowledgementNumber")
        } else {
            updateSessionState(runtime.sessionId, UserspaceSessionState.failed, "Failed to queue TCP ACK ack=$acknowledgementNumber")
        }
    }

    private fun openTunneledSocket(server: ResolvedDnsServerConfig): Socket {
        val session =
            openTcpSession(
                ProxyConnectRequest(
                    host = server.resolvedIpv4,
                    port = server.port,
                    protocol = server.protocol.wireValue,
                ),
            )
        session.awaitEstablished(connectTimeoutMs)
        val listener = ServerSocket(0, 1, InetAddress.getByName(LOCALHOST_IPV4))
        try {
            val clientSocket = Socket(LOCALHOST_IPV4, listener.localPort)
            val accepted = listener.accept()
            listener.close()
            Thread(
                {
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
                },
                "dns-tunnel-session-${session.descriptor.sessionId}",
            ).start()
            return clientSocket
        } catch (e: IOException) {
            try {
                listener.close()
            } catch (_: IOException) {
            }
            session.close()
            throw e
        }
    }

    private fun sendUdpDatagram(runtime: UdpAssociationRuntime, datagram: ProxyUdpDatagram) {
        if (datagram.port !in 1..65535) {
            throw IOException("Invalid UDP port.")
        }
        if (datagram.payload.size > maxUdpPayloadBytes) {
            throw IOException("UDP payload is too large for the tunnel MTU.")
        }
        val remoteIp = resolveUdpRemoteIpv4(datagram.host)
        val addedRemote = runtime.recordRemote(remoteIp, datagram.port)
        val packet =
            UdpPacketBuilder.buildIpv4UdpPacket(
                sourceIp = clientIpv4,
                destinationIp = remoteIp,
                sourcePort = runtime.localPort,
                destinationPort = datagram.port,
                payload = datagram.payload,
            )
        if (!bridge.queueOutboundPacket(packet)) {
            val message = "Failed to queue outbound UDP payload."
            if (addedRemote) runtime.removeRemote(remoteIp, datagram.port)
            updateSessionState(runtime.sessionId, UserspaceSessionState.failed, message)
            throw IOException(message)
        }
        logger(Log.DEBUG, "proxy udp out sid=${runtime.sessionId} target=${datagram.host}:${datagram.port} ip=$remoteIp bytes=${datagram.payload.size}")
        updateSessionState(runtime.sessionId, UserspaceSessionState.established, "Queued outbound UDP payload len=${datagram.payload.size}")
    }

    private fun resolveUdpRemoteIpv4(host: String): String {
        host.toIpv4LiteralOrNull()?.let { return it }
        if (host.contains(':')) {
            throw ProxyTransportException(ProxyTransportFailureReason.networkUnreachable, "IPv6 destinations are not supported in proxy mode.")
        }
        return dnsResolver.resolve(host)
    }

    private fun allocateUdpLocalPort(): Int {
        repeat(UDP_LOCAL_PORT_SPAN) {
            val candidate = nextUdpLocalPort.getAndUpdate {
                if (it >= UDP_LOCAL_PORT_MAX) UDP_LOCAL_PORT_MIN else it + 1
            }
            if (!udpRuntimeByLocalPort.containsKey(candidate)) return candidate
        }
        throw IOException("No UDP association source ports are available.")
    }

    companion object {
        private const val TAG = "UserspaceTunnelStack"
        private const val IPV4_HEADER_LEN = 20
        private const val TCP_HEADER_LEN = 20
        private const val ICMP_TYPE_DESTINATION_UNREACHABLE = 3
        private const val ICMP_CODE_FRAGMENTATION_NEEDED = 4
        private const val MAX_TCP_OPTION_VALUE = 0xffff
        private const val DEFAULT_CLIENT_IPV4 = "10.0.0.2"
        private const val DEFAULT_LINK_MTU = 1450
        private const val DEFAULT_FAILURE_REASON = "Proxy forwarding over the packet bridge is not attached yet."
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        private val DEFAULT_MAX_TCP_SESSIONS: Int? = null
        private val DEFAULT_MAX_PENDING_TCP_CONNECTS: Int? = null
        private const val DEFAULT_SYN_PACING_INTERVAL_MS = 0L
        private const val DEFAULT_TCP_FIN_DRAIN_TIMEOUT_MS = 5_000L
        private const val DEFAULT_NO_REPLY_FAILURE_CACHE_TTL_MS = 60_000L
        private const val SYN_QUEUE_RETRY_DELAY_MS = 50L
        private const val DEFAULT_TCP_RECEIVE_WINDOW_BYTES = 64 * 1024 - 1
        private const val DATA_SEND_WINDOW_WAIT_MS = 30_000L
        private val DEFAULT_TCP_PERSIST_PROBE_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        private const val LOCALHOST_IPV4 = "127.0.0.1"
        private const val UDP_HEADER_LEN = 8
        private const val TCP_LOCAL_PORT_MIN = 30000
        private const val TCP_LOCAL_PORT_MAX = 65535
        private const val TCP_LOCAL_PORT_SPAN = TCP_LOCAL_PORT_MAX - TCP_LOCAL_PORT_MIN + 1
        private const val UDP_LOCAL_PORT_MIN = 20000
        private const val UDP_LOCAL_PORT_MAX = 29999
        private const val UDP_LOCAL_PORT_SPAN = UDP_LOCAL_PORT_MAX - UDP_LOCAL_PORT_MIN + 1
        private val DEFAULT_SYN_RETRANSMIT_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L)
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
    drainTimedOut,
    failed,
}

private data class NoReplyTargetKey(
    val ip: String,
    val port: Int,
)

private data class CachedNoReplyFailure(
    val createdAtMs: Long,
    val synAttempts: Int,
)

private data class OutstandingTcpSegment(
    val startSequence: Long,
    val endSequence: Long,
    val packet: ByteArray,
    val label: String,
    val firstSentAtMs: Long,
    var lastSentAtMs: Long,
    var retransmitted: Boolean = false,
    var retransmitAttempts: Int = 0,
    var retransmitTask: ScheduledFuture<*>? = null,
)

private data class InboundTcpSegment(
    val sequenceNumber: Long,
    val payload: ByteArray,
    val fin: Boolean,
) {
    val sequenceEnd: Long = sequenceNumber + payload.size + if (fin) 1 else 0
    val bufferedSize: Int = payload.size + if (fin) 1 else 0
}

private data class InboundDeliveryResult(
    val deliveredFin: Boolean,
    val blocked: Boolean,
    val failed: Boolean,
)

private data class TcpAckUpdate(
    val acknowledgedSegments: Int,
    val outstandingBytes: Long,
    val windowChanged: Boolean,
    val sendResumed: Boolean,
    val fastRetransmitStartSequence: Long? = null,
)

private data class TcpSendCreditWaitResult(
    val credit: Int,
    val failureMessage: String? = null,
)

private const val INITIAL_TCP_RTO_MS = 1_000L
private const val MIN_TCP_RTO_MS = 500L
private const val MAX_TCP_RTO_MS = 8_000L
private const val DUPLICATE_ACKS_FOR_FAST_RETRANSMIT = 3

private class TcpSessionRuntime(
    val sessionId: Int,
    val targetHost: String,
    val localPort: Int,
    var remoteIp: String,
    val remotePort: Int,
    val initialSequenceNumber: Long,
    val connectTimeoutMs: Long,
    private var pendingConnectLease: PendingConnectLease?,
    private val inboundReorderLimitBytes: Int,
    private val persistProbeDelaysMs: List<Long>,
) {
    var remoteSequenceNumber: Long = 0
    var handshakeAckQueued: Boolean = false
    var nextSendSequenceNumber: Long = initialSequenceNumber + 1
    var localFinQueued: Boolean = false
    var connectFailureMessage: String? = null
        private set
    var terminalFailureMessage: String? = null
        private set
    private var synAttempts: Int = 0
    private var inboundTcpSeen: Int = 0
    private var synAckMatched: Int = 0
    private var rstSeen: Int = 0
    private var icmpUnreachableSeen: Int = 0
    private var sourceMismatchDrops: Int = 0

    private var connectState: ConnectWaitState = ConnectWaitState.pending
    private var sessionWaitState: SessionWaitState = SessionWaitState.pending
    private var pendingConnectPermitReleased: Boolean = false
    private var failureLogged: Boolean = false
    private val connectLatch = CountDownLatch(1)
    private val sessionWaitLatch = CountDownLatch(1)
    private val scheduledTasks = ArrayList<ScheduledFuture<*>>()
    private val outstandingSegments = LinkedHashMap<Long, OutstandingTcpSegment>()
    private val outOfOrderInboundSegments = TreeMap<Long, InboundTcpSegment>()
    private var highestOutboundAcknowledgement: Long = initialSequenceNumber + 1
    private var peerWindowSize: Int = 65535
    private var duplicateAckNumber: Long = initialSequenceNumber + 1
    private var duplicateAckCount: Int = 0
    private var bufferedInboundBytes: Int = 0
    private var persistProbeTask: ScheduledFuture<*>? = null
    private var persistProbeDelayIndex: Int = 0
    private var srttMs: Long? = null
    private var rttvarMs: Long = INITIAL_TCP_RTO_MS / 2
    private var rtoMs: Long = INITIAL_TCP_RTO_MS

    @Synchronized
    fun <T> withLock(block: TcpSessionRuntime.() -> T): T = block()

    @Synchronized
    fun isConnectPending(): Boolean = connectState == ConnectWaitState.pending

    @Synchronized
    fun recordSynAttempt(): Int {
        synAttempts += 1
        return synAttempts
    }

    @Synchronized
    fun describeConnectTarget(): String =
        "host=$targetHost ip=$remoteIp port=$remotePort sport=$localPort synAttempts=$synAttempts"

    @Synchronized
    fun describeConnectDiagnostics(): String =
        "connectDiag=inboundTcp=$inboundTcpSeen synAck=$synAckMatched rst=$rstSeen icmpUnreachable=$icmpUnreachableSeen sourceMismatchDrops=$sourceMismatchDrops${noReplyDiagnosticSuffix()}"

    @Synchronized
    fun isNoReplyThroughTunnel(): Boolean =
        inboundTcpSeen == 0 && synAckMatched == 0 && rstSeen == 0 && icmpUnreachableSeen == 0 && sourceMismatchDrops == 0

    @Synchronized
    fun synAttemptCount(): Int = synAttempts

    private fun noReplyDiagnosticSuffix(): String =
        if (inboundTcpSeen == 0 && rstSeen == 0 && icmpUnreachableSeen == 0 && sourceMismatchDrops == 0) {
            " upstreamReply=none-through-tunnel"
        } else {
            ""
        }

    @Synchronized
    fun noteInboundTcp() {
        inboundTcpSeen += 1
    }

    @Synchronized
    fun noteSynAckMatched() {
        synAckMatched += 1
    }

    @Synchronized
    fun noteRst() {
        rstSeen += 1
    }

    @Synchronized
    fun noteIcmpUnreachable() {
        icmpUnreachableSeen += 1
    }

    @Synchronized
    fun noteSourceMismatch() {
        sourceMismatchDrops += 1
    }

    @Synchronized
    fun attachScheduledTask(task: ScheduledFuture<*>) {
        if (connectState == ConnectWaitState.pending) {
            scheduledTasks += task
        } else {
            task.cancel(true)
        }
    }

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
    fun markEstablished(establishedRemoteIp: String, establishedRemoteSequence: Long, establishedPeerWindow: Int): Boolean {
        if (connectState != ConnectWaitState.pending) return false
        handshakeAckQueued = true
        remoteIp = establishedRemoteIp
        remoteSequenceNumber = establishedRemoteSequence
        peerWindowSize = establishedPeerWindow
        connectState = ConnectWaitState.established
        cancelScheduledTasks()
        connectLatch.countDown()
        monitorNotifyAll()
        return true
    }

    @Synchronized
    fun recordOutstandingSegment(segment: OutstandingTcpSegment) {
        if (connectState != ConnectWaitState.established) return
        if (segment.endSequence <= highestOutboundAcknowledgement) return
        outstandingSegments[segment.startSequence] = segment
    }

    @Synchronized
    fun attachOutstandingRetransmit(startSequence: Long, task: ScheduledFuture<*>) {
        val segment = outstandingSegments[startSequence]
        if (segment == null || connectState != ConnectWaitState.established) {
            task.cancel(true)
            return
        }
        segment.retransmitTask = task
    }

    @Synchronized
    fun outstandingSegmentForRetransmit(startSequence: Long): OutstandingTcpSegment? {
        if (connectState != ConnectWaitState.established) return null
        return outstandingSegments[startSequence]
    }

    @Synchronized
    fun acknowledgeOutbound(acknowledgementNumber: Long, advertisedWindowSize: Int): TcpAckUpdate {
        val previousAvailable = sendCreditLocked()
        val previousWindow = peerWindowSize
        val previousHighestAck = highestOutboundAcknowledgement
        peerWindowSize = advertisedWindowSize
        if (peerWindowSize > 0) cancelPersistProbeLocked()
        highestOutboundAcknowledgement = maxOf(highestOutboundAcknowledgement, acknowledgementNumber)
        var fastRetransmitStartSequence: Long? = null
        if (outstandingSegments.isEmpty()) {
            val available = sendCreditLocked()
            if (available > previousAvailable) monitorNotifyAll()
            return TcpAckUpdate(
                acknowledgedSegments = 0,
                outstandingBytes = outstandingBytesLocked(),
                windowChanged = previousWindow != peerWindowSize,
                sendResumed = previousAvailable <= 0 && available > 0,
                fastRetransmitStartSequence = null,
            )
        }
        val acknowledged = outstandingSegments.values.filter { it.endSequence <= acknowledgementNumber }
        val now = System.currentTimeMillis()
        for (segment in acknowledged) {
            segment.retransmitTask?.cancel(true)
            if (!segment.retransmitted) {
                updateRtoLocked((now - segment.firstSentAtMs).coerceAtLeast(1L))
            }
            outstandingSegments.remove(segment.startSequence)
        }
        if (acknowledgementNumber > previousHighestAck) {
            duplicateAckNumber = acknowledgementNumber
            duplicateAckCount = 0
        } else if (acknowledgementNumber == previousHighestAck && acknowledged.isEmpty()) {
            if (duplicateAckNumber == acknowledgementNumber) {
                duplicateAckCount += 1
            } else {
                duplicateAckNumber = acknowledgementNumber
                duplicateAckCount = 1
            }
            if (duplicateAckCount == DUPLICATE_ACKS_FOR_FAST_RETRANSMIT) {
                fastRetransmitStartSequence = outstandingSegments.keys.firstOrNull()
            }
        }
        val available = sendCreditLocked()
        if (acknowledged.isNotEmpty() || available > previousAvailable) monitorNotifyAll()
        return TcpAckUpdate(
            acknowledgedSegments = acknowledged.size,
            outstandingBytes = outstandingBytesLocked(),
            windowChanged = previousWindow != peerWindowSize,
            sendResumed = previousAvailable <= 0 && available > 0,
            fastRetransmitStartSequence = fastRetransmitStartSequence,
        )
    }

    @Synchronized
    fun awaitSendCredit(timeoutMs: Long): TcpSendCreditWaitResult {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1L)
        while (connectState == ConnectWaitState.established && sessionWaitState == SessionWaitState.pending) {
            val credit = sendCreditLocked()
            if (credit > 0) return TcpSendCreditWaitResult(credit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return TcpSendCreditWaitResult(0)
            try {
                monitorWait(remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                val message = "Proxy session interrupted while waiting for TCP send window."
                signalRuntimeFailure(message)
                return TcpSendCreditWaitResult(0, message)
            }
        }
        val message =
            terminalFailureMessage
                ?: connectFailureMessage
                ?: when {
                    connectState != ConnectWaitState.established -> "TCP session is not established."
                    sessionWaitState != SessionWaitState.pending -> "TCP session is closed."
                    else -> null
                }
        return TcpSendCreditWaitResult(0, message)
    }

    @Synchronized
    fun currentSendCredit(): Int = sendCreditLocked().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    @Synchronized
    fun consumeAlreadyAcknowledgedPending(maxBytes: Int): Int {
        val acknowledged = (highestOutboundAcknowledgement - nextSendSequenceNumber).coerceAtLeast(0L)
        val consumed = acknowledged.coerceAtMost(maxBytes.toLong()).toInt()
        if (consumed > 0) {
            nextSendSequenceNumber += consumed
        }
        return consumed
    }

    @Synchronized
    fun outstandingBytes(): Long = outstandingBytesLocked()

    @Synchronized
    fun peerWindowSize(): Int = peerWindowSize

    @Synchronized
    fun currentRtoMs(): Long = rtoMs

    @Synchronized
    fun markOutstandingRetransmitted(startSequence: Long): Int {
        val segment = outstandingSegments[startSequence] ?: return 0
        segment.retransmitTask?.cancel(true)
        segment.retransmitted = true
        segment.retransmitAttempts += 1
        segment.lastSentAtMs = System.currentTimeMillis()
        return segment.retransmitAttempts
    }

    @Synchronized
    fun preparePersistProbeDelay(): Long? {
        if (connectState != ConnectWaitState.established || sessionWaitState != SessionWaitState.pending) return null
        if (peerWindowSize > 0 || persistProbeTask != null) return null
        return persistProbeDelaysMs[persistProbeDelayIndex.coerceAtMost(persistProbeDelaysMs.lastIndex)]
    }

    @Synchronized
    fun attachPersistProbe(task: ScheduledFuture<*>) {
        if (peerWindowSize > 0 || connectState != ConnectWaitState.established || sessionWaitState != SessionWaitState.pending) {
            task.cancel(true)
            return
        }
        persistProbeTask = task
    }

    @Synchronized
    fun markPersistProbeSent() {
        persistProbeTask = null
        if (persistProbeDelayIndex < persistProbeDelaysMs.lastIndex) {
            persistProbeDelayIndex += 1
        }
    }

    @Synchronized
    fun cancelPersistProbe() {
        cancelPersistProbeLocked()
    }

    @Synchronized
    fun bufferOutOfOrderInbound(sequenceNumber: Long, payload: ByteArray, fin: Boolean): Boolean {
        val segment = InboundTcpSegment(sequenceNumber, payload, fin)
        if (segment.sequenceEnd <= remoteSequenceNumber) return true
        if (outOfOrderInboundSegments.containsKey(sequenceNumber)) return true
        if (bufferedInboundBytes + segment.bufferedSize > inboundReorderLimitBytes) return false
        outOfOrderInboundSegments[sequenceNumber] = segment
        bufferedInboundBytes += segment.bufferedSize
        return true
    }

    @Synchronized
    fun peekContiguousBufferedInbound(): InboundTcpSegment? =
        outOfOrderInboundSegments[remoteSequenceNumber]

    @Synchronized
    fun removeBufferedInbound(sequenceNumber: Long) {
        val removed = outOfOrderInboundSegments.remove(sequenceNumber) ?: return
        bufferedInboundBytes = (bufferedInboundBytes - removed.bufferedSize).coerceAtLeast(0)
    }

    @Synchronized
    fun bufferedInboundBytes(): Int = bufferedInboundBytes

    private fun sendCreditLocked(): Long =
        (peerWindowSize.toLong() - outstandingBytesLocked()).coerceAtLeast(0L)

    private fun outstandingBytesLocked(): Long =
        outstandingSegments.values.sumOf { it.endSequence - it.startSequence }

    private fun updateRtoLocked(sampleMs: Long) {
        val currentSrtt = srttMs
        if (currentSrtt == null) {
            srttMs = sampleMs
            rttvarMs = (sampleMs / 2).coerceAtLeast(1L)
        } else {
            val delta = kotlin.math.abs(currentSrtt - sampleMs)
            rttvarMs = ((3 * rttvarMs + delta) / 4).coerceAtLeast(1L)
            srttMs = ((7 * currentSrtt + sampleMs) / 8).coerceAtLeast(1L)
        }
        rtoMs = (srttMs!! + 4 * rttvarMs).coerceIn(MIN_TCP_RTO_MS, MAX_TCP_RTO_MS)
    }

    private fun cancelPersistProbeLocked() {
        persistProbeTask?.cancel(true)
        persistProbeTask = null
        persistProbeDelayIndex = 0
    }

    @Synchronized
    fun signalConnectFailure(message: String) {
        if (connectState != ConnectWaitState.pending) return
        connectFailureMessage = message
        connectState = ConnectWaitState.failed
        cancelScheduledTasks()
        connectLatch.countDown()
        monitorNotifyAll()
    }

    @Synchronized
    fun connectFailure(message: String): IOException {
        val normalized = message.lowercase()
        val reason =
            when {
                normalized.contains("timed out") -> ProxyTransportFailureReason.upstreamTimeout
                normalized.contains("bridge") || normalized.contains("queue") -> ProxyTransportFailureReason.localServiceUnavailable
                normalized.contains("rst") || normalized.contains("reset") -> ProxyTransportFailureReason.upstreamConnectFailed
                else -> ProxyTransportFailureReason.upstreamConnectFailed
            }
        return ProxyTransportException(reason, message)
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

    fun awaitRemoteCloseOrFailure(timeoutMs: Long): SessionWaitState {
        return try {
            if (sessionWaitLatch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) {
                synchronized(this) { sessionWaitState }
            } else {
                synchronized(this) {
                    if (sessionWaitState == SessionWaitState.pending) {
                        sessionWaitState = SessionWaitState.drainTimedOut
                    }
                    sessionWaitState
                }
            }
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
            cancelScheduledTasks()
            connectLatch.countDown()
        }
        monitorNotifyAll()
        if (sessionWaitState != SessionWaitState.pending) return
        cancelOutstandingSegments()
        terminalFailureMessage = message
        sessionWaitState = SessionWaitState.failed
        sessionWaitLatch.countDown()
    }

    @Synchronized
    fun markFailureLogged(): Boolean {
        if (failureLogged) return false
        failureLogged = true
        return true
    }

    @Synchronized
    fun releasePendingConnectPermit(): Boolean {
        if (pendingConnectPermitReleased) return false
        pendingConnectPermitReleased = true
        pendingConnectLease?.release()
        pendingConnectLease = null
        return true
    }

    @Synchronized
    fun cancelScheduledTasks() {
        for (task in scheduledTasks) {
            task.cancel(true)
        }
        scheduledTasks.clear()
        cancelPersistProbeLocked()
        cancelOutstandingSegments()
        outOfOrderInboundSegments.clear()
        bufferedInboundBytes = 0
    }

    @Synchronized
    private fun cancelOutstandingSegments() {
        for (segment in outstandingSegments.values) {
            segment.retransmitTask?.cancel(true)
        }
        outstandingSegments.clear()
        monitorNotifyAll()
    }

    private fun monitorWait(timeoutMs: Long) {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (this as java.lang.Object).wait(timeoutMs)
    }

    private fun monitorNotifyAll() {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (this as java.lang.Object).notifyAll()
    }
}

private data class PendingConnectLease(
    val releases: List<() -> Unit>,
) {
    fun release() {
        releases.asReversed().forEach { it() }
    }
}

private data class PendingConnectBudget(
    val key: String?,
    val limit: Int?,
    val label: String,
    val connectTimeoutMs: Long,
    val waitTimeoutMs: Long,
)

private data class UdpRemoteEndpoint(
    val ip: String,
    val port: Int,
)

private class UdpAssociationRuntime(
    val sessionId: Int,
    val localPort: Int,
) {
    private val closed = AtomicBoolean(false)
    private val remotes = ConcurrentHashMap.newKeySet<UdpRemoteEndpoint>()
    private val receiveQueue = ArrayBlockingQueue<ProxyUdpDatagram>(RECEIVE_QUEUE_CAPACITY)

    fun recordRemote(
        ip: String,
        port: Int,
    ): Boolean = remotes.add(UdpRemoteEndpoint(ip, port))

    fun removeRemote(
        ip: String,
        port: Int,
    ) {
        remotes.remove(UdpRemoteEndpoint(ip, port))
    }

    fun acceptsRemote(
        ip: String,
        port: Int,
    ): Boolean = !closed.get() && remotes.contains(UdpRemoteEndpoint(ip, port))

    fun enqueue(datagram: ProxyUdpDatagram): Boolean {
        if (closed.get()) return false
        return receiveQueue.offer(datagram)
    }

    fun receive(timeoutMs: Long): ProxyUdpDatagram? {
        if (closed.get()) return null
        return try {
            receiveQueue.poll(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    fun close() {
        closed.set(true)
        receiveQueue.clear()
        remotes.clear()
    }

    private companion object {
        private const val RECEIVE_QUEUE_CAPACITY = 256
    }
}

private class ClientAttachment(
    client: ProxyClientConnection,
    private val receiveWindowBytes: Int,
    private val onWriteFailure: (String) -> Unit,
    private val onWindowDrained: () -> Unit,
) {
    private val socket = client.socket
    private val output: OutputStream = client.output
    private val closed = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val queuedBytes = AtomicInteger(0)
    private val writer =
        Thread(
            {
                runWriter()
            },
            "proxy-client-writer",
        ).apply {
            isDaemon = true
            start()
        }

    fun enqueue(bytes: ByteArray): Boolean {
        if (closed.get()) return false
        while (true) {
            val current = queuedBytes.get()
            if (current + bytes.size > receiveWindowBytes) return false
            if (queuedBytes.compareAndSet(current, current + bytes.size)) break
        }
        if (!queue.offer(bytes)) {
            queuedBytes.addAndGet(-bytes.size)
            return false
        }
        return true
    }

    fun advertisedWindow(): Int = (receiveWindowBytes - queuedBytes.get()).coerceIn(0, receiveWindowBytes)

    fun queuedBytes(): Int = queuedBytes.get()

    private fun runWriter() {
        try {
            while (!closed.get()) {
                val bytes =
                    try {
                        queue.take()
                    } catch (_: InterruptedException) {
                        if (closed.get()) return
                        continue
                    }
                val before = queuedBytes.get()
                output.write(bytes)
                output.flush()
                val after = queuedBytes.addAndGet(-bytes.size).coerceAtLeast(0)
                if (before == receiveWindowBytes || (before > receiveWindowBytes / 2 && after <= receiveWindowBytes / 2)) {
                    onWindowDrained()
                }
            }
        } catch (e: IOException) {
            if (!closed.get()) {
                onWriteFailure(e.message ?: "Failed writing inbound payload to client")
            }
        }
    }

    fun shutdownOutput() {
        try {
            socket.shutdownOutput()
        } catch (_: IOException) {
        }
    }

    fun close() {
        closed.set(true)
        writer.interrupt()
        queue.clear()
        queuedBytes.set(0)
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

private class BridgeUserspaceUdpAssociation(
    override val descriptor: ProxySessionDescriptor,
    private val onSend: (ProxyUdpDatagram) -> Unit,
    private val onReceive: (Long) -> ProxyUdpDatagram?,
    private val onClose: () -> Unit,
) : UserspaceUdpAssociation {
    private var closed = false

    override fun send(datagram: ProxyUdpDatagram) {
        if (closed) throw IOException("UDP association ${descriptor.sessionId} is closed.")
        onSend(datagram)
    }

    override fun receive(timeoutMs: Long): ProxyUdpDatagram? {
        if (closed) return null
        return onReceive(timeoutMs)
    }

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}
