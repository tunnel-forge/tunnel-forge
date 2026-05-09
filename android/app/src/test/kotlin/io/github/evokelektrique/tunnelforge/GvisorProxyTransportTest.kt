package io.github.evokelektrique.tunnelforge

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GvisorProxyTransportTest {
    @Test
    fun idleReadTimeoutDoesNotCloseSession() {
        val backend =
            FakeGvisorTcpBackend(
                openResult = 7,
                readResult = null,
            )
        val session =
            GvisorProxyTransport(
                tunneledResolver = { "93.184.216.34" },
                backend = backend,
            ).openTcpSession(ProxyConnectRequest("example.com", 443, "http-connect"))

        session.awaitConnected(1_000)

        assertNull(session.readRemoteBytes(1024, 50))
        assertEquals(0, backend.closeCount.get())
    }

    @Test
    fun remoteEofReturnsEmptyBytesAndMarksTearingDown() {
        val backend =
            FakeGvisorTcpBackend(
                openResult = 7,
                readResult = ByteArray(0),
            )
        val session =
            GvisorProxyTransport(
                tunneledResolver = { "93.184.216.34" },
                backend = backend,
            ).openTcpSession(ProxyConnectRequest("example.com", 443, "http-connect"))

        session.awaitConnected(1_000)

        assertArrayEquals(ByteArray(0), session.readRemoteBytes(1024, 50))
        assertArrayEquals(ByteArray(0), session.readRemoteBytes(1024, 50))
    }

    @Test
    fun openTimeoutCodeMapsToUpstreamTimeout() {
        val session =
            GvisorProxyTransport(
                tunneledResolver = { "93.184.216.34" },
                backend = FakeGvisorTcpBackend(openResult = -4),
            ).openTcpSession(ProxyConnectRequest("example.com", 443, "http-connect"))

        val thrown =
            runCatching { session.awaitConnected(1_000) }
                .exceptionOrNull() as ProxyTransportException

        assertEquals(ProxyTransportFailureReason.upstreamTimeout, thrown.failureReason)
    }

    @Test
    fun formatGvisorStatsIncludesOpenDiagnosticsCounters() {
        assertEquals(
            "gvisorStats running=1 activeTcp=2 outboundQueued=3 openAttempts=4 openOk=5 openFailed=6 " +
                "outboundPackets=7 inboundPackets=8 pendingOpens=9 openTimeouts=10 openImmediate=11 " +
                "openResets=12 openInternal=13 openCanceled=14 openSynOut=15 openSynAckIn=16 openRstIn=17",
            formatGvisorStats((1..17).toList().toIntArray()),
        )
    }

    @Test
    fun openFailureLogMessageCarriesNativeDiagnostics() {
        val session =
            GvisorProxyTransport(
                tunneledResolver = { "93.184.216.34" },
                backend =
                    FakeGvisorTcpBackend(
                        openResult = -4,
                        openDiagnostics = "openDiag openId=1 localPort=42000 syn=3 synAck=0 rst=0",
                    ),
            ).openTcpSession(ProxyConnectRequest("example.com", 443, "http-connect"))

        val thrown =
            runCatching { session.awaitConnected(1_000) }
                .exceptionOrNull() as ProxyTransportException

        assertEquals(ProxyTransportFailureReason.upstreamTimeout, thrown.failureReason)
        assertEquals(
            "gVisor TCP connect failed code=-4. openDiag openId=1 localPort=42000 syn=3 synAck=0 rst=0",
            thrown.message,
        )
    }

    @Test
    fun closeWhileOpenPendingCancelsNativeOpen() {
        val openEntered = CountDownLatch(1)
        val cancelCalled = CountDownLatch(1)
        val backend =
            object : FakeGvisorTcpBackend(openResult = -7) {
                override fun open(openId: Int, remoteIpv4: IntArray, port: Int, timeoutMs: Int): Int {
                    openEntered.countDown()
                    assertTrue(cancelCalled.await(2, TimeUnit.SECONDS))
                    return -7
                }

                override fun cancelOpen(openId: Int): Int {
                    super.cancelOpen(openId)
                    cancelCalled.countDown()
                    return 0
                }
            }
        val session =
            GvisorProxyTransport(
                tunneledResolver = { "93.184.216.34" },
                backend = backend,
            ).openTcpSession(ProxyConnectRequest("example.com", 443, "http-connect"))
        val thread = Thread {
            runCatching { session.awaitConnected(5_000) }
        }
        thread.start()
        assertTrue(openEntered.await(2, TimeUnit.SECONDS))

        session.close()
        thread.join(2_000)

        assertEquals(1, backend.cancelCount.get())
    }

    @Test
    fun canceledOpenCodeMapsToClientAborted() {
        val session =
            GvisorProxyTransport(
                tunneledResolver = { "93.184.216.34" },
                backend = FakeGvisorTcpBackend(openResult = -7),
            ).openTcpSession(ProxyConnectRequest("example.com", 443, "http-connect"))

        val thrown =
            runCatching { session.awaitConnected(1_000) }
                .exceptionOrNull() as ProxyTransportException

        assertEquals(ProxyTransportFailureReason.clientAborted, thrown.failureReason)
    }

    @Test
    fun gvisorReadErrorCategoriesAreExplicit() {
        assertEquals(
            GvisorTcpFailureCategory.sessionClosedLocally,
            gvisorTcpFailureCategory(IOException("gVisor TCP read failed: session closed locally.")),
        )
        assertEquals(
            GvisorTcpFailureCategory.connectionReset,
            gvisorTcpFailureCategory(IOException("gVisor TCP read failed: connection reset.")),
        )
        assertEquals(
            GvisorTcpFailureCategory.readTimeout,
            gvisorTcpFailureCategory(IOException("gVisor TCP read timed out.")),
        )
        assertEquals(
            GvisorTcpFailureCategory.internalGvisorError,
            gvisorTcpFailureCategory(IOException("gVisor TCP read failed: internal gVisor error.")),
        )
    }

    @Test
    fun gvisorWriteTimeoutCategoryIsDistinct() {
        assertEquals(
            GvisorTcpFailureCategory.writeTimeout,
            gvisorTcpFailureCategory(IOException("gVisor TCP write failed: write timeout."), writePath = true),
        )
    }

    @Test
    fun hostnamesUseTunneledResolverBeforeOpen() {
        val backend = FakeGvisorTcpBackend(openResult = 9)
        val session =
            GvisorProxyTransport(
                resolver = { error("Android resolver must not be used") },
                tunneledResolver = { host ->
                    assertEquals("blocked.example", host)
                    "203.0.113.10"
                },
                backend = backend,
            ).openTcpSession(ProxyConnectRequest("blocked.example", 443, "http-connect"))

        session.awaitConnected(1_000)

        assertArrayEquals(intArrayOf(203, 0, 113, 10), backend.lastOpenIpv4)
    }

    @Test
    fun tunneledResolverFailureMapsToDnsFailed() {
        val session =
            GvisorProxyTransport(
                tunneledResolver = { throw IOException("filtered DNS") },
                requireTunneledDns = true,
                backend = FakeGvisorTcpBackend(openResult = 9),
            ).openTcpSession(ProxyConnectRequest("blocked.example", 443, "http-connect"))

        val thrown =
            runCatching { session.awaitConnected(1_000) }
                .exceptionOrNull() as ProxyTransportException

        assertEquals(ProxyTransportFailureReason.dnsFailed, thrown.failureReason)
    }

    @Test
    fun missingRequiredTunneledResolverFailsBeforeAndroidResolver() {
        val backend = FakeGvisorTcpBackend(openResult = 9)
        val session =
            GvisorProxyTransport(
                resolver = { error("Android resolver must not be used") },
                requireTunneledDns = true,
                backend = backend,
            ).openTcpSession(ProxyConnectRequest("blocked.example", 443, "http-connect"))

        val thrown =
            runCatching { session.awaitConnected(1_000) }
                .exceptionOrNull() as ProxyTransportException

        assertEquals(ProxyTransportFailureReason.dnsFailed, thrown.failureReason)
        assertArrayEquals(intArrayOf(), backend.lastOpenIpv4)
    }

    @Test
    fun requiredTunneledDnsStillAllowsIpv4Literals() {
        val backend = FakeGvisorTcpBackend(openResult = 9)
        val session =
            GvisorProxyTransport(
                resolver = { error("Android resolver must not be used") },
                requireTunneledDns = true,
                backend = backend,
            ).openTcpSession(ProxyConnectRequest("203.0.113.77", 443, "http-connect"))

        session.awaitConnected(1_000)

        assertArrayEquals(intArrayOf(203, 0, 113, 77), backend.lastOpenIpv4)
    }

    private open class FakeGvisorTcpBackend(
        private val openResult: Int,
        private val readResult: ByteArray? = null,
        private val openDiagnostics: String = "",
    ) : GvisorTcpBackend {
        val closeCount = AtomicInteger(0)
        val cancelCount = AtomicInteger(0)
        var lastOpenIpv4: IntArray = intArrayOf()

        override fun open(openId: Int, remoteIpv4: IntArray, port: Int, timeoutMs: Int): Int {
            lastOpenIpv4 = remoteIpv4
            return openResult
        }

        override fun cancelOpen(openId: Int): Int {
            cancelCount.incrementAndGet()
            return 0
        }

        override fun read(sessionId: Int, maxLen: Int, timeoutMs: Int): ByteArray? = readResult

        override fun write(sessionId: Int, bytes: ByteArray, timeoutMs: Int): Int = bytes.size

        override fun close(sessionId: Int) {
            closeCount.incrementAndGet()
        }

        override fun openDiagnostics(openId: Int): String = openDiagnostics
    }
}
