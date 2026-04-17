package com.example.tunnel_forge

import androidx.annotation.Keep

/** JNI `tunnel_engine`. TUN fd owned by [TunnelVpnService] (do not close from native). UDP: [TunnelVpnService.protectSocketFd] before I/O. */
@Keep
object VpnBridge {
    init {
        System.loadLibrary("tunnel_engine")
    }

    @JvmStatic
    external fun nativeRunTunnel(tunFd: Int, server: String, user: String, password: String, psk: String): Int

    /**
     * On success, fills [outClientIpv4] with four octets 0–255 (PPP IPCP local IPv4) when non-null and length ≥ 4.
     */
    @JvmStatic
    external fun nativeNegotiate(
        server: String,
        user: String,
        password: String,
        psk: String,
        outClientIpv4: IntArray?,
    ): Int

    @JvmStatic
    external fun nativeStartLoop(tunFd: Int): Int

    @JvmStatic
    external fun nativeStopTunnel()
}
