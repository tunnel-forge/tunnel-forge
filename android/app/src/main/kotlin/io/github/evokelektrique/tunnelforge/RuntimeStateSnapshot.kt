package io.github.evokelektrique.tunnelforge

internal object RuntimeStateSnapshot {
    fun tunnel(
        state: String,
        detail: String,
        attemptId: String,
        connectionMode: String,
        proxyExposure: ProxyExposureInfo? = null,
    ): Map<String, Any?> =
        buildMap {
            put(VpnContract.ARG_TUNNEL_STATE, state)
            put(VpnContract.ARG_TUNNEL_DETAIL, detail)
            put(VpnContract.ARG_ATTEMPT_ID, attemptId)
            put(VpnContract.ARG_CONNECTION_MODE, connectionMode)
            proxyExposure?.let { putProxyExposure(it) }
        }

    private fun MutableMap<String, Any?>.putProxyExposure(exposure: ProxyExposureInfo) {
        put(VpnContract.ARG_PROXY_EXPOSURE_ACTIVE, exposure.active)
        put(VpnContract.ARG_PROXY_EXPOSURE_BIND_ADDRESS, exposure.bindAddress)
        put(VpnContract.ARG_PROXY_EXPOSURE_DISPLAY_ADDRESS, exposure.displayAddress)
        put(VpnContract.ARG_PROXY_EXPOSURE_HTTP_PORT, exposure.httpPort)
        put(VpnContract.ARG_PROXY_EXPOSURE_SOCKS_PORT, exposure.socksPort)
        put(VpnContract.ARG_PROXY_EXPOSURE_LAN_REQUESTED, exposure.lanRequested)
        put(VpnContract.ARG_PROXY_EXPOSURE_LAN_ACTIVE, exposure.lanActive)
        put(VpnContract.ARG_PROXY_EXPOSURE_WARNING, exposure.warning ?: "")
    }
}
