package app.kinsight.core.keystore

import app.kinsight.core.model.DeviceKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SelectionCacheTest {
    private fun selection() =
        KeySelection(KeyBackendKind.SOFTWARE, DeviceKeys.generate(), SelfTestResult(true, "ok"), firstRun = false, fallbackReason = null)

    @Test
    fun concurrentCallersSelectOnce() = runBlocking {
        val calls = AtomicInteger()
        val cache = SelectionCache({
            calls.incrementAndGet()
            Thread.sleep(50)
            selection()
        })
        val all = (1..8).map { async(Dispatchers.Default) { cache.get() } }.awaitAll()
        assertEquals(1, calls.get())
        assertTrue(all.all { it === all[0] })
    }

    @Test
    fun failureIsNotCached() = runBlocking {
        val calls = AtomicInteger()
        val good = selection()
        val cache = SelectionCache({
            if (calls.incrementAndGet() == 1) throw KeyStorageException("lost choice") else good
        })
        assertFailsWith<KeyStorageException> { cache.get() }
        assertSame(good, cache.get())
        assertSame(good, cache.getBlocking())
        assertEquals(2, calls.get())
    }

    @Test
    fun cancelledCallerDoesNotInterruptSelect() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        val good = selection()
        val cache = SelectionCache({
            calls.incrementAndGet()
            entered.countDown()
            try {
                release.await()
            } catch (e: InterruptedException) {
                interrupted.set(true)
            }
            finished.countDown()
            good
        })
        val caller = launch(Dispatchers.Default) { cache.get() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // cancel() then release, join afterwards: the withContext(io) child cannot complete until the uninterruptible
        // select() returns, and select() waits for [release], so cancelAndJoin() before release would deadlock.
        caller.cancel()
        release.countDown()
        caller.join()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(interrupted.get(), "select() was interrupted")
        assertSame(good, cache.get())
        assertEquals(1, calls.get())
    }

    @Test
    fun resetDropsTheCachedSelection() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val cache = SelectionCache({
            calls.incrementAndGet()
            selection()
        })
        val a = cache.get()
        var wiped = false
        cache.reset { wiped = true }
        val b = cache.get()
        assertTrue(wiped)
        assertEquals(2, calls.get())
        assertFalse(a === b)
    }

    @Test
    fun failedWipeStillDropsTheCache() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val cache = SelectionCache({
            calls.incrementAndGet()
            selection()
        })
        cache.get()
        assertFailsWith<IllegalStateException> { cache.reset { error("boom") } }
        cache.get()
        assertEquals(2, calls.get())
    }
}
