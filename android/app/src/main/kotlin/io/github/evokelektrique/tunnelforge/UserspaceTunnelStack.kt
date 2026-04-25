package io.github.evokelektrique.tunnelforge

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val maxTcpSessions: Int = DEFAULT_MAX_TCP_SESSIONS,
) : UserspaceTunnelStack {
    init {
        require(maxTcpSessions >= 1) { "TCP session limit must be at least 1" }
    }

    private val nextSessionId = AtomicInteger(1)
    private val nextLocalPort = AtomicInteger(30000)
    private val nextUdpLocalPort = AtomicInteger(20000)
    private val tcpSessionPermits = Semaphore(maxTcpSessions, true)
    private val sessions = ConcurrentHashMap<Int, UserspaceSessionSnapshot>()
    private val tcpRuntimeBySessionId = ConcurrentHashMap<Int, TcpSessionRuntime>()
    private val udpRuntimeBySessionId = ConcurrentHashMap<Int, UdpAssociationRuntime>()
    private val udpRuntimeByLocalPort = ConcurrentHashMap<Int, UdpAssociationRuntime>()
    private val clientAttachments = ConcurrentHashMap<Int, ClientAttachment>()
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
            throw IOException("Proxy packet bridge is not active.")
        }
        if (!tcpSessionPermits.tryAcquire()) {
            logger(Log.WARN, "proxy session reject reason=too-many-tcp-sessions limit=$maxTcpSessions target=${request.host}:${request.port}")
            throw IOException("Too many active proxy TCP sessions; try again shortly.")
        }
        val descriptor =
            ProxySessionDescriptor(
                sessionId = nextSessionId.getAndIncrement(),
                request = request,
                openedAtMs = System.currentTimeMillis(),
            )
        logger(Log.DEBUG, "proxy session open sid=${descriptor.sessionId} target=${request.host}:${request.port}")
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
            tcpSessionPermits.release()
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
                tcpSessionPermits.release()
            },
            failureReason = failureReason,
        )
    }

    override fun openUdpAssociation(request: ProxyConnectRequest): UserspaceUdpAssociation {
        if (!running || !bridge.isRunning()) {
            throw IOException("Proxy packet bridge is not active.")
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
        logger(Log.DEBUG, "userspace stack stop sessions=${sessions.size} runtimes=${tcpRuntimeBySessionId.size} attachments=${clientAttachments.size}")
        tcpRuntimeBySessionId.values.forEach { it.signalRuntimeFailure("Proxy packet bridge stopped.") }
        udpRuntimeBySessionId.values.forEach { it.close() }
        sessions.clear()
        tcpRuntimeBySessionId.clear()
        udpRuntimeBySessionId.clear()
        udpRuntimeByLocalPort.clear()
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
            logger(
                Log.DEBUG,
                "Dropped inbound UDP without association ${ipv4.sourceIp}:${udp.sourcePort} -> ${ipv4.destinationIp}:${udp.destinationPort} len=${udp.payloadLength}",
            )
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
            logger(Log.WARN, "proxy tcp fail sid=${runtime.sessionId} reason=rst-in")
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
                logger(Log.DEBUG, "proxy tcp established sid=${runtime.sessionId}")
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
                logger(Log.DEBUG, "proxy tcp reack sid=${runtime.sessionId} reason=duplicate ack=$expectedSequence")
                updateSessionState(
                    runtime.sessionId,
                    UserspaceSessionState.established,
                    "Ignored duplicate inbound TCP payload seq=${tcp.sequenceNumber} expected=$expectedSequence",
                )
                return
            }
            if (tcp.sequenceNumber > expectedSequence) {
                queuePureAck(runtime, expectedSequence)
                logger(Log.DEBUG, "proxy tcp reack sid=${runtime.sessionId} reason=out-of-order ack=$expectedSequence")
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
                logger(Log.DEBUG, "proxy tcp in sid=${runtime.sessionId} bytes=${tcp.payloadLength}")
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
            logger(Log.DEBUG, "proxy tcp close sid=${runtime.sessionId} reason=fin-in")
            updateSessionState(
                runtime.sessionId,
                UserspaceSessionState.established,
                "Observed TCP FIN from ${ipv4.sourceIp}:${tcp.sourcePort}; awaiting local close",
            )
        }
    }

    private fun resolveRemoteIpv4(descriptor: ProxySessionDescriptor): String {
        descriptor.request.host.toIpv4LiteralOrNull()?.let {
            logger(Log.DEBUG, "proxy resolve sid=${descriptor.sessionId} host=${descriptor.request.host} mode=literal ip=$it")
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
        logger(Log.DEBUG, "proxy resolve sid=${descriptor.sessionId} host=${descriptor.request.host} mode=dns ip=$resolved")
        return resolved
    }

    private fun queueSyntheticSyn(descriptor: ProxySessionDescriptor, remoteIp: String) {
        val localPort = nextLocalPort.getAndIncrement().coerceIn(1024, 65535)
        val sequenceNumber = descriptor.sessionId.toLong() shl 20
        val runtime =
            TcpSessionRuntime(
                sessionId = descriptor.sessionId,
                targetHost = descriptor.request.host,
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

        if (!queueSyntheticSynPacket(runtime, reason = "initial")) {
            tcpRuntimeBySessionId.remove(descriptor.sessionId, runtime)
            runtime.signalConnectFailure("Failed to queue TCP SYN into proxy packet bridge.")
            logger(Log.WARN, "proxy tcp fail sid=${descriptor.sessionId} reason=syn-queue-failed")
            updateSessionState(descriptor.sessionId, UserspaceSessionState.failed, "Failed to queue TCP SYN into proxy packet bridge")
            throw IOException("Failed to queue outbound TCP SYN into proxy packet bridge.")
        }
        startSynRetransmitter(runtime)
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
                )
            }
        if (!bridge.queueOutboundPacket(synPacket)) {
            logger(Log.WARN, "proxy tcp syn failed sid=${runtime.sessionId} reason=$reason attempt=$attempt ${runtime.describeConnectTarget()}")
            return false
        }
        logger(Log.DEBUG, "proxy tcp syn sid=${runtime.sessionId} reason=$reason attempt=$attempt ${runtime.describeConnectTarget()} mss=$advertisedMss")
        if (runtime.isConnectPending()) {
            updateSessionState(
                runtime.sessionId,
                UserspaceSessionState.opening,
                "Queued TCP SYN attempt=$attempt ${runtime.describeConnectTarget()}",
            )
        }
        return true
    }

    private fun startSynRetransmitter(runtime: TcpSessionRuntime) {
        if (synRetransmitDelaysMs.isEmpty()) return
        val thread = Thread(
            {
                for (delayMs in synRetransmitDelaysMs) {
                    if (!running || !runtime.isConnectPending()) return@Thread
                    try {
                        Thread.sleep(delayMs.coerceAtLeast(1L))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                    if (!running || !runtime.isConnectPending()) return@Thread
                    if (!queueSyntheticSynPacket(runtime, reason = "retransmit")) {
                        val message = "Failed to queue TCP SYN retransmit ${runtime.describeConnectTarget()}."
                        runtime.signalConnectFailure(message)
                        updateSessionState(runtime.sessionId, UserspaceSessionState.failed, message)
                        return@Thread
                    }
                }
            },
            "proxy-syn-retry-${runtime.sessionId}",
        )
        runtime.attachSynRetransmitThread(thread)
        thread.start()
    }

    private fun updateSessionState(sessionId: Int, state: UserspaceSessionState, event: String) {
        sessions.computeIfPresent(sessionId) { _, current ->
            current.copy(state = state, lastEvent = event)
        }
    }

    private fun pumpSessionTraffic(sessionId: Int, client: ProxyClientConnection) {
        clientAttachments[sessionId] = ClientAttachment(client)
        val runtime = tcpRuntimeBySessionId[sessionId] ?: throw IOException("TCP session runtime closed before activation.")
        logger(Log.DEBUG, "proxy session attach sid=$sessionId localPort=${runtime.localPort} remote=${runtime.remoteIp}:${runtime.remotePort}")
        awaitSessionEstablished(sessionId, connectTimeoutMs)
        logger(Log.DEBUG, "proxy session active sid=$sessionId localPort=${runtime.localPort} remote=${runtime.remoteIp}:${runtime.remotePort}")

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
            logger(Log.DEBUG, "proxy tcp out sid=$sessionId bytes=$read")
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
        logger(Log.DEBUG, "proxy tcp close sid=$sessionId reason=fin-out")
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
                val message = "TCP connect timed out ${runtime.describeConnectTarget()}."
                runtime.signalConnectFailure(message)
                tcpRuntimeBySessionId.remove(sessionId, runtime)
                clientAttachments.remove(sessionId)?.close()
                logger(Log.WARN, "proxy tcp fail sid=$sessionId reason=connect-timeout timeoutMs=$timeoutMs ${runtime.describeConnectTarget()}")
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
            throw IOException("IPv6 destinations are not supported in proxy mode.")
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
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_MAX_TCP_SESSIONS = 32
        private const val LOCALHOST_IPV4 = "127.0.0.1"
        private const val UDP_HEADER_LEN = 8
        private const val UDP_LOCAL_PORT_MIN = 20000
        private const val UDP_LOCAL_PORT_MAX = 29999
        private const val UDP_LOCAL_PORT_SPAN = UDP_LOCAL_PORT_MAX - UDP_LOCAL_PORT_MIN + 1
        private val DEFAULT_SYN_RETRANSMIT_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L)
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
    val targetHost: String,
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
    private var synAttempts: Int = 0

    private var connectState: ConnectWaitState = ConnectWaitState.pending
    private var sessionWaitState: SessionWaitState = SessionWaitState.pending
    private val connectLatch = CountDownLatch(1)
    private val sessionWaitLatch = CountDownLatch(1)
    private var synRetransmitThread: Thread? = null

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
    fun attachSynRetransmitThread(thread: Thread) {
        if (connectState == ConnectWaitState.pending) {
            synRetransmitThread = thread
        } else {
            thread.interrupt()
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
    fun markEstablished(establishedRemoteIp: String, establishedRemoteSequence: Long) {
        if (connectState != ConnectWaitState.pending) return
        handshakeAckQueued = true
        remoteIp = establishedRemoteIp
        remoteSequenceNumber = establishedRemoteSequence
        connectState = ConnectWaitState.established
        stopSynRetransmitter()
        connectLatch.countDown()
    }

    @Synchronized
    fun signalConnectFailure(message: String) {
        if (connectState != ConnectWaitState.pending) return
        connectFailureMessage = message
        connectState = ConnectWaitState.failed
        stopSynRetransmitter()
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
            stopSynRetransmitter()
            connectLatch.countDown()
        }
        if (sessionWaitState != SessionWaitState.pending) return
        terminalFailureMessage = message
        sessionWaitState = SessionWaitState.failed
        sessionWaitLatch.countDown()
    }

    private fun stopSynRetransmitter() {
        synRetransmitThread?.interrupt()
        synRetransmitThread = null
    }
}

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
