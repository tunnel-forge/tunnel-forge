package io.github.evokelektrique.tunnelforge

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLogLevelTest {
    @Test
    fun fromWireValueDefaultsToDebug() {
        assertEquals(EngineLogLevel.DEBUG, EngineLogLevel.fromWireValue(null))
        assertEquals(EngineLogLevel.DEBUG, EngineLogLevel.fromWireValue("unknown"))
        assertEquals(EngineLogLevel.DEBUG, EngineLogLevel.fromWireValue("debug"))
    }

    @Test
    fun warningLevelMatchesRequestedLadder() {
        EngineLogPolicy.update(EngineLogLevel.WARNING)

        assertFalse(VpnTunnelEvents.shouldForwardEngineLog(Log.INFO))
        assertTrue(VpnTunnelEvents.shouldForwardEngineLog(Log.WARN))
        assertTrue(VpnTunnelEvents.shouldForwardEngineLog(Log.ERROR))
        assertFalse(VpnTunnelEvents.shouldForwardEngineLog(Log.DEBUG))
    }

    @Test
    fun errorLevelIncludesInfoWarningAndErrorButNotDebug() {
        EngineLogPolicy.update(EngineLogLevel.ERROR)

        assertFalse(VpnTunnelEvents.shouldForwardEngineLog(Log.INFO))
        assertFalse(VpnTunnelEvents.shouldForwardEngineLog(Log.WARN))
        assertTrue(VpnTunnelEvents.shouldForwardEngineLog(Log.ERROR))
        assertFalse(VpnTunnelEvents.shouldForwardEngineLog(Log.DEBUG))
    }

    @Test
    fun debugLevelForwardsEverything() {
        EngineLogPolicy.update(EngineLogLevel.DEBUG)

        assertTrue(VpnTunnelEvents.shouldForwardEngineLog(Log.DEBUG))
        assertTrue(VpnTunnelEvents.shouldForwardEngineLog(Log.INFO))
        assertTrue(VpnTunnelEvents.shouldForwardEngineLog(Log.WARN))
        assertTrue(VpnTunnelEvents.shouldForwardEngineLog(Log.ERROR))
    }
}
