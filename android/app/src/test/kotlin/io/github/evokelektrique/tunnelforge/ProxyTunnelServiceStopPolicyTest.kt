package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyTunnelServiceStopPolicyTest {
    @Test
    fun actionStopEmitsStoppedOnlyWhenServiceWasActive() {
        assertTrue(ProxyTunnelServiceStopPolicy.shouldEmitStoppedOnActionStop(hadActiveSession = true))
        assertFalse(ProxyTunnelServiceStopPolicy.shouldEmitStoppedOnActionStop(hadActiveSession = false))
    }

    @Test
    fun bridgeReadinessFailureIsIgnoredAfterStopRequest() {
        assertFalse(ProxyTunnelServiceStopPolicy.shouldFailBridgeReadiness(stopRequested = true))
        assertTrue(ProxyTunnelServiceStopPolicy.shouldFailBridgeReadiness(stopRequested = false))
    }

    @Test
    fun stopRequestedInterruptIsSuppressed() {
        assertTrue(
            ProxyTunnelServiceStopPolicy.shouldSuppressStartupFailure(
                stopRequested = true,
                throwable = InterruptedException("shutdown"),
            ),
        )
    }

    @Test
    fun stopRequestedWorkerReplacementIsSuppressed() {
        assertTrue(
            ProxyTunnelServiceStopPolicy.shouldSuppressStartupFailure(
                stopRequested = true,
                throwable = IllegalStateException(ProxyTunnelServiceStopPolicy.WORKER_REPLACED_MESSAGE),
            ),
        )
    }

    @Test
    fun unexpectedStartupFailuresAreNotSuppressed() {
        assertFalse(
            ProxyTunnelServiceStopPolicy.shouldSuppressStartupFailure(
                stopRequested = false,
                throwable = InterruptedException("unexpected"),
            ),
        )
        assertFalse(
            ProxyTunnelServiceStopPolicy.shouldSuppressStartupFailure(
                stopRequested = true,
                throwable = IllegalStateException("boom"),
            ),
        )
    }
}
