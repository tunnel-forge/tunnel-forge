package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelVpnServiceTest {

    @Test
    fun effectiveInclusivePackagesAddsTunnelForgePackage() {
        val effective =
            TunnelVpnService.effectiveInclusivePackages(
                splitTunnelEnabled = true,
                splitTunnelMode = VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE,
                inclusivePackages = listOf(" com.example.alpha ", "com.example.beta"),
                selfPackageName = "io.github.evokelektrique.tunnelforge",
            )

        assertEquals(
            listOf(
                "com.example.alpha",
                "com.example.beta",
                "io.github.evokelektrique.tunnelforge",
            ),
            effective,
        )
    }

    @Test
    fun effectiveInclusivePackagesDoesNotDuplicateTunnelForgePackage() {
        val effective =
            TunnelVpnService.effectiveInclusivePackages(
                splitTunnelEnabled = true,
                splitTunnelMode = VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE,
                inclusivePackages =
                    listOf(
                        "io.github.evokelektrique.tunnelforge",
                        "com.example.alpha",
                        "io.github.evokelektrique.tunnelforge",
                    ),
                selfPackageName = "io.github.evokelektrique.tunnelforge",
            )

        assertEquals(
            listOf("io.github.evokelektrique.tunnelforge", "com.example.alpha"),
            effective,
        )
    }

    @Test
    fun effectiveInclusivePackagesIsEmptyWhenDisabled() {
        val effective =
            TunnelVpnService.effectiveInclusivePackages(
                splitTunnelEnabled = false,
                splitTunnelMode = VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE,
                inclusivePackages = listOf("com.example.alpha"),
                selfPackageName = "io.github.evokelektrique.tunnelforge",
            )

        assertEquals(emptyList<String>(), effective)
    }

    @Test
    fun requestedExclusivePackagesTrimsDedupesAndSkipsSelf() {
        val requested =
            TunnelVpnService.requestedExclusivePackages(
                splitTunnelEnabled = true,
                splitTunnelMode = VpnContract.SPLIT_TUNNEL_MODE_EXCLUSIVE,
                exclusivePackages =
                    listOf(
                        " com.example.alpha ",
                        "io.github.evokelektrique.tunnelforge",
                        "com.example.alpha",
                        "com.example.beta",
                    ),
                selfPackageName = "io.github.evokelektrique.tunnelforge",
            )

        assertEquals(listOf("com.example.alpha", "com.example.beta"), requested)
    }

    @Test
    fun localProxyRuntimeConfigKeepsClientCapacityUnlimited() {
        val config =
            ProxyRuntimeConfig(
                httpEnabled = true,
                httpPort = 8080,
                socksEnabled = true,
                socksPort = 1080,
                maxConcurrentClients = 1,
            )
        val exposure =
            ProxyExposureInfo.loopback(
                httpPort = config.httpPort,
                socksPort = config.socksPort,
                lanRequested = false,
            )

        val runtimeConfig = TunnelVpnService.localProxyRuntimeConfig(config, exposure)

        assertNull(runtimeConfig.maxConcurrentClients)
        assertEquals(exposure, runtimeConfig.exposure)
    }

    @Test
    fun actionStopDoesNotEmitStoppedWhenTunnelServiceIsIdle() {
        assertFalse(
            TunnelVpnServiceStopPolicy.shouldEmitStoppedOnActionStop(
                running = false,
                hasSetupThread = false,
                hasEngineThread = false,
                hasTunInterface = false,
                hasDnsServer = false,
                hasLocalProxyRuntime = false,
            ),
        )
    }

    @Test
    fun actionStopEmitsStoppedWhenTunnelServiceHasActiveState() {
        assertTrue(
            TunnelVpnServiceStopPolicy.shouldEmitStoppedOnActionStop(
                running = false,
                hasSetupThread = false,
                hasEngineThread = true,
                hasTunInterface = false,
                hasDnsServer = false,
                hasLocalProxyRuntime = false,
            ),
        )
        assertTrue(
            TunnelVpnServiceStopPolicy.shouldEmitStoppedOnActionStop(
                running = false,
                hasSetupThread = false,
                hasEngineThread = false,
                hasTunInterface = true,
                hasDnsServer = false,
                hasLocalProxyRuntime = false,
            ),
        )
        assertTrue(
            TunnelVpnServiceStopPolicy.shouldEmitStoppedOnActionStop(
                running = false,
                hasSetupThread = true,
                hasEngineThread = false,
                hasTunInterface = false,
                hasDnsServer = false,
                hasLocalProxyRuntime = false,
            ),
        )
    }

    @Test
    fun staleStopRequestIsIgnoredWhenAttemptDoesNotMatchActiveAttempt() {
        assertTrue(
            VpnStopAttemptPolicy.shouldIgnoreStopRequest(
                requestedAttemptId = "attempt-old",
                activeAttemptId = "attempt-new",
            ),
        )
        assertFalse(
            VpnStopAttemptPolicy.shouldIgnoreStopRequest(
                requestedAttemptId = "attempt-current",
                activeAttemptId = "attempt-current",
            ),
        )
        assertFalse(
            VpnStopAttemptPolicy.shouldIgnoreStopRequest(
                requestedAttemptId = "",
                activeAttemptId = "attempt-current",
            ),
        )
    }
}
