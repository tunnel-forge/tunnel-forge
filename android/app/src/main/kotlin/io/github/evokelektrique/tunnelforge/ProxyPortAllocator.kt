package io.github.evokelektrique.tunnelforge

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.random.Random

internal data class ProxyPortSelection(
    val httpPort: Int,
    val socksPort: Int,
    val sequentialFallbackUsed: Boolean,
    val randomFallbackUsed: Boolean,
) {
    fun differsFrom(requestedHttpPort: Int, requestedSocksPort: Int): Boolean =
        httpPort != requestedHttpPort || socksPort != requestedSocksPort
}

internal object ProxyPortAllocator {
    const val SEQUENTIAL_CHECKS = 5
    const val RANDOM_CHECKS = 5
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535
    private const val LISTENER_BACKLOG = 1024
    private const val RANDOM_MIN_PORT = 20_000
    private const val RANDOM_MAX_PORT = 60_000

    fun chooseSequential(
        requestedHttpPort: Int,
        requestedSocksPort: Int,
        bindHosts: List<String>,
        isPortAvailable: (Int, List<String>) -> Boolean = ::isAvailableOnAllHosts,
    ): ProxyPortSelection? {
        val httpPort =
            firstSequentialPort(
                requestedPort = requestedHttpPort,
                reservedPorts = emptySet(),
                bindHosts = bindHosts,
                isPortAvailable = isPortAvailable,
            ) ?: return null
        val socksPort =
            firstSequentialPort(
                requestedPort = requestedSocksPort,
                reservedPorts = setOf(httpPort),
                bindHosts = bindHosts,
                isPortAvailable = isPortAvailable,
            ) ?: return null
        return ProxyPortSelection(
            httpPort = httpPort,
            socksPort = socksPort,
            sequentialFallbackUsed = httpPort != requestedHttpPort || socksPort != requestedSocksPort,
            randomFallbackUsed = false,
        )
    }

    fun choosePreferredThenRandom(
        requestedHttpPort: Int,
        requestedSocksPort: Int,
        bindHosts: List<String>,
        random: Random = Random.Default,
        isPortAvailable: (Int, List<String>) -> Boolean = ::isAvailableOnAllHosts,
    ): ProxyPortSelection? {
        if (
            requestedHttpPort != requestedSocksPort &&
            requestedHttpPort in MIN_PORT..MAX_PORT &&
            requestedSocksPort in MIN_PORT..MAX_PORT &&
            isPortAvailable(requestedHttpPort, bindHosts) &&
            isPortAvailable(requestedSocksPort, bindHosts)
        ) {
            return ProxyPortSelection(
                httpPort = requestedHttpPort,
                socksPort = requestedSocksPort,
                sequentialFallbackUsed = false,
                randomFallbackUsed = false,
            )
        }

        repeat(RANDOM_CHECKS) {
            val httpPort = random.nextInt(RANDOM_MIN_PORT, RANDOM_MAX_PORT + 1)
            if (!isPortAvailable(httpPort, bindHosts)) return@repeat
            val socksPort = random.nextInt(RANDOM_MIN_PORT, RANDOM_MAX_PORT + 1)
            if (socksPort == httpPort || !isPortAvailable(socksPort, bindHosts)) return@repeat
            return ProxyPortSelection(
                httpPort = httpPort,
                socksPort = socksPort,
                sequentialFallbackUsed = false,
                randomFallbackUsed = true,
            )
        }
        return null
    }

    private fun firstSequentialPort(
        requestedPort: Int,
        reservedPorts: Set<Int>,
        bindHosts: List<String>,
        isPortAvailable: (Int, List<String>) -> Boolean,
    ): Int? {
        for (offset in 0 until SEQUENTIAL_CHECKS) {
            val candidate = requestedPort + offset
            if (candidate !in MIN_PORT..MAX_PORT) continue
            if (candidate in reservedPorts) continue
            if (isPortAvailable(candidate, bindHosts)) return candidate
        }
        return null
    }

    private fun isAvailableOnAllHosts(port: Int, bindHosts: List<String>): Boolean {
        val sockets = mutableListOf<ServerSocket>()
        return try {
            for (bindHost in bindHosts.distinct()) {
                val socket = ServerSocket(port, LISTENER_BACKLOG, InetAddress.getByName(bindHost)).apply {
                    reuseAddress = true
                }
                sockets += socket
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            for (socket in sockets) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
        }
    }
}
