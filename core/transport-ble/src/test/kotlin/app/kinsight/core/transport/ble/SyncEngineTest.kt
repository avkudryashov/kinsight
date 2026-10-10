package app.kinsight.core.transport.ble

import app.kinsight.core.crypto.DiscoveryTags
import app.kinsight.core.model.Membership
import app.kinsight.core.model.proto.BatchAck
import app.kinsight.core.model.proto.Hello
import app.kinsight.core.model.proto.SyncMessage
import app.kinsight.core.model.proto.VectorAnnounce
import app.kinsight.core.sync.ByteStream
import app.kinsight.core.sync.LinkFaults
import app.kinsight.core.sync.MemoryPipe
import app.kinsight.core.sync.PROTOCOL_VERSION
import app.kinsight.core.sync.SecureChannel
import app.kinsight.core.sync.TODAY
import app.kinsight.core.sync.summary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import okio.ByteString.Companion.toByteString

class SyncEngineTest {
    private data class Addr(val name: String) : PeerAddress

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var mono = 0L

    /** Early in a 15-minute window, so tags do not rotate under a test by accident. */
    @Volatile
    private var wall = 1_760_000_000_000L / DiscoveryTags.WINDOW_MS * DiscoveryTags.WINDOW_MS + 1_000

    private val engines = ConcurrentHashMap<PeerAddress, SyncEngine>()
    private val connects = AtomicInteger()

    @Volatile
    private var dropNextAfterBytes: Long? = null

    /** Connecting to an address hands the other end of an in-memory pipe to the engine registered there. */
    private val link = object : BleLink {
        override suspend fun connect(address: PeerAddress): ByteStream {
            connects.incrementAndGet()
            val faults = dropNextAfterBytes?.let { dropNextAfterBytes = null; LinkFaults(it) } ?: LinkFaults()
            val (mine, theirs) = MemoryPipe.pair(faults)
            engines.getValue(address).onInbound(theirs)
            return mine
        }
    }

    private fun engine(
        group: TestGroup,
        d: TestDevice,
        sessionTimeout: Duration = SyncEngine.SESSION_TIMEOUT,
        prepare: suspend () -> Unit = {},
    ) = SyncEngine(
        scope = scope,
        context = { SyncContext(group.groupId, group.discoveryKey, d.keys, d.store, d.policy()) },
        link = link,
        prepare = prepare,
        monoClock = { mono },
        wallClock = { wall },
        sessionTimeout = sessionTimeout,
    )

    @AfterTest
    fun cleanUp() = runBlocking<Unit> { scope.coroutineContext.job.cancelAndJoin() }

    /** Both engines see each other's current advert at [x] and [y] (the addresses rotate like RPAs). */
    private suspend fun seeEachOther(x: String, ex: SyncEngine, y: String, ey: SyncEngine) {
        engines[Addr(x)] = ex
        engines[Addr(y)] = ey
        ex.refreshAdvert()
        ey.refreshAdvert()
        ex.onSightings(listOf(Sighting(Addr(y), ey.advert.value!!, rssi = -50)))
        ey.onSightings(listOf(Sighting(Addr(x), ex.advert.value!!, rssi = -50)))
    }

    private suspend fun awaitFinished(total: Int, vararg es: SyncEngine) = withTimeout(15.seconds) {
        while (es.sumOf { it.status.value.finishedSessions } < total) delay(20)
    }

