package io.github.evokelektrique.tunnelforge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier

/**
 * Widget/integration tests mock the VPN channel, so they never exercise JNI. These calls stay on
 * low-risk native entrypoints and should fail immediately if library loading or RegisterNatives is broken.
 */
@RunWith(AndroidJUnit4::class)
class JniEntryPointsTest {

    @Test
    fun nativeCodeCanStillResolveKotlinJniPeers() {
        val protect =
            TunnelVpnService::class.java.getDeclaredMethod(
                "protectSocketFd",
                Int::class.javaPrimitiveType,
            )
        assertTrue(Modifier.isStatic(protect.modifiers))
        protect.isAccessible = true
        assertTrue(protect.invoke(null, -1) is Boolean)

        val ready =
            TunnelVpnService::class.java.getDeclaredMethod(
                "onNativeTunnelReady",
                String::class.java,
            )
        assertTrue(Modifier.isStatic(ready.modifiers))
        ready.isAccessible = true
        assertNull(ready.invoke(null, "TUN interface ready; tunnel loop active"))
    }

    @Test
    fun nativeBridgeMethodsAreRegisteredAndCallable() {
        VpnBridge.nativeSetSocketProtectionEnabled(false)
        assertFalse(VpnBridge.nativeIsProxyPacketBridgeActive())
        assertNull(VpnBridge.nativeReadProxyInboundPacket(0))
        VpnBridge.nativeStopTunnel()
    }
}
