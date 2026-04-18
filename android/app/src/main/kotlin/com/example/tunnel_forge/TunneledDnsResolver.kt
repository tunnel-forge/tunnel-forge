package com.example.tunnel_forge

import android.util.Log
import java.io.IOException
import java.net.IDN
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class TunneledDnsResolver(
    private val bridge: ProxyPacketBridge,
    private val clientIpv4: String,
    dnsServer: String,
    private val logger: (Int, String) -> Unit,
) {
    private val dnsServerIpv4 = dnsServer.trim().toIpv4LiteralOrNull()
    private val nextSourcePort = AtomicInteger(INITIAL_SOURCE_PORT)
    private val nextQueryId = AtomicInteger(1)
    private val pendingQueries = ConcurrentHashMap<Int, PendingDnsQuery>()

    @Throws(IOException::class)
    fun resolve(host: String): String {
        val dnsServerIp = dnsServerIpv4 ?: throw IOException("Proxy hostname resolution needs an IPv4 DNS server address.")
        val queryHost = canonicalizeHostname(host)
        val sourcePort = allocateSourcePort()
        val txId = nextQueryId.getAndIncrement() and 0xffff
        val query = PendingDnsQuery(txId = txId, sourcePort = sourcePort, host = queryHost, serverIp = dnsServerIp)
        logger(Log.INFO, "proxy dns start host=$host canonical=$queryHost server=$dnsServerIp txid=$txId sport=$sourcePort")
        val payload = buildQueryPayload(txId, queryHost)
        val packet =
            UdpPacketBuilder.buildIpv4UdpPacket(
                sourceIp = clientIpv4,
                destinationIp = dnsServerIp,
                sourcePort = sourcePort,
                destinationPort = DNS_PORT,
                payload = payload,
            )
        pendingQueries[sourcePort] = query
        try {
            for (attempt in 0 until MAX_QUERY_ATTEMPTS) {
                if (!bridge.queueOutboundPacket(packet)) {
                    throw IOException("Failed to queue tunneled DNS query for $queryHost.")
                }
                logger(Log.INFO, "proxy dns query host=$queryHost server=$dnsServerIp sport=$sourcePort attempt=${attempt + 1}")
                if (query.await(RESPONSE_WAIT_MS)) break
            }
            query.resolvedIpv4?.let { resolved ->
                logger(Log.INFO, "proxy dns resolved host=$queryHost ip=$resolved")
                return resolved
            }
            query.failureMessage?.let { message ->
                logger(Log.WARN, "proxy dns failed host=$queryHost reason=$message")
                throw IOException(message)
            }
            logger(Log.WARN, "proxy dns timeout host=$queryHost server=$dnsServerIp txid=$txId")
            throw IOException("Tunneled DNS lookup timed out for $queryHost.")
        } finally {
            pendingQueries.remove(sourcePort, query)
        }
    }

    fun handleInboundPacket(ipv4: ParsedIpv4Packet, udp: ParsedUdpDatagram, packet: ByteArray): Boolean {
        val query = pendingQueries[udp.destinationPort] ?: return false
        if (ipv4.sourceIp != query.serverIp || udp.sourcePort != DNS_PORT) {
            logger(
                Log.DEBUG,
                "proxy dns ignore source=${ipv4.sourceIp}:${udp.sourcePort} expected=${query.serverIp}:$DNS_PORT sport=${query.sourcePort}",
            )
            return false
        }
        val payload = packet.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        val response = parseResponse(payload, query.txId, query.host) ?: return true
        if (response.answerIpv4 != null) {
            logger(Log.INFO, "proxy dns answer host=${query.host} ip=${response.answerIpv4} txid=${query.txId}")
            query.completeSuccess(response.answerIpv4)
        } else {
            logger(Log.WARN, "proxy dns negative host=${query.host} reason=${response.failureMessage}")
            query.completeFailure(response.failureMessage ?: "Tunneled DNS lookup failed for ${query.host}.")
        }
        return true
    }

    private fun allocateSourcePort(): Int {
        while (true) {
            val candidate = nextSourcePort.getAndUpdate {
                if (it >= MAX_SOURCE_PORT) INITIAL_SOURCE_PORT else it + 1
            }
            if (!pendingQueries.containsKey(candidate)) return candidate
        }
    }

    @Throws(IOException::class)
    private fun canonicalizeHostname(host: String): String {
        val trimmed = host.trim().trimEnd('.')
        if (trimmed.isEmpty()) {
            throw IOException("Hostname is empty.")
        }
        if (trimmed.contains(':')) {
            throw IOException("IPv6 destinations are not supported in proxy mode.")
        }
        return try {
            IDN.toASCII(trimmed).lowercase()
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid hostname: $host")
        }
    }

    private fun buildQueryPayload(txId: Int, host: String): ByteArray {
        val labels = host.split('.')
        val nameLength = labels.sumOf { it.length + 1 } + 1
        val payload = ByteArray(DNS_HEADER_LEN + nameLength + DNS_QUESTION_SUFFIX_LEN)
        writeUint16(payload, 0, txId)
        writeUint16(payload, 2, 0x0100)
        writeUint16(payload, 4, 1)
        var offset = DNS_HEADER_LEN
        for (label in labels) {
            val ascii = label.toByteArray(StandardCharsets.US_ASCII)
            payload[offset] = ascii.size.toByte()
            offset += 1
            ascii.copyInto(payload, destinationOffset = offset)
            offset += ascii.size
        }
        payload[offset] = 0
        offset += 1
        writeUint16(payload, offset, TYPE_A)
        writeUint16(payload, offset + 2, CLASS_IN)
        return payload
    }

    private fun parseResponse(payload: ByteArray, expectedTxId: Int, host: String): ParsedDnsResponse? {
        if (payload.size < DNS_HEADER_LEN) return ParsedDnsResponse(failureMessage = "Malformed tunneled DNS response for $host.")
        val txId = readUint16(payload, 0)
        if (txId != expectedTxId) return null
        val flags = readUint16(payload, 2)
        val isResponse = (flags and 0x8000) != 0
        if (!isResponse) return ParsedDnsResponse(failureMessage = "Malformed tunneled DNS response for $host.")
        val rcode = flags and 0x000f
        if (rcode != 0) {
            return ParsedDnsResponse(failureMessage = "DNS response code $rcode for $host.")
        }
        val questionCount = readUint16(payload, 4)
        val answerCount = readUint16(payload, 6)
        var offset = DNS_HEADER_LEN
        repeat(questionCount) {
            offset = skipName(payload, offset) ?: return ParsedDnsResponse(failureMessage = "Malformed tunneled DNS response for $host.")
            if (offset + 4 > payload.size) {
                return ParsedDnsResponse(failureMessage = "Malformed tunneled DNS response for $host.")
            }
            offset += 4
        }
        repeat(answerCount) {
            offset = skipName(payload, offset) ?: return ParsedDnsResponse(failureMessage = "Malformed tunneled DNS response for $host.")
            if (offset + 10 > payload.size) {
                return ParsedDnsResponse(failureMessage = "Malformed tunneled DNS response for $host.")
            }
            val type = readUint16(payload, offset)
            val recordClass = readUint16(payload, offset + 2)
            val rdLength = readUint16(payload, offset + 8)
            offset += 10
            if (offset + rdLength > payload.size) {
                return ParsedDnsResponse(failureMessage = "Malformed tunneled DNS response for $host.")
            }
            if (type == TYPE_A && recordClass == CLASS_IN && rdLength == 4) {
                val answer =
                    listOf(
                        payload[offset].toInt() and 0xff,
                        payload[offset + 1].toInt() and 0xff,
                        payload[offset + 2].toInt() and 0xff,
                        payload[offset + 3].toInt() and 0xff,
                    ).joinToString(".")
                return ParsedDnsResponse(answerIpv4 = answer)
            }
            offset += rdLength
        }
        return ParsedDnsResponse(failureMessage = "No IPv4 DNS answer returned for $host.")
    }

    private fun skipName(payload: ByteArray, start: Int): Int? {
        var offset = start
        var jumps = 0
        while (offset < payload.size) {
            val len = payload[offset].toInt() and 0xff
            if (len == 0) return offset + 1
            if ((len and 0xc0) == 0xc0) {
                if (offset + 1 >= payload.size) return null
                return offset + 2
            }
            if ((len and 0xc0) != 0 || len > 63) return null
            offset += 1 + len
            jumps += 1
            if (offset > payload.size || jumps > MAX_NAME_LABELS) return null
        }
        return null
    }

    private fun readUint16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xff) shl 8) or (buf[offset + 1].toInt() and 0xff)

    private fun writeUint16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xff).toByte()
        buf[offset + 1] = (value and 0xff).toByte()
    }

    private data class ParsedDnsResponse(
        val answerIpv4: String? = null,
        val failureMessage: String? = null,
    )

    private class PendingDnsQuery(
        val txId: Int,
        val sourcePort: Int,
        val host: String,
        val serverIp: String,
    ) {
        private val done = CountDownLatch(1)

        @Volatile
        var resolvedIpv4: String? = null
            private set

        @Volatile
        var failureMessage: String? = null
            private set

        fun await(timeoutMs: Long): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun completeSuccess(ipv4: String) {
            if (done.count == 0L) return
            resolvedIpv4 = ipv4
            done.countDown()
        }

        fun completeFailure(message: String) {
            if (done.count == 0L) return
            failureMessage = message
            done.countDown()
        }
    }

    private companion object {
        private const val DNS_PORT = 53
        private const val TYPE_A = 1
        private const val CLASS_IN = 1
        private const val DNS_HEADER_LEN = 12
        private const val DNS_QUESTION_SUFFIX_LEN = 4
        private const val INITIAL_SOURCE_PORT = 41000
        private const val MAX_SOURCE_PORT = 61000
        private const val MAX_QUERY_ATTEMPTS = 2
        private const val RESPONSE_WAIT_MS = 1500L
        private const val MAX_NAME_LABELS = 64
    }
}
