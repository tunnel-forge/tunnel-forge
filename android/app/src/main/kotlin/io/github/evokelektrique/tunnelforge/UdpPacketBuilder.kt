package io.github.evokelektrique.tunnelforge

import java.net.InetAddress

object UdpPacketBuilder {
    fun buildIpv4UdpPacket(
        sourceIp: String,
        destinationIp: String,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val source = InetAddress.getByName(sourceIp).address
        val destination = InetAddress.getByName(destinationIp).address
        require(source.size == 4 && destination.size == 4) { "Only IPv4 is supported" }
        require(sourcePort in 1..65535 && destinationPort in 1..65535) { "Invalid UDP port" }

        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val udpLength = udpHeaderLen + payload.size
        val totalLen = ipHeaderLen + udpLength
        val packet = ByteArray(totalLen)

        packet[0] = 0x45
        packet[1] = 0x00
        writeUint16(packet, 2, totalLen)
        writeUint16(packet, 4, 0)
        writeUint16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = IpPacketParser.IP_PROTOCOL_UDP.toByte()
        source.copyInto(packet, destinationOffset = 12)
        destination.copyInto(packet, destinationOffset = 16)
        writeUint16(packet, 10, ipv4Checksum(packet, 0, ipHeaderLen))

        val udpOffset = ipHeaderLen
        writeUint16(packet, udpOffset, sourcePort)
        writeUint16(packet, udpOffset + 2, destinationPort)
        writeUint16(packet, udpOffset + 4, udpLength)
        writeUint16(packet, udpOffset + 6, 0)
        if (payload.isNotEmpty()) {
            payload.copyInto(packet, destinationOffset = udpOffset + udpHeaderLen)
        }
        return packet
    }

    private fun ipv4Checksum(packet: ByteArray, offset: Int, length: Int): Int = internetChecksum(packet, offset, length)

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
}
