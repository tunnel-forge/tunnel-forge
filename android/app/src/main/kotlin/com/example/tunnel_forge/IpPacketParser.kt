package com.example.tunnel_forge

import java.net.InetAddress

data class ParsedIpv4Packet(
    val sourceIp: String,
    val destinationIp: String,
    val protocol: Int,
    val payloadOffset: Int,
    val totalLength: Int,
)

data class ParsedTcpSegment(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Long,
    val acknowledgementNumber: Long,
    val flags: Int,
    val headerLength: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

data class ParsedUdpDatagram(
    val sourcePort: Int,
    val destinationPort: Int,
    val length: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

data class ParsedIcmpPacket(
    val type: Int,
    val code: Int,
    val nextHopMtu: Int?,
    val payloadOffset: Int,
    val payloadLength: Int,
    val quotedIpv4: ParsedIpv4Packet? = null,
    val quotedTcp: ParsedTcpSegment? = null,
)

object IpPacketParser {
    const val IP_PROTOCOL_ICMP = 1
    const val IP_PROTOCOL_TCP = 6
    const val IP_PROTOCOL_UDP = 17

    fun parseIpv4(packet: ByteArray): ParsedIpv4Packet? {
        if (packet.size < 20) return null
        val version = (packet[0].toInt() ushr 4) and 0x0f
        if (version != 4) return null
        val ihlWords = packet[0].toInt() and 0x0f
        val headerLength = ihlWords * 4
        if (headerLength < 20 || packet.size < headerLength) return null
        val totalLength = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        if (totalLength < headerLength || totalLength > packet.size) return null
        val protocol = packet[9].toInt() and 0xff
        val sourceIp = InetAddress.getByAddress(packet.copyOfRange(12, 16)).hostAddress ?: return null
        val destinationIp = InetAddress.getByAddress(packet.copyOfRange(16, 20)).hostAddress ?: return null
        return ParsedIpv4Packet(
            sourceIp = sourceIp,
            destinationIp = destinationIp,
            protocol = protocol,
            payloadOffset = headerLength,
            totalLength = totalLength,
        )
    }

    fun parseTcp(packet: ByteArray, ipv4: ParsedIpv4Packet): ParsedTcpSegment? {
        if (ipv4.protocol != IP_PROTOCOL_TCP) return null
        if (packet.size < ipv4.payloadOffset + 20) return null
        val base = ipv4.payloadOffset
        val sourcePort = ((packet[base].toInt() and 0xff) shl 8) or (packet[base + 1].toInt() and 0xff)
        val destinationPort = ((packet[base + 2].toInt() and 0xff) shl 8) or (packet[base + 3].toInt() and 0xff)
        val sequenceNumber = readUint32(packet, base + 4)
        val acknowledgementNumber = readUint32(packet, base + 8)
        val headerLength = ((packet[base + 12].toInt() ushr 4) and 0x0f) * 4
        if (headerLength < 20 || packet.size < base + headerLength) return null
        val flags = packet[base + 13].toInt() and 0xff
        val payloadOffset = base + headerLength
        val payloadLength = ipv4.totalLength - ipv4.payloadOffset - headerLength
        return ParsedTcpSegment(
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            flags = flags,
            headerLength = headerLength,
            payloadOffset = payloadOffset,
            payloadLength = if (payloadLength >= 0) payloadLength else 0,
        )
    }

    fun parseUdp(packet: ByteArray, ipv4: ParsedIpv4Packet): ParsedUdpDatagram? {
        if (ipv4.protocol != IP_PROTOCOL_UDP) return null
        if (packet.size < ipv4.payloadOffset + 8) return null
        val base = ipv4.payloadOffset
        val sourcePort = ((packet[base].toInt() and 0xff) shl 8) or (packet[base + 1].toInt() and 0xff)
        val destinationPort = ((packet[base + 2].toInt() and 0xff) shl 8) or (packet[base + 3].toInt() and 0xff)
        val length = ((packet[base + 4].toInt() and 0xff) shl 8) or (packet[base + 5].toInt() and 0xff)
        if (length < 8) return null
        val payloadOffset = base + 8
        val availableLength = ipv4.totalLength - ipv4.payloadOffset
        if (length > availableLength) return null
        return ParsedUdpDatagram(
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            length = length,
            payloadOffset = payloadOffset,
            payloadLength = length - 8,
        )
    }

    fun parseIcmp(packet: ByteArray, ipv4: ParsedIpv4Packet): ParsedIcmpPacket? {
        if (ipv4.protocol != IP_PROTOCOL_ICMP) return null
        if (packet.size < ipv4.payloadOffset + 8) return null
        val base = ipv4.payloadOffset
        val type = packet[base].toInt() and 0xff
        val code = packet[base + 1].toInt() and 0xff
        val payloadOffset = base + 8
        val payloadLength = ipv4.totalLength - ipv4.payloadOffset - 8
        if (payloadLength < 0) return null
        val nextHopMtu =
            if (type == 3 && code == 4) {
                ((packet[base + 6].toInt() and 0xff) shl 8) or (packet[base + 7].toInt() and 0xff)
            } else {
                null
            }
        val quotedBytes =
            if (payloadLength > 0) {
                packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
            } else {
                null
            }
        val quotedIpv4 = quotedBytes?.let(::parseIpv4)
        val quotedTcp = if (quotedBytes != null && quotedIpv4 != null) parseTcp(quotedBytes, quotedIpv4) else null
        return ParsedIcmpPacket(
            type = type,
            code = code,
            nextHopMtu = nextHopMtu,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
            quotedIpv4 = quotedIpv4,
            quotedTcp = quotedTcp,
        )
    }

    private fun readUint32(packet: ByteArray, offset: Int): Long {
        return ((packet[offset].toLong() and 0xff) shl 24) or
            ((packet[offset + 1].toLong() and 0xff) shl 16) or
            ((packet[offset + 2].toLong() and 0xff) shl 8) or
            (packet[offset + 3].toLong() and 0xff)
    }
}
