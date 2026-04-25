package io.github.evokelektrique.tunnelforge

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyTunnelLoopRunnerTest {
    @Test
    fun loopCrashEmitsFailedOnce() {
        val logs = CopyOnWriteArrayList<String>()
        val terminalEvents = CopyOnWriteArrayList<String>()
        val crashes = CopyOnWriteArrayList<Throwable>()

        ProxyTunnelLoopRunner.run(
            attemptId = "attempt-1",
            emitEngineLog = { level, message -> logs += "$level:$message" },
            startLoop = { throw IllegalStateException("boom") },
            detailForCode = { "code=$it" },
            isStopRequested = { false },
            isConnected = { true },
            emitTerminal = { attemptId, state, detail -> terminalEvents += "$attemptId:$state:$detail" },
            onLoopCrash = { crashes += it },
        )

        assertTrue(logs.any { it == "${Log.DEBUG}:attempt=attempt-1 nativeStartProxyLoop thread running" })
        assertEquals(listOf("attempt-1:${VpnContract.TUNNEL_FAILED}:boom"), terminalEvents)
        assertEquals(1, crashes.size)
        assertTrue(crashes.single() is IllegalStateException)
    }

    @Test
    fun successfulNonUserExitAfterReadyEmitsFailedTransportLoss() {
        val terminalEvents = CopyOnWriteArrayList<String>()

        ProxyTunnelLoopRunner.run(
            attemptId = "",
            emitEngineLog = { _, _ -> },
            startLoop = { 0 },
            detailForCode = { "code=$it" },
            isStopRequested = { false },
            isConnected = { true },
            emitTerminal = { attemptId, state, detail -> terminalEvents += "$attemptId:$state:$detail" },
            onLoopCrash = { throw AssertionError("Unexpected crash callback: ${it.message}") },
        )

        assertEquals(
            listOf(":${VpnContract.TUNNEL_FAILED}:Local proxy connection was lost."),
            terminalEvents,
        )
    }

    @Test
    fun manualStopSuppressesLoopTerminalEmit() {
        val terminalEvents = CopyOnWriteArrayList<String>()

        ProxyTunnelLoopRunner.run(
            attemptId = "",
            emitEngineLog = { _, _ -> },
            startLoop = { 0 },
            detailForCode = { "code=$it" },
            isStopRequested = { true },
            isConnected = { true },
            emitTerminal = { attemptId, state, detail -> terminalEvents += "$attemptId:$state:$detail" },
            onLoopCrash = { throw AssertionError("Unexpected crash callback: ${it.message}") },
        )

        assertTrue(terminalEvents.isEmpty())
    }
}