    @Test
    fun mutualSightingsYieldOneSessionAndConverge() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.toString(), 5)))
        val prepared = AtomicInteger()
        val e0 = engine(g, d0) { prepared.incrementAndGet() }
        val e1 = engine(g, d1) { prepared.incrementAndGet() }
        seeEachOther("d0", e0, "d1", e1)
        awaitFinished(2, e0, e1)
        delay(200)
        assertEquals(1, connects.get())
        assertEquals(d0.store.versionVector(), d1.store.versionVector())
        assertTrue(prepared.get() in 1..2, "prepared ${prepared.get()} times")
        assertTrue(e0.status.value.lastSyncWallMs == wall && e1.status.value.lastSyncWallMs == wall)
        assertEquals(e0.advert.value!!.vectorHash.toList(), e1.advert.value!!.vectorHash.toList()) // adverts refreshed
    }

    @Test
    fun equalVectorsDoNotConnect() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val e0 = engine(g, g.devices[0])
        val e1 = engine(g, g.devices[1])
        seeEachOther("d0", e0, "d1", e1)
        delay(300)
        assertEquals(0, connects.get())
    }

    @Test
    fun cooldownFollowsTheDeviceAcrossAddressAndWindowChange() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.toString(), 5)))
        val e0 = engine(g, d0)
        val e1 = engine(g, d1)
        seeEachOther("d0", e0, "d1", e1)
        awaitFinished(2, e0, e1)

        d0.store.applyBatch(listOf(d0.author.summary(TODAY.minusDays(1).toString(), 7)))
        wall += DiscoveryTags.WINDOW_MS // new tags
        mono += 5 * 60_000
        seeEachOther("d0-rpa2", e0, "d1-rpa2", e1) // new addresses
        delay(300)
        assertEquals(1, connects.get(), "cooldown must follow the device, not its address or tag")

        mono += 6 * 60_000
        seeEachOther("d0-rpa3", e0, "d1-rpa3", e1)
        awaitFinished(4, e0, e1)
        assertEquals(2, connects.get())
        assertEquals(d0.store.versionVector(), d1.store.versionVector())
    }

    @Test
    fun droppedLinkIsRetriedAfterBackoffAndConverges() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch((0 until 50).map { d0.author.summary(TODAY.minusDays(it.toLong()).toString(), it.toLong()) })
        val e0 = engine(g, d0)
        val e1 = engine(g, d1)
        dropNextAfterBytes = 3_000 // past the handshake, inside the first batch
        seeEachOther("d0", e0, "d1", e1)
        awaitFinished(2, e0, e1)
        assertTrue(listOf(e0, e1).any { it.status.value.lastProblem == SyncProblem.LINK_FAILED })

        seeEachOther("d0", e0, "d1", e1)
        delay(300)
        assertEquals(1, connects.get(), "retried inside the one-minute backoff")

        mono += 61_000
        seeEachOther("d0", e0, "d1", e1)
        awaitFinished(4, e0, e1)
        assertEquals(2, connects.get())
        assertEquals(d0.store.versionVector(), d1.store.versionVector())
    }

    @Test
    fun prepareFailureDoesNotStopTheSync() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.toString(), 5)))
        val e0 = engine(g, d0) { error("usage database busy") }
        val e1 = engine(g, d1) { error("usage database busy") }
        seeEachOther("d0", e0, "d1", e1)
        awaitFinished(2, e0, e1)
        assertEquals(d0.store.versionVector(), d1.store.versionVector())
    }

    @Test
    fun withoutAGroupNothingHappensAndInboundIsClosed() = runBlocking<Unit> {
        val prepared = AtomicInteger()
        val e = SyncEngine(scope, context = { null }, link = link, prepare = { prepared.incrementAndGet() }, monoClock = { mono }, wallClock = { wall })
        e.refreshAdvert()
        assertNull(e.advert.value)
        e.onSightings(listOf(Sighting(Addr("x"), AdvertPayload(ByteArray(4), ByteArray(2), ByteArray(4)), rssi = -50)))
        val (mine, theirs) = MemoryPipe.pair()
        e.onInbound(theirs)
        withTimeout(2.seconds) { assertFailsWith<IOException> { mine.readFully(1) } }
        delay(200)
        assertEquals(0, connects.get())
        assertEquals(0, prepared.get())
    }

    @Test
    fun inboundWhileBusyIsClosed() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val e1 = engine(g, g.devices[1])
        val (silent, silentTheirs) = MemoryPipe.pair()
        e1.onInbound(silentTheirs) // the handshake waits for a first message that never comes: the session gate is held
        delay(100)
        val (mine, theirs) = MemoryPipe.pair()
        e1.onInbound(theirs)
        withTimeout(2.seconds) { assertFailsWith<IOException> { mine.readFully(1) } }
        silent.close()
    }

    @Test
    fun backoffFollowsTheAuthenticatedPeerNotTheResolvedOne() = runBlocking<Unit> {
        val g = TestGroup.create(3)
        val (d0, d1, d2) = g.devices
        d0.store.applyBatch((0 until 50).map { d0.author.summary(TODAY.minusDays(it.toLong()).toString(), it.toLong()) })
        val e0 = engine(g, d0)
        val e1 = engine(g, d1)
        val e2 = engine(g, d2)
        // A tag window in which d0 advertises the smallest tag, so d0 is the side that connects to either peer.
        while (true) {
            listOf(e0, e1, e2).forEach { it.refreshAdvert() }
            val tags = listOf(e0, e1, e2).map { it.advert.value!!.deviceTagValue }
            if (tags[0] < tags[1] && tags[0] < tags[2]) break
            wall += DiscoveryTags.WINDOW_MS
        }
        // The advert names d1, the radio reaches d2: what a 2-byte device-tag collision looks like to the engine.
        engines[Addr("x")] = e2
        engines[Addr("d2")] = e2
        dropNextAfterBytes = 3_000 // past the handshake: the peer is authenticated when the link drops
        e0.onSightings(listOf(Sighting(Addr("x"), e1.advert.value!!, rssi = -50)))
        awaitFinished(2, e0, e2)
        assertEquals(SyncProblem.LINK_FAILED, e0.status.value.lastProblem)

        e0.onSightings(listOf(Sighting(Addr("d2"), e2.advert.value!!, rssi = -50)))
        delay(300)
        assertEquals(1, connects.get(), "the failure backoff must hold for d2, the device the Noise handshake proved")
    }

    @Test
    fun aPeerThatKeepsTalkingCannotHoldTheSessionPastTheTimeout() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        val e1 = engine(g, d1, sessionTimeout = 1.seconds)
        val (mine, theirs) = MemoryPipe.pair()
        e1.onInbound(theirs)
        val ch = SecureChannel.initiate(mine, d0.keys.dh, SecureChannel.prologue(g.groupId))
        suspend fun send(m: SyncMessage) = ch.send(SyncMessage.ADAPTER.encode(m))
        val view = Membership.compute(g.groupId, d0.store.membershipEntries())!!
        val effective = view.effectiveEntries.sortedWith(compareBy({ it.author.hex }, { it.seq }))
        send(
            SyncMessage(
                hello = Hello(
                    protocol_version = PROTOCOL_VERSION,
                    group_id = g.groupId.bytes().toByteString(),
                    membership_hash = Membership.hash(g.groupId, effective).toByteString(),
                    wants_foreign_entries = true,
                ),
            ),
        )
        val vector = d0.store.versionVector().toProto()
        send(SyncMessage(vector_announce = VectorAnnounce(vector = vector)))
        // Never Done: a stray ack every 50 ms keeps the session's 30-second idle timeout from ever firing.
        withTimeout(10.seconds) {
            assertFailsWith<IOException> {
                while (true) {
                    send(SyncMessage(ack = BatchAck(vector = vector)))
                    delay(50)
                }
            }
        }
        awaitFinished(1, e1)
        assertEquals(SyncProblem.LINK_FAILED, e1.status.value.lastProblem)
        assertTrue(!e1.status.value.syncing)
    }
}
