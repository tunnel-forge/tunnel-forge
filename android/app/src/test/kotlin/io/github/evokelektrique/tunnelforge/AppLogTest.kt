package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test
    fun renderForwardedMessageLeavesPlainLogsUntouched() {
        assertEquals("vpn ready", AppLog.renderForwardedMessage("vpn ready", null))
    }

    @Test
    fun renderForwardedMessageIncludesThrowableStackTrace() {
        val throwable = captureThrowable()

        val rendered = AppLog.renderForwardedMessage("startTunnel failed", throwable)

        assertTrue(rendered.contains("startTunnel failed"))
        assertTrue(rendered.contains("IllegalStateException: boom"))
        assertTrue(rendered.contains("captureThrowable"))
        assertTrue(rendered.contains("AppLogTest"))
    }

    private fun captureThrowable(): Throwable {
        return try {
            throw IllegalStateException("boom")
        } catch (t: Throwable) {
            t
        }
    }
}
