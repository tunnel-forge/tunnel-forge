package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeStateSnapshotTest {

    @Test
    fun tunnelSnapshotUsesConnectionModeContract() {
        val snapshot =
            RuntimeStateSnapshot.tunnel(
                state = VpnContract.TUNNEL_CONNECTED,
                detail = "ready",
                attemptId = "attempt-1",
                connectionMode = VpnContract.MODE_PROXY_ONLY,
                proxyExposure =
                    ProxyExposureInfo(
                        active = true,
                        bindAddress = "0.0.0.0",
                        displayAddress = "192.168.1.24",
                        httpPort = 18080,
                        socksPort = 11080,
                        lanRequested = true,
                        lanActive = true,
                    ),
            )

        assertEquals(VpnContract.TUNNEL_CONNECTED, snapshot[VpnContract.ARG_TUNNEL_STATE])
        assertEquals("ready", snapshot[VpnContract.ARG_TUNNEL_DETAIL])
        assertEquals("attempt-1", snapshot[VpnContract.ARG_ATTEMPT_ID])
        assertEquals(VpnContract.MODE_PROXY_ONLY, snapshot[VpnContract.ARG_CONNECTION_MODE])
        assertEquals(true, snapshot[VpnContract.ARG_PROXY_EXPOSURE_ACTIVE])
        assertEquals("0.0.0.0", snapshot[VpnContract.ARG_PROXY_EXPOSURE_BIND_ADDRESS])
        assertEquals("192.168.1.24", snapshot[VpnContract.ARG_PROXY_EXPOSURE_DISPLAY_ADDRESS])
        assertEquals(18080, snapshot[VpnContract.ARG_PROXY_EXPOSURE_HTTP_PORT])
        assertEquals(11080, snapshot[VpnContract.ARG_PROXY_EXPOSURE_SOCKS_PORT])
        assertEquals(true, snapshot[VpnContract.ARG_PROXY_EXPOSURE_LAN_REQUESTED])
        assertEquals(true, snapshot[VpnContract.ARG_PROXY_EXPOSURE_LAN_ACTIVE])
    }
}
