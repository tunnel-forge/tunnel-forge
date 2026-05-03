package io.github.evokelektrique.tunnelforge

import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyPortAllocatorTest {
    @Test
    fun sequentialKeepsRequestedPortsWhenAvailable() {
        val selection =
            ProxyPortAllocator.chooseSequential(
                requestedHttpPort = 8080,
                requestedSocksPort = 1080,
                bindHosts = listOf("127.0.0.1"),
                isPortAvailable = { _, _ -> true },
            )

        assertNotNull(selection)
        assertEquals(8080, selection!!.httpPort)
        assertEquals(1080, selection.socksPort)
        assertFalse(selection.sequentialFallbackUsed)
        assertFalse(selection.randomFallbackUsed)
    }

    @Test
    fun sequentialIncrementsOccupiedPortsIndependently() {
        val unavailable = setOf(8080, 1080, 1081)
        val selection =
            ProxyPortAllocator.chooseSequential(
                requestedHttpPort = 8080,
                requestedSocksPort = 1080,
                bindHosts = listOf("127.0.0.1"),
                isPortAvailable = { port, _ -> port !in unavailable },
            )

        assertNotNull(selection)
        assertEquals(8081, selection!!.httpPort)
        assertEquals(1082, selection.socksPort)
        assertTrue(selection.sequentialFallbackUsed)
    }

    @Test
    fun sequentialStopsAfterFiveChecks() {
        val unavailable = (8080..8084).toSet()
        val selection =
            ProxyPortAllocator.chooseSequential(
                requestedHttpPort = 8080,
                requestedSocksPort = 1080,
                bindHosts = listOf("127.0.0.1"),
                isPortAvailable = { port, _ -> port !in unavailable },
            )

        assertNull(selection)
    }

    @Test
    fun sequentialDoesNotAssignSamePortToHttpAndSocks() {
        val selection =
            ProxyPortAllocator.chooseSequential(
                requestedHttpPort = 8080,
                requestedSocksPort = 8080,
                bindHosts = listOf("127.0.0.1"),
                isPortAvailable = { _, _ -> true },
            )

        assertNotNull(selection)
        assertEquals(8080, selection!!.httpPort)
        assertEquals(8081, selection.socksPort)
        assertNotEquals(selection.httpPort, selection.socksPort)
    }

    @Test
    fun sequentialDetectsActuallyOccupiedLoopbackPort() {
        ServerSocket(0).use { occupied ->
            val selection =
                ProxyPortAllocator.chooseSequential(
                    requestedHttpPort = occupied.localPort,
                    requestedSocksPort = 1080,
                    bindHosts = listOf("127.0.0.1"),
                )

            assertNotNull(selection)
            assertNotEquals(occupied.localPort, selection!!.httpPort)
        }
    }

    @Test
    fun preferredKeepsRequestedPortsWhenBothAreAvailable() {
        val selection =
            ProxyPortAllocator.choosePreferredThenRandom(
                requestedHttpPort = 8080,
                requestedSocksPort = 1080,
                bindHosts = listOf("127.0.0.1"),
                isPortAvailable = { _, _ -> true },
            )

        assertNotNull(selection)
        assertEquals(8080, selection!!.httpPort)
        assertEquals(1080, selection.socksPort)
        assertFalse(selection.sequentialFallbackUsed)
        assertFalse(selection.randomFallbackUsed)
    }

    @Test
    fun preferredUsesRandomPortsWhenRequestedHttpPortIsOccupied() {
        val unavailable = setOf(8080)
        val selection =
            ProxyPortAllocator.choosePreferredThenRandom(
                requestedHttpPort = 8080,
                requestedSocksPort = 1080,
                bindHosts = listOf("127.0.0.1"),
                isPortAvailable = { port, _ -> port !in unavailable },
            )

        assertNotNull(selection)
        assertTrue(selection!!.randomFallbackUsed)
        assertFalse(selection.sequentialFallbackUsed)
        assertNotEquals(8080, selection.httpPort)
        assertNotEquals(1080, selection.socksPort)
        assertNotEquals(selection.httpPort, selection.socksPort)
        assertTrue(selection.httpPort in 20_000..60_000)
        assertTrue(selection.socksPort in 20_000..60_000)
    }

    @Test
    fun preferredUsesRandomPortsWhenRequestedSocksPortIsOccupied() {
        val unavailable = setOf(1080)
        val selection =
            ProxyPortAllocator.choosePreferredThenRandom(
                requestedHttpPort = 8080,
                requestedSocksPort = 1080,
                bindHosts = listOf("127.0.0.1"),
                isPortAvailable = { port, _ -> port !in unavailable },
            )

        assertNotNull(selection)
        assertTrue(selection!!.randomFallbackUsed)
        assertFalse(selection.sequentialFallbackUsed)
        assertNotEquals(8080, selection.httpPort)
        assertNotEquals(1080, selection.socksPort)
        assertNotEquals(selection.httpPort, selection.socksPort)
        assertTrue(selection.httpPort in 20_000..60_000)
        assertTrue(selection.socksPort in 20_000..60_000)
    }

    @Test
    fun preferredRandomFallbackIsBoundedToFivePairAttempts() {
        var randomPortChecks = 0
        val selection =
            ProxyPortAllocator.choosePreferredThenRandom(
                requestedHttpPort = 8080,
                requestedSocksPort = 1080,
                bindHosts = listOf("127.0.0.1"),
                isPortAvailable = { port, _ ->
                    if (port in 20_000..60_000) randomPortChecks += 1
                    false
                },
            )

        assertNull(selection)
        assertEquals(ProxyPortAllocator.RANDOM_CHECKS, randomPortChecks)
    }
}
