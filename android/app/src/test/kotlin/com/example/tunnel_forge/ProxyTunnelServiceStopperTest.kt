package com.example.tunnel_forge

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyTunnelServiceStopperTest {
    @Test
    fun stopPreviousWorkerStopsNativeTunnelAndWaitsForJoin() {
        val events = CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1)
        val worker =
            Thread {
                started.countDown()
                try {
                    while (true) {
                        Thread.sleep(1_000)
                    }
                } catch (_: InterruptedException) {
                    events += "worker-interrupted"
                }
            }
        worker.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))

        ProxyTunnelServiceStopper.stopPreviousWorker(
            worker = worker,
            onStopNativeTunnel = {
                events += "native-stop"
            },
            logger = { level, message ->
                events += "log:$level:$message"
            },
            joinTimeoutMs = 1_000,
        )

        assertEquals("native-stop", events.first())
        assertTrue(events.contains("worker-interrupted"))
        assertTrue(events.any { it == "log:${Log.INFO}:Previous proxy worker joined" })
        assertTrue(!worker.isAlive)
    }

    @Test
    fun stopPreviousWorkerStillStopsNativeTunnelWithoutWorker() {
        var nativeStopCalls = 0

        ProxyTunnelServiceStopper.stopPreviousWorker(
            worker = null,
            onStopNativeTunnel = {
                nativeStopCalls += 1
            },
            logger = { _, _ -> },
            joinTimeoutMs = 100,
        )

        assertEquals(1, nativeStopCalls)
    }
}
