package io.github.evokelektrique.tunnelforge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTunnelSessionCoordinatorTest {
    @Test
    fun acquireReleaseTracksSingleOwner() {
        val coordinator = NativeTunnelSessionCoordinator(stopNativeTunnel = {}, logger = { _, _ -> })
        val owner = NativeTunnelOwner(VpnContract.MODE_VPN_TUNNEL, "attempt-vpn")

        assertTrue(coordinator.acquire(owner, reason = "test"))
        assertEquals(owner, coordinator.currentOwner())
        assertTrue(coordinator.release(owner, reason = "done"))
        assertNull(coordinator.currentOwner())
    }

    @Test
    fun secondOwnerStopsPreviousAndWaitsForRelease() {
        val nativeStopCalls = AtomicInteger(0)
        val coordinator =
            NativeTunnelSessionCoordinator(
                stopNativeTunnel = {
                    nativeStopCalls.incrementAndGet()
                },
                logger = { _, _ -> },
            )
        val first = NativeTunnelOwner(VpnContract.MODE_PROXY_ONLY, "attempt-proxy")
        val second = NativeTunnelOwner(VpnContract.MODE_VPN_TUNNEL, "attempt-vpn")
        val acquired = CountDownLatch(1)

        assertTrue(coordinator.acquire(first, reason = "first"))
        val waiter =
            Thread {
                if (coordinator.acquire(second, reason = "second", waitTimeoutMs = 2_000)) {
                    acquired.countDown()
                }
            }
        waiter.start()

        Thread.sleep(100)
        assertEquals(1, nativeStopCalls.get())
        assertFalse(acquired.await(50, TimeUnit.MILLISECONDS))
        assertTrue(coordinator.release(first, reason = "first stopped"))
        assertTrue(acquired.await(1, TimeUnit.SECONDS))
        waiter.join(1_000)
        assertEquals(second, coordinator.currentOwner())
    }

    @Test
    fun acquireWaitsForPendingStopBeforeTakingReleasedOwnerSlot() {
        val stopStarted = CountDownLatch(1)
        val allowStopToFinish = CountDownLatch(1)
        val coordinator =
            NativeTunnelSessionCoordinator(
                stopNativeTunnel = {
                    stopStarted.countDown()
                    assertTrue(allowStopToFinish.await(1, TimeUnit.SECONDS))
                },
                logger = { _, _ -> },
            )
        val first = NativeTunnelOwner(VpnContract.MODE_PROXY_ONLY, "attempt-proxy")
        val second = NativeTunnelOwner(VpnContract.MODE_VPN_TUNNEL, "attempt-vpn")
        val third = NativeTunnelOwner(VpnContract.MODE_PROXY_ONLY, "attempt-next")
        val secondAcquired = CountDownLatch(1)
        val thirdFinished = CountDownLatch(1)
        val thirdResult = AtomicInteger(-1)

        assertTrue(coordinator.acquire(first, reason = "first"))
        val stopper =
            Thread {
                if (coordinator.acquire(second, reason = "second", waitTimeoutMs = 2_000)) {
                    secondAcquired.countDown()
                }
            }
        stopper.start()

        assertTrue(stopStarted.await(1, TimeUnit.SECONDS))
        assertTrue(coordinator.release(first, reason = "first stopped"))

        val later =
            Thread {
                thirdResult.set(if (coordinator.acquire(third, reason = "third", waitTimeoutMs = 100)) 1 else 0)
                thirdFinished.countDown()
            }
        later.start()

        assertTrue(thirdFinished.await(1, TimeUnit.SECONDS))
        assertEquals(0, thirdResult.get())
        allowStopToFinish.countDown()
        assertTrue(secondAcquired.await(1, TimeUnit.SECONDS))
        stopper.join(1_000)
        later.join(1_000)
        assertEquals(second, coordinator.currentOwner())
    }

    @Test
    fun staleOwnerCannotReleaseNewerOwner() {
        val coordinator = NativeTunnelSessionCoordinator(stopNativeTunnel = {}, logger = { _, _ -> })
        val old = NativeTunnelOwner(VpnContract.MODE_PROXY_ONLY, "attempt-old")
        val current = NativeTunnelOwner(VpnContract.MODE_PROXY_ONLY, "attempt-current")

        assertTrue(coordinator.acquire(old, reason = "old"))
        assertTrue(coordinator.release(old, reason = "old done"))
        assertTrue(coordinator.acquire(current, reason = "current"))

        assertFalse(coordinator.release(old, reason = "stale"))
        assertEquals(current, coordinator.currentOwner())
    }
}
