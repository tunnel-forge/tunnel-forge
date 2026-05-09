package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEnvironmentInfoTest {
    @Test
    fun formatIncludesAppAndSystemFields() {
        val message =
            RuntimeEnvironmentInfo.format(
                mode = VpnContract.MODE_PROXY_ONLY,
                packageName = "io.github.evokelektrique.tunnelforge",
                versionName = "1.2.3",
                buildNumber = "42",
                androidSdk = 35,
                androidRelease = "15",
                manufacturer = "Google",
                model = "Pixel",
                device = "panther",
                supportedAbis = "arm64-v8a,armeabi-v7a",
            )

        assertTrue(message.contains("runtime info mode=proxyOnly"))
        assertTrue(message.contains("appVersion=1.2.3"))
        assertTrue(message.contains("buildNumber=42"))
        assertTrue(message.contains("androidSdk=35"))
        assertTrue(message.contains("manufacturer=Google"))
        assertTrue(message.contains("supportedAbis=arm64-v8a,armeabi-v7a"))
    }

    @Test
    fun formatUsesUnknownForBlankFields() {
        val message =
            RuntimeEnvironmentInfo.format(
                mode = "",
                packageName = "",
                versionName = "",
                buildNumber = "",
                androidSdk = 1,
                androidRelease = "",
                manufacturer = "",
                model = "",
                device = "",
                supportedAbis = "",
            )

        assertTrue(message.contains("mode=unknown"))
        assertTrue(message.contains("appVersion=unknown"))
        assertTrue(message.contains("package=unknown"))
        assertTrue(message.contains("supportedAbis=unknown"))
    }
}
