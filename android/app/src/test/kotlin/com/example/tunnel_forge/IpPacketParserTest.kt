package com.example.tunnel_forge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IpPacketParserTest {

    @Test
    fun parsesIpv4TcpPacket() {
        val packet =
            byteArrayOf(
                0x45,
                0x00,
                0x00,
                0x28,
                0x00,
                0x01,
                0x00,
                0x00,
                0x40,
                0x06,
                0x00,
                0x00,
                10,
                0,
                0,
                1,
                93.toByte(),
                184.toByte(),
                216.toByte(),
                34,
                0x30,
                0x39,
                0x01,
                0xbb.toByte(),
                0x00,
                0x00,
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x00,
                0x50,
                0x02,
                0x20,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            )

        val ipv4 = IpPacketParser.parseIpv4(packet)
        assertNotNull(ipv4)
        assertEquals("10.0.0.1", ipv4!!.sourceIp)
        assertEquals("93.184.216.34", ipv4.destinationIp)
        val tcp = IpPacketParser.parseTcp(packet, ipv4)
        assertNotNull(tcp)
        assertEquals(12345, tcp!!.sourcePort)
        assertEquals(443, tcp.destinationPort)
        assertEquals(0x02, tcp.flags)
    }

    @Test
    fun rejectsNonIpv4Packet() {
        val packet = byteArrayOf(0x60, 0x00, 0x00, 0x00)
        assertNull(IpPacketParser.parseIpv4(packet))
    }

    @Test
    fun parsesIpv4UdpPacket() {
        val packet =
            UdpPacketBuilder.buildIpv4UdpPacket(
                sourceIp = "10.0.0.1",
                destinationIp = "1.1.1.1",
                sourcePort = 53000,
                destinationPort = 53,
                payload = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            )

        val ipv4 = IpPacketParser.parseIpv4(packet)
        assertNotNull(ipv4)
        assertEquals("10.0.0.1", ipv4!!.sourceIp)
        assertEquals("1.1.1.1", ipv4.destinationIp)
        val udp = IpPacketParser.parseUdp(packet, ipv4)
        assertNotNull(udp)
        assertEquals(53000, udp!!.sourcePort)
        assertEquals(53, udp.destinationPort)
        assertEquals(4, udp.payloadLength)
    }
}
