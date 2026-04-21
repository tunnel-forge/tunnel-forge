package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnContractTest {

    @Test
    fun methodChannel() {
        assertEquals("io.github.evokelektrique.tunnelforge/vpn", VpnContract.METHOD_CHANNEL)
    }

    @Test
    fun connectExtras() {
        assertEquals("setLogLevel", VpnContract.SET_LOG_LEVEL)
        assertEquals("logLevel", VpnContract.ARG_LOG_LEVEL)
        assertEquals("server", VpnContract.ARG_SERVER)
        assertEquals("user", VpnContract.ARG_USER)
        assertEquals("password", VpnContract.ARG_PASSWORD)
        assertEquals("psk", VpnContract.ARG_PSK)
        assertEquals("dnsAutomatic", VpnContract.ARG_DNS_AUTOMATIC)
        assertEquals("dnsServers", VpnContract.ARG_DNS_SERVERS)
        assertEquals("host", VpnContract.ARG_DNS_SERVER_HOST)
        assertEquals("protocol", VpnContract.ARG_DNS_SERVER_PROTOCOL)
        assertEquals("mtu", VpnContract.ARG_MTU)
        assertEquals("profileName", VpnContract.ARG_PROFILE_NAME)
        assertEquals("connectionMode", VpnContract.ARG_CONNECTION_MODE)
        assertEquals("proxyHttpEnabled", VpnContract.ARG_PROXY_HTTP_ENABLED)
        assertEquals("proxyHttpPort", VpnContract.ARG_PROXY_HTTP_PORT)
        assertEquals("proxySocksEnabled", VpnContract.ARG_PROXY_SOCKS_ENABLED)
        assertEquals("proxySocksPort", VpnContract.ARG_PROXY_SOCKS_PORT)
        assertEquals("routingMode", VpnContract.ARG_ROUTING_MODE)
        assertEquals("allowedPackages", VpnContract.ARG_ALLOWED_PACKAGES)
        assertEquals("vpnTunnel", VpnContract.MODE_VPN_TUNNEL)
        assertEquals("proxyOnly", VpnContract.MODE_PROXY_ONLY)
        assertEquals("fullTunnel", VpnContract.ROUTING_FULL_TUNNEL)
        assertEquals("perAppAllowList", VpnContract.ROUTING_PER_APP_ALLOW_LIST)
    }

    @Test
    fun listAppsMethod() {
        assertEquals("listVpnCandidateApps", VpnContract.LIST_VPN_CANDIDATE_APPS)
    }

    @Test
    fun getAppIconMethod() {
        assertEquals("getAppIcon", VpnContract.GET_APP_ICON)
    }

    @Test
    fun engineLogChannel() {
        assertEquals("onEngineLog", VpnContract.ON_ENGINE_LOG)
        assertEquals("engineLogLevel", VpnContract.ARG_ENGINE_LOG_LEVEL)
        assertEquals("engineLogSource", VpnContract.ARG_ENGINE_LOG_SOURCE)
        assertEquals("engineLogTag", VpnContract.ARG_ENGINE_LOG_TAG)
        assertEquals("engineLogMessage", VpnContract.ARG_ENGINE_LOG_MESSAGE)
        assertEquals("dart", VpnContract.LOG_SOURCE_DART)
        assertEquals("kotlin", VpnContract.LOG_SOURCE_KOTLIN)
        assertEquals("native", VpnContract.LOG_SOURCE_NATIVE)
    }
}
