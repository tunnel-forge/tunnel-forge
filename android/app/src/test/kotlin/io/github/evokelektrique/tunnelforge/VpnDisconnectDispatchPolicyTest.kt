package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnDisconnectDispatchPolicyTest {
    @Test
    fun proxyOnlyStopsOnlyProxyService() {
        val targets = VpnDisconnectDispatchPolicy.targetsForConnectionMode(VpnContract.MODE_PROXY_ONLY)

        assertFalse(targets.stopVpn)
        assertTrue(targets.stopProxy)
    }

    @Test
    fun vpnTunnelStopsOnlyVpnService() {
        val targets = VpnDisconnectDispatchPolicy.targetsForConnectionMode(VpnContract.MODE_VPN_TUNNEL)

        assertTrue(targets.stopVpn)
        assertFalse(targets.stopProxy)
    }

    @Test
    fun missingOrUnknownModeStopsBothServicesForCompatibility() {
        val missing = VpnDisconnectDispatchPolicy.targetsForConnectionMode(null)
        val unknown = VpnDisconnectDispatchPolicy.targetsForConnectionMode("unexpected")

        assertTrue(missing.stopVpn)
        assertTrue(missing.stopProxy)
        assertTrue(unknown.stopVpn)
        assertTrue(unknown.stopProxy)
    }
}
