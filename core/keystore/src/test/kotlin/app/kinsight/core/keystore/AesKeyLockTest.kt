package app.kinsight.core.keystore

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Parked from Task 4: the identity's wrap and CloudFiles look the alias up under one lock, so it is generated once. */
class AesKeyLockTest {
    @Test
    fun twoCallersAtOnceGenerateTheAliasOnce() {
        val stored = AtomicReference<SecretKey?>(null)
        val created = AtomicInteger()
        val start = CountDownLatch(1)
        val results = arrayOfNulls<SecretKey>(2)
        val threads = (0..1).map { i ->
            thread {
                start.await()
                results[i] = lookupOrCreate(lookup = { stored.get() }) {
                    created.incrementAndGet()
                    Thread.sleep(50) // a slow keygen: the other caller must wait, not generate its own
                    SecretKeySpec(ByteArray(32) { i.toByte() }, "AES").also { stored.set(it) }
                }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, created.get())
        assertSame(results[0], results[1])
    }
}
