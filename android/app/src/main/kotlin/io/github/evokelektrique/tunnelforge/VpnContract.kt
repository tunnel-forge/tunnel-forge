package io.github.evokelektrique.tunnelforge

/** Method channel and [connect] intent extras; keep aligned with `lib/vpn_contract.dart`. */
object VpnContract {
    const val METHOD_CHANNEL = "io.github.evokelektrique.tunnelforge/vpn"

    const val PREPARE_VPN = "prepareVpn"
    const val CONNECT = "connect"
    const val DISCONNECT = "disconnect"
    const val SET_LOG_LEVEL = "setLogLevel"

    /** Correlation id for one connect attempt (Flutter -> Android). */
    const val ARG_ATTEMPT_ID = "attemptId"
    const val ARG_LOG_LEVEL = "logLevel"

    const val ARG_SERVER = "server"
    const val ARG_USER = "user"
    const val ARG_PASSWORD = "password"
    const val ARG_PSK = "psk"
    const val ARG_DNS_AUTOMATIC = "dnsAutomatic"
    const val ARG_DNS_SERVERS = "dnsServers"
    const val ARG_DNS_SERVER_HOST = "host"
    const val ARG_DNS_SERVER_PROTOCOL = "protocol"
    const val ARG_MTU = "mtu"
    const val ARG_PROFILE_NAME = "profileName"
    const val ARG_CONNECTION_MODE = "connectionMode"
    const val ARG_PROXY_HTTP_ENABLED = "proxyHttpEnabled"
    const val ARG_PROXY_HTTP_PORT = "proxyHttpPort"
    const val ARG_PROXY_SOCKS_ENABLED = "proxySocksEnabled"
    const val ARG_PROXY_SOCKS_PORT = "proxySocksPort"

    /** `fullTunnel` or `perAppAllowList` - must match Dart `RoutingMode`. */
    const val ARG_ROUTING_MODE = "routingMode"

    const val ARG_ALLOWED_PACKAGES = "allowedPackages"

    const val LIST_VPN_CANDIDATE_APPS = "listVpnCandidateApps"

    /** Flutter passes package name as the method argument (String). Returns PNG bytes or null. */
    const val GET_APP_ICON = "getAppIcon"

    const val ROUTING_FULL_TUNNEL = "fullTunnel"
    const val ROUTING_PER_APP_ALLOW_LIST = "perAppAllowList"
    const val MODE_VPN_TUNNEL = "vpnTunnel"
    const val MODE_PROXY_ONLY = "proxyOnly"

    /** Flutter [MethodChannel.invokeMethod] from Android -> Dart. */
    const val ON_TUNNEL_STATE = "onTunnelState"

    const val ARG_TUNNEL_STATE = "tunnelState"
    const val ARG_TUNNEL_DETAIL = "tunnelDetail"

    const val ON_ENGINE_LOG = "onEngineLog"

    const val ARG_ENGINE_LOG_LEVEL = "engineLogLevel"
    const val ARG_ENGINE_LOG_SOURCE = "engineLogSource"
    const val ARG_ENGINE_LOG_TAG = "engineLogTag"
    const val ARG_ENGINE_LOG_MESSAGE = "engineLogMessage"

    const val LOG_SOURCE_DART = "dart"
    const val LOG_SOURCE_KOTLIN = "kotlin"
    const val LOG_SOURCE_NATIVE = "native"

    const val TUNNEL_CONNECTING = "connecting"
    const val TUNNEL_CONNECTED = "connected"
    const val TUNNEL_FAILED = "failed"
    const val TUNNEL_STOPPED = "stopped"
}
