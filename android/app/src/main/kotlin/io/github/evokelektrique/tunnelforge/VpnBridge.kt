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

    /*
     * gVisor bridge calls are used only by proxy-only mode. Packet direction is relative to
     * gVisor: inbound packets are injected from PPP, outbound packets are read for PPP send.
     */
    @JvmStatic
    external fun nativeGvisorStart(clientIpv4: IntArray, mtu: Int): Int

    @JvmStatic
    external fun nativeGvisorStop()

    @JvmStatic
    external fun nativeGvisorInjectInbound(packet: ByteArray): Int

    @JvmStatic
    external fun nativeGvisorReadOutbound(maxLen: Int, timeoutMs: Int): ByteArray?

    @JvmStatic
    external fun nativeGvisorTcpOpen(remoteIpv4: IntArray, port: Int, timeoutMs: Int): Int

    @JvmStatic
    external fun nativeGvisorTcpOpenCancelable(openId: Int, remoteIpv4: IntArray, port: Int, timeoutMs: Int): Int

    @JvmStatic
    external fun nativeGvisorTcpCancelOpen(openId: Int): Int

    @JvmStatic
    external fun nativeGvisorTcpRead(sessionId: Int, maxLen: Int, timeoutMs: Int): ByteArray?

    @JvmStatic
    external fun nativeGvisorTcpWrite(sessionId: Int, bytes: ByteArray, timeoutMs: Int): Int

    @JvmStatic
    external fun nativeGvisorTcpClose(sessionId: Int)

    @JvmStatic
    external fun nativeGvisorStats(): IntArray

    @JvmStatic
    external fun nativeGvisorLastOpenDiagnostics(): String

    @JvmStatic
    external fun nativeGvisorOpenDiagnostics(openId: Int): String

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
