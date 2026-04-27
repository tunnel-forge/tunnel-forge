package io.github.evokelektrique.tunnelforge

import androidx.annotation.Keep

/** JNI `tunnel_engine`. TUN fd owned by [TunnelVpnService] (do not close from native). UDP: [TunnelVpnService.protectSocketFd] before I/O. */
@Keep
object VpnBridge {
    init {
        System.loadLibrary("tunnel_engine")
    }

    @JvmStatic
    external fun nativeRunTunnel(tunFd: Int, server: String, user: String, password: String, psk: String, tunMtu: Int): Int

    /**
     * On success, fills [outClientIpv4] with four octets 0–255 (PPP IPCP local IPv4) when non-null and length ≥ 4.
     */
    @JvmStatic
    external fun nativeNegotiate(
        server: String,
        user: String,
        password: String,
        psk: String,
        tunMtu: Int,
        outClientIpv4: IntArray?,
        outPrimaryDnsIpv4: IntArray?,
        outSecondaryDnsIpv4: IntArray?,
    ): Int

    @JvmStatic
    external fun nativeSetSocketProtectionEnabled(enabled: Boolean)

    @JvmStatic
    external fun nativeStartLoop(tunFd: Int): Int

    @JvmStatic
    external fun nativeStartProxyLoop(): Int

    @JvmStatic
    external fun nativeIsProxyPacketBridgeActive(): Boolean

    @JvmStatic
    external fun nativeQueueProxyOutboundPacket(packet: ByteArray): Int

    @JvmStatic
    external fun nativeReadProxyInboundPacket(maxLen: Int): ByteArray?

    @JvmStatic
    external fun nativeSetVpnDnsInterceptIpv4(ipv4: String?): Int

    @JvmStatic
    external fun nativeReadVpnDnsQuery(maxLen: Int): ByteArray?

    @JvmStatic
    external fun nativeQueueVpnDnsResponse(packet: ByteArray): Int

    @JvmStatic
    external fun nativeStopTunnel()
}
