package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyExposurePlannerTest {

    @Test
    fun lanDisabledFallsBackToLoopback() {
        val exposure =
            ProxyExposurePlanner.choose(
                httpPort = 8080,
                socksPort = 1080,
                lanRequested = false,
                candidates =
                    listOf(
                        ProxyInterfaceCandidate(
                            interfaceName = "wlan0",
                            address = "192.168.1.24",
                            transport = ProxyInterfaceTransport.WIFI,
                            isActive = true,
                            isPrivateAddress = true,
                        ),
                    ),
            )

        assertFalse(exposure.lanActive)
        assertEquals("127.0.0.1", exposure.displayAddress)
        assertEquals("HTTP 127.0.0.1:8080, SOCKS5 127.0.0.1:1080", exposure.endpointSummary())
    }

    @Test
    fun prefersHotspotAddressOverCellularUplink() {
        val exposure =
            ProxyExposurePlanner.choose(
                httpPort = 8080,
                socksPort = 1080,
                lanRequested = true,
                candidates =
                    listOf(
                        ProxyInterfaceCandidate(
                            interfaceName = "rmnet_data0",
                            address = "10.15.18.4",
                            transport = ProxyInterfaceTransport.CELLULAR,
                            isActive = true,
                            isPrivateAddress = true,
                        ),
                        ProxyInterfaceCandidate(
                            interfaceName = "ap0",
                            address = "192.168.43.1",
                            transport = ProxyInterfaceTransport.HOTSPOT,
                            isActive = false,
                            isPrivateAddress = true,
                        ),
                    ),
            )

        assertTrue(exposure.lanActive)
        assertEquals("192.168.43.1", exposure.displayAddress)
    }

    @Test
    fun fallsBackToLoopbackWhenNoShareableCandidateExists() {
        val exposure =
            ProxyExposurePlanner.choose(
                httpPort = 8080,
                socksPort = 1080,
                lanRequested = true,
                candidates =
                    listOf(
                        ProxyInterfaceCandidate(
                            interfaceName = "rmnet_data0",
                            address = "100.72.1.5",
                            transport = ProxyInterfaceTransport.CELLULAR,
                            isActive = true,
                            isPrivateAddress = false,
                        ),
                    ),
            )

        assertFalse(exposure.lanActive)
        assertEquals("127.0.0.1", exposure.displayAddress)
        assertEquals(ProxyExposurePlanner.LAN_UNAVAILABLE_WARNING, exposure.warning)
    }
}
