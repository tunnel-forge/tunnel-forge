package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelVpnServiceTest {

    @Test
    fun effectivePerAppPackagesAddsTunnelForgePackage() {
        val effective =
            TunnelVpnService.effectivePerAppPackages(
                routingMode = VpnContract.ROUTING_PER_APP_ALLOW_LIST,
                allowedPackages = listOf(" com.example.alpha ", "com.example.beta"),
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
    fun effectivePerAppPackagesDoesNotDuplicateTunnelForgePackage() {
        val effective =
            TunnelVpnService.effectivePerAppPackages(
                routingMode = VpnContract.ROUTING_PER_APP_ALLOW_LIST,
                allowedPackages =
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
    fun effectivePerAppPackagesIsEmptyOutsidePerAppMode() {
        val effective =
            TunnelVpnService.effectivePerAppPackages(
                routingMode = VpnContract.ROUTING_FULL_TUNNEL,
                allowedPackages = listOf("com.example.alpha"),
                selfPackageName = "io.github.evokelektrique.tunnelforge",
            )

        assertEquals(emptyList<String>(), effective)
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
}
