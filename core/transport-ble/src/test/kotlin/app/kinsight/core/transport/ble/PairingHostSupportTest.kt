package app.kinsight.core.transport.ble

import android.bluetooth.le.AdvertiseCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingHostSupportTest {
    private val tooMany = AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS

    /** A listener like BluetoothServerSocket: accept() blocks (ignoring interrupts) until close() releases it. */
    private class BlockingListener : AutoCloseable {
        val released = CountDownLatch(1)
        val closes = AtomicInteger()

        fun accept(): Any {
            released.await()
            throw IOException("listener closed")
        }

        override fun close() {
            closes.incrementAndGet()
            released.countDown()
        }
    }

    @Test
    fun cancellingTheScopeClosesTheHostAndEndsTheBlockedAcceptLoop() = runBlocking<Unit> {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val listener = BlockingListener()
        scope.launchResilientAcceptLoop(
            first = listener,
            accept = { it.accept() },
            reopen = { error("a closed listener is not reopened") },
            isClosed = { listener.closes.get() > 0 },
            onSocket = {},
            discard = {},
            onGiveUp = {},
            log = {},
        )
        scope.closeOnCancel(listener)

        scope.cancel()
        withTimeout(5_000) { scope.coroutineContext.job.join() } // a hung accept() would keep the scope alive forever

        assertEquals(1, listener.closes.get())
    }

    @Test
    fun anAlreadyCancelledScopeClosesAtOnce() {
        val scope = CoroutineScope(Job())
        scope.cancel()
        val listener = BlockingListener()

        scope.closeOnCancel(listener)

        assertEquals(1, listener.closes.get())
    }

    @Test
    fun cancellingTheWatcherAlsoCloses() = runBlocking<Unit> {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val listener = BlockingListener()
        val watcher = scope.closeOnCancel(listener)

        watcher.cancel()
        watcher.join()

        assertEquals(1, listener.closes.get())
        assertTrue(scope.coroutineContext.job.isActive)
        scope.cancel()
    }

    @Test
    fun acceptLoopHandsSocketsOverAndDiscardsOnesThatFail() = runBlocking<Unit> {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val queue = java.util.concurrent.LinkedBlockingQueue<Int>(listOf(1, 2, 3))
        val delivered = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val loop = scope.launchResilientAcceptLoop(
            first = AutoCloseable {},
            accept = { queue.poll() ?: run { done.set(true); throw IOException("closed") } },
            reopen = { error("a closed listener is not reopened") },
            isClosed = done::get,
            onGiveUp = {},
            onSocket = { if (it == 2) throw IllegalStateException("unusable") else synchronized(delivered) { delivered += it } },
            discard = { synchronized(discarded) { discarded += it } },
            log = {},
        )
        withTimeout(5_000) { loop.join() }

        assertEquals(listOf(1, 3), delivered)
        assertEquals(listOf(2), discarded)
        scope.cancel()
    }

    @Test
    fun aSocketAcceptedAfterCloseIsDiscarded() = runBlocking<Unit> {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val delivered = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val loop = scope.launchResilientAcceptLoop(
            first = AutoCloseable {},
            accept = { 7 },
            reopen = { error("a closed listener is not reopened") },
            onGiveUp = {},
            isClosed = { true },
            onSocket = { delivered += it },
            discard = { discarded += it },
            log = {},
        )
        withTimeout(5_000) { loop.join() }

        assertEquals(emptyList(), delivered)
        assertEquals(listOf(7), discarded)
        scope.cancel()
    }

    @Test
    fun advertFailureIsRetriedOnceAfterFiveSecondsThenStands() = runTest {
        val starts = AtomicInteger()
        val ads = PairingAdvertProblems(backgroundScope, log = {})
        ads.begin { starts.incrementAndGet() }
        assertEquals(1, starts.get())

        ads.onStartFailure(tooMany)
        assertEquals("advertising failed ($tooMany), retrying", ads.problem.value)
        advanceTimeBy(PairingAdvertProblems.RETRY_MS - 1)
        runCurrent()
        assertEquals(1, starts.get())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, starts.get())

        ads.onStartFailure(tooMany)
        assertEquals("advertising failed ($tooMany)", ads.problem.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, starts.get())
    }

    @Test
    fun alreadyStartedAndSuccessClearTheProblem() = runTest {
        val ads = PairingAdvertProblems(backgroundScope, log = {})
        ads.begin {}
        ads.onStartFailure(tooMany)
        ads.onStartSuccess()
        assertNull(ads.problem.value)

        ads.onStartFailure(tooMany) // the retry's failure: gives up
        ads.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED)
        assertNull(ads.problem.value)
    }

    @Test
    fun aStartThatThrowsIsAProblemToo() = runTest {
        val ads = PairingAdvertProblems(backgroundScope, log = {})
        ads.begin { throw SecurityException("revoked") }
        assertTrue(ads.problem.value!!.startsWith("advertising failed"))
    }

    @Test
    fun stopCancelsAPendingRetry() = runTest {
        val starts = AtomicInteger()
        val ads = PairingAdvertProblems(backgroundScope, log = {})
        ads.begin { starts.incrementAndGet() }
        ads.onStartFailure(tooMany)

        ads.stop()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, starts.get())
    }

    @Test
    fun aGattServiceProblemOutlivesAWorkingAdvert() = runTest {
        val ads = PairingAdvertProblems(backgroundScope, log = {})
        ads.serviceProblem("pairing GATT service refused")
        ads.begin {}
        ads.onStartSuccess()

        assertEquals("pairing GATT service refused", ads.problem.value)
    }
}
