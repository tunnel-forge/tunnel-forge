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
    const val ARG_PROXY_HTTP_PORT = "proxyHttpPort"
    const val ARG_PROXY_SOCKS_PORT = "proxySocksPort"
    const val ARG_PROXY_ALLOW_LAN = "proxyAllowLan"
    const val ARG_SPLIT_TUNNEL_ENABLED = "splitTunnelEnabled"
    const val ARG_SPLIT_TUNNEL_MODE = "splitTunnelMode"
    const val ARG_SPLIT_TUNNEL_INCLUSIVE_PACKAGES = "splitTunnelInclusivePackages"
    const val ARG_SPLIT_TUNNEL_EXCLUSIVE_PACKAGES = "splitTunnelExclusivePackages"

    const val LIST_VPN_CANDIDATE_APPS = "listVpnCandidateApps"

    /** Flutter passes package name as the method argument (String). Returns PNG bytes or null. */
    const val GET_APP_ICON = "getAppIcon"

    const val MODE_VPN_TUNNEL = "vpnTunnel"
    const val MODE_PROXY_ONLY = "proxyOnly"
    const val SPLIT_TUNNEL_MODE_INCLUSIVE = "inclusive"
    const val SPLIT_TUNNEL_MODE_EXCLUSIVE = "exclusive"

    /** Flutter [MethodChannel.invokeMethod] from Android -> Dart. */
    const val ON_TUNNEL_STATE = "onTunnelState"

    const val ARG_TUNNEL_STATE = "tunnelState"
    const val ARG_TUNNEL_DETAIL = "tunnelDetail"

    const val ON_ENGINE_LOG = "onEngineLog"

    const val ARG_ENGINE_LOG_LEVEL = "engineLogLevel"
    const val ARG_ENGINE_LOG_SOURCE = "engineLogSource"
    const val ARG_ENGINE_LOG_TAG = "engineLogTag"
    const val ARG_ENGINE_LOG_MESSAGE = "engineLogMessage"

    const val ON_PROXY_EXPOSURE_CHANGED = "onProxyExposureChanged"

    const val ARG_PROXY_EXPOSURE_ACTIVE = "proxyExposureActive"
    const val ARG_PROXY_EXPOSURE_BIND_ADDRESS = "proxyExposureBindAddress"
    const val ARG_PROXY_EXPOSURE_DISPLAY_ADDRESS = "proxyExposureDisplayAddress"
    const val ARG_PROXY_EXPOSURE_HTTP_PORT = "proxyExposureHttpPort"
    const val ARG_PROXY_EXPOSURE_SOCKS_PORT = "proxyExposureSocksPort"
    const val ARG_PROXY_EXPOSURE_LAN_REQUESTED = "proxyExposureLanRequested"
    const val ARG_PROXY_EXPOSURE_LAN_ACTIVE = "proxyExposureLanActive"
    const val ARG_PROXY_EXPOSURE_WARNING = "proxyExposureWarning"

    const val LOG_SOURCE_DART = "dart"
    const val LOG_SOURCE_KOTLIN = "kotlin"
    const val LOG_SOURCE_NATIVE = "native"

    const val TUNNEL_CONNECTING = "connecting"
    const val TUNNEL_CONNECTED = "connected"
    const val TUNNEL_FAILED = "failed"
    const val TUNNEL_STOPPED = "stopped"
}
