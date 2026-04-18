package com.example.tunnel_forge

import java.net.InetAddress

object TcpPacketBuilder {
    const val TCP_FLAG_ACK = 0x10
    const val TCP_FLAG_FIN = 0x01
    const val TCP_FLAG_PSH = 0x08
    const val TCP_FLAG_RST = 0x04
    const val TCP_FLAG_SYN = 0x02

    fun buildIpv4TcpPacket(
        sourceIp: String,
        destinationIp: String,
        sourcePort: Int,
        destinationPort: Int,
        sequenceNumber: Long,
        acknowledgementNumber: Long = 0,
        flags: Int,
        mss: Int? = null,
        payload: ByteArray = byteArrayOf(),
    ): ByteArray {
        val source = InetAddress.getByName(sourceIp).address
        val destination = InetAddress.getByName(destinationIp).address
        require(source.size == 4 && destination.size == 4) { "Only IPv4 is supported" }
        require(sourcePort in 1..65535 && destinationPort in 1..65535) { "Invalid TCP port" }
        require(mss == null || mss in 1..0xffff) { "Invalid TCP MSS" }

        val ipHeaderLen = 20
        val tcpOptions = if (mss != null) byteArrayOf(0x02, 0x04, ((mss ushr 8) and 0xff).toByte(), (mss and 0xff).toByte()) else byteArrayOf()
        val tcpHeaderLen = 20 + tcpOptions.size
        val totalLen = ipHeaderLen + tcpHeaderLen + payload.size
        val packet = ByteArray(totalLen)

        packet[0] = 0x45
        packet[1] = 0x00
        writeUint16(packet, 2, totalLen)
        writeUint16(packet, 4, 0)
        writeUint16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = IpPacketParser.IP_PROTOCOL_TCP.toByte()
        source.copyInto(packet, destinationOffset = 12)
        destination.copyInto(packet, destinationOffset = 16)
        writeUint16(packet, 10, ipv4Checksum(packet, 0, ipHeaderLen))

        val tcpOffset = ipHeaderLen
        writeUint16(packet, tcpOffset, sourcePort)
        writeUint16(packet, tcpOffset + 2, destinationPort)
        writeUint32(packet, tcpOffset + 4, sequenceNumber)
        writeUint32(packet, tcpOffset + 8, acknowledgementNumber)
        packet[tcpOffset + 12] = ((tcpHeaderLen / 4) shl 4).toByte()
        packet[tcpOffset + 13] = flags.toByte()
        writeUint16(packet, tcpOffset + 14, 65535)
        writeUint16(packet, tcpOffset + 16, 0)
        writeUint16(packet, tcpOffset + 18, 0)
        if (tcpOptions.isNotEmpty()) {
            tcpOptions.copyInto(packet, destinationOffset = tcpOffset + 20)
        }
        if (payload.isNotEmpty()) {
            payload.copyInto(packet, destinationOffset = tcpOffset + tcpHeaderLen)
        }
        writeUint16(packet, tcpOffset + 16, tcpChecksum(source, destination, packet, tcpOffset, tcpHeaderLen + payload.size))
        return packet
    }

    private fun ipv4Checksum(packet: ByteArray, offset: Int, length: Int): Int = internetChecksum(packet, offset, length)

    private fun tcpChecksum(sourceIp: ByteArray, destinationIp: ByteArray, packet: ByteArray, tcpOffset: Int, tcpLength: Int): Int {
        val pseudo = ByteArray(12 + tcpLength)
        sourceIp.copyInto(pseudo, destinationOffset = 0)
        destinationIp.copyInto(pseudo, destinationOffset = 4)
        pseudo[8] = 0
        pseudo[9] = IpPacketParser.IP_PROTOCOL_TCP.toByte()
        writeUint16(pseudo, 10, tcpLength)
        packet.copyInto(pseudo, destinationOffset = 12, startIndex = tcpOffset, endIndex = tcpOffset + tcpLength)
        return internetChecksum(pseudo, 0, pseudo.size)
    }

    private fun internetChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length) {
            val hi = data[i].toInt() and 0xff
            val lo = if (i + 1 < offset + length) data[i + 1].toInt() and 0xff else 0
            sum += ((hi shl 8) or lo).toLong()
            while ((sum ushr 16) != 0L) {
                sum = (sum and 0xffff) + (sum ushr 16)
            }
            i += 2
        }
        return (sum.inv() and 0xffff).toInt()
    }

    private fun writeUint16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xff).toByte()
        buf[offset + 1] = (value and 0xff).toByte()
    }

    private fun writeUint32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 24) and 0xff).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xff).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xff).toByte()
        buf[offset + 3] = (value and 0xff).toByte()
    }
}
