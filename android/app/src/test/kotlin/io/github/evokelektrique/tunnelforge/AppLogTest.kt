package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test
    fun renderForwardedMessageLeavesPlainLogsUntouched() {
        assertEquals("vpn ready", AppLog.renderForwardedMessage("vpn ready", null))
    }

    @Test
    fun renderForwardedMessageRedactsStructuredSensitiveFields() {
        val rendered =
            AppLog.renderForwardedMessage(
                "connect server=vpn.example.com password=hunter2 target=secure.example.net:443 uri=https://example.com/token",
                null,
            )

        assertFalse(rendered.contains("vpn.example.com"))
        assertFalse(rendered.contains("hunter2"))
        assertFalse(rendered.contains("secure.example.net"))
        assertFalse(rendered.contains("https://example.com/token"))
        assertTrue(rendered.contains("server=[REDACTED_HOST]"))
        assertTrue(rendered.contains("password=[REDACTED]"))
        assertTrue(rendered.contains("target=[REDACTED_TARGET]:443"))
        assertTrue(rendered.contains("uri=[REDACTED_URI]"))
    }

    @Test
    fun renderForwardedMessageRedactsEntireDnsLists() {
        val rendered =
            AppLog.renderForwardedMessage(
                "connect dns=a.example[UDP],b.example[TLS] mtu=1400",
                null,
            )

        assertFalse(rendered.contains("a.example"))
        assertFalse(rendered.contains("b.example"))
        assertTrue(rendered.contains("dns=[REDACTED_HOST]"))
        assertTrue(rendered.contains("mtu=1400"))
    }

    @Test
    fun renderForwardedMessageKeepsNumericExpectedDiagnostics() {
        val rendered =
            AppLog.renderForwardedMessage(
                "Ignored out-of-order inbound TCP payload seq=7 expected=12",
                null,
            )

        assertTrue(rendered.contains("expected=12"))
    }

    @Test
    fun renderForwardedMessageStillRedactsEndpointExpectedValues() {
        val rendered =
            AppLog.renderForwardedMessage(
                "proxy dns ignore source=10.0.0.2:5353 expected=8.8.8.8:53 sport=5353",
                null,
            )

        assertFalse(rendered.contains("8.8.8.8"))
        assertTrue(rendered.contains("expected=[REDACTED_HOST]:53"))
    }

    @Test
    fun renderForwardedMessageIncludesThrowableStackTrace() {
        val throwable = captureThrowable()

        val rendered =
            AppLog.renderForwardedMessage(
                "startTunnel failed server=vpn.example.com password=hunter2",
                throwable,
            )

        assertTrue(rendered.contains("startTunnel failed"))
        assertTrue(rendered.contains("IllegalStateException: boom"))
        assertTrue(rendered.contains("captureThrowable"))
        assertTrue(rendered.contains("AppLogTest"))
        assertFalse(rendered.contains("vpn.example.com"))
        assertFalse(rendered.contains("hunter2"))
    }

    private fun captureThrowable(): Throwable {
        return try {
            throw IllegalStateException("boom")
        } catch (t: Throwable) {
            t
        }
    }
}
