package com.example.tunnel_forge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier

/**
 * JNI peers must stay visible to reflection with the names native code expects.
 * Widget/integration tests mock the VPN channel, so they never exercise this path.
 * Release shrinking is covered by `android/app/proguard-rules.pro` and `flutter build apk`, not this debug APK.
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

        val run =
            Class.forName("com.example.tunnel_forge.VpnBridge").getDeclaredMethod(
                "nativeRunTunnel",
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
            )
        assertTrue(Modifier.isStatic(run.modifiers))
        assertTrue(Modifier.isNative(run.modifiers))
    }
}
