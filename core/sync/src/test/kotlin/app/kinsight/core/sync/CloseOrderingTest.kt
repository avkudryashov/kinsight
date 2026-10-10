package app.kinsight.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.EOFException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A link with per-direction latency whose close drops everything still in flight, as a BLE L2CAP socket does: a frame
 * written (and flushed) just before close() may never reach the peer.
 */
private class LossyLink(scope: CoroutineScope, latencyAtoB: Duration, latencyBtoA: Duration) {
    @Volatile private var closed = false
    private val wireAB = Channel<ByteArray>(Channel.UNLIMITED)
    private val wireBA = Channel<ByteArray>(Channel.UNLIMITED)
    private val inA = Channel<ByteArray>(Channel.UNLIMITED)
    private val inB = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        scope.launch { pump(wireAB, inB, latencyAtoB) }
        scope.launch { pump(wireBA, inA, latencyBtoA) }
    }

    val a: ByteStream = End(inA, wireAB)
    val b: ByteStream = End(inB, wireBA)

    private suspend fun pump(wire: Channel<ByteArray>, to: Channel<ByteArray>, latency: Duration) {
        for (bytes in wire) {
            delay(latency)
            if (closed) break
            to.send(bytes)
        }
    }

    private fun closeAll() {
        closed = true
        wireAB.close()
        wireBA.close()
        inA.close()
        inB.close()
    }

    private inner class End(private val incoming: Channel<ByteArray>, private val outgoing: Channel<ByteArray>) : ByteStream {
        private var buf = ByteArray(0)
        private var pos = 0

        override suspend fun readFully(n: Int): ByteArray {
            val out = ByteArray(n)
            var filled = 0
            while (filled < n) {
                if (pos == buf.size) {
                    buf = incoming.receiveCatching().getOrNull() ?: throw EOFException("stream closed")
                    pos = 0
                }
                val k = minOf(n - filled, buf.size - pos)
                buf.copyInto(out, filled, pos, pos + k)
                pos += k
                filled += k
            }
            return out
        }

        override suspend fun write(bytes: ByteArray) {
            if (closed) throw IOException("stream closed")
            outgoing.send(bytes.copyOf())
        }

        override fun close() = closeAll()
    }
}

class CloseOrderingTest {
    private suspend fun CoroutineScope.emptySession(
        link: LossyLink,
        initiator: Node,
        responder: Node,
    ): Pair<SessionResult, SessionResult> {
        val prologue = SecureChannel.prologue(initiator.store.groupId)
        val ri = async {
            runCatching { SyncSession(initiator.store, initiator.policy).run(SecureChannel.initiate(link.a, initiator.keys.dh, prologue)) }
                .getOrElse { SessionResult.Failed(it) }
        }
        val rr = async {
            runCatching { SyncSession(responder.store, responder.policy).run(SecureChannel.accept(link.b, responder.keys.dh, prologue)) }
                .getOrElse { SessionResult.Failed(it) }
        }
        return ri.await() to rr.await()
    }

    /** The emulator run: the responder has nothing to send, completes first and must not cut off its own Done. */
    @Test
    fun emptySessionCompletesOnBothSidesWhenResponderFinishesFirst() = runTest {
        val (child, parent) = buildGroup(2)
        syncPair(child, parent) // both now hold everything: the next session is empty both ways
        val link = LossyLink(backgroundScope, latencyAtoB = 1.milliseconds, latencyBtoA = 50.milliseconds)
        val (initiator, responder) = emptySession(link, child, parent)
        assertEquals(SessionResult.Completed(parent.keys.deviceId, received = 0, sentBatches = 0), initiator)
        assertEquals(SessionResult.Completed(child.keys.deviceId, received = 0, sentBatches = 0), responder)
    }

    @Test
    fun emptySessionCompletesOnBothSidesWhenInitiatorFinishesFirst() = runTest {
        val (child, parent) = buildGroup(2)
        syncPair(child, parent)
        val link = LossyLink(backgroundScope, latencyAtoB = 50.milliseconds, latencyBtoA = 1.milliseconds)
        val (initiator, responder) = emptySession(link, child, parent)
        assertIs<SessionResult.Completed>(initiator)
        assertIs<SessionResult.Completed>(responder)
    }

    @Test
    fun sessionWithDataCompletesOnBothSidesOverLossyClose() = runTest {
        val (child, parent) = buildGroup(2)
        val usage = child.author.summary(TODAY.toString(), 60)
        child.publish(usage)
        val link = LossyLink(backgroundScope, latencyAtoB = 1.milliseconds, latencyBtoA = 50.milliseconds)
        val (initiator, responder) = emptySession(link, child, parent)
        assertEquals(1, assertIs<SessionResult.Completed>(initiator).sentBatches)
        assertIs<SessionResult.Completed>(responder)
        assertTrue(usage in parent.store.liveEntries())
    }

    /** Both sides wait for each other only until the peer has finished too, never for the whole linger bound. */
    @Test
    fun closingHandshakeDoesNotWaitForTheLingerBound() = runTest {
        val (child, parent) = buildGroup(2)
        syncPair(child, parent)
        val link = LossyLink(backgroundScope, latencyAtoB = 10.milliseconds, latencyBtoA = 10.milliseconds)
        val start = testScheduler.currentTime
        val (initiator, responder) = emptySession(link, child, parent)
        assertIs<SessionResult.Completed>(initiator)
        assertIs<SessionResult.Completed>(responder)
        assertTrue(testScheduler.currentTime - start < 1.seconds.inWholeMilliseconds, "took ${testScheduler.currentTime - start} ms")
    }
}
