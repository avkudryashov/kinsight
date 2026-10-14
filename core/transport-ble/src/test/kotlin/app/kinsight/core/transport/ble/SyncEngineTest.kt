package app.kinsight.core.transport.ble

import app.kinsight.core.crypto.DiscoveryTags
import app.kinsight.core.model.Membership
import app.kinsight.core.model.proto.BatchAck
import app.kinsight.core.model.proto.CloudHandoff
import app.kinsight.core.model.proto.Hello
import app.kinsight.core.model.proto.SyncMessage
import app.kinsight.core.model.proto.VectorAnnounce
import app.kinsight.core.sync.ByteStream
import app.kinsight.core.sync.EntryStore
import app.kinsight.core.sync.LinkFaults
import app.kinsight.core.sync.MemoryPipe
import app.kinsight.core.sync.PROTOCOL_VERSION
import app.kinsight.core.sync.ProtocolVersions
import app.kinsight.core.sync.SecureChannel
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.TODAY
import app.kinsight.core.sync.summary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import okio.ByteString.Companion.toByteString

class SyncEngineTest {
    private data class Addr(val name: String) : PeerAddress

    /** Exceptions that escaped an engine coroutine: on Android each of these would crash the process. */
    private val uncaught = ConcurrentLinkedQueue<Throwable>()

    private val scope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught.add(e) },
    )

    /** Every engine's log, prefixed with its device name. */
    private val logs = ConcurrentLinkedQueue<String>()

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
        prepareTimeout: Duration = SyncEngine.PREPARE_TIMEOUT,
        connectTimeout: Duration = SyncEngine.CONNECT_TIMEOUT,
        store: EntryStore = d.store,
        link: BleLink = this.link,
        onLog: (String) -> Unit = {},
        onLinkExhausted: () -> Unit = {},
        syncPolicy: SyncPolicy = d.policy(),
        versions: () -> IntRange = { ProtocolVersions.SUPPORTED },
        prepare: suspend () -> Unit = {},
    ) = SyncEngine(
        scope = scope,
        context = { SyncContext(group.groupId, group.discoveryKey, d.keys, store, syncPolicy) },
        link = link,
        prepare = prepare,
        monoClock = { mono },
        wallClock = { wall },
        log = {
            logs.add("${d.name}: $it")
            onLog(it)
        },
        sessionTimeout = sessionTimeout,
        prepareTimeout = prepareTimeout,
        connectTimeout = connectTimeout,
        protocolVersions = versions,
        onLinkExhausted = onLinkExhausted,
    )

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        scope.coroutineContext.job.cancelAndJoin()
        assertTrue(uncaught.isEmpty(), "escaped engine coroutines: ${uncaught.toList()}")
    }

    private fun skipped(device: TestDevice, peer: TestDevice, reason: String) =
        logs.contains("${device.name}: skip ${PeerKey.of(peer.keys.deviceId).value.take(12)}: $reason")

    /** Both engines see each other's current advert at [x] and [y] (the addresses rotate like RPAs). */
    private suspend fun seeEachOther(x: String, ex: SyncEngine, y: String, ey: SyncEngine) {
        engines[Addr(x)] = ex
        engines[Addr(y)] = ey
        ex.refreshAdvert()
        ey.refreshAdvert()
        ex.onSightings(listOf(Sighting(Addr(y), ey.advert.value!!, rssi = -50)))
        ey.onSightings(listOf(Sighting(Addr(x), ex.advert.value!!, rssi = -50)))
    }

    /** Sessions reported finished and every gate released, so the next sighting is decided, not dropped as busy. */
    private suspend fun awaitFinished(total: Int, vararg es: SyncEngine) = withTimeout(15.seconds) {
        while (es.sumOf { it.status.value.finishedSessions } < total || es.any { it.busy.value }) delay(20)
    }

    @Test
    fun aVersionRejectNamesThePeerUntilASessionWithItSucceeds() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.toString(), 5)))
        var v0 = PROTOCOL_VERSION + 1..PROTOCOL_VERSION + 1
        val e0 = engine(g, d0, versions = { v0 })
        val e1 = engine(g, d1)
        seeEachOther("d0", e0, "d1", e1)
        awaitFinished(2, e0, e1)
        assertEquals(mapOf(d1.keys.deviceId to false), e0.status.value.outdated) // d1 must update
        assertEquals(mapOf(d0.keys.deviceId to true), e1.status.value.outdated) // this phone (d1) must update

        v0 = ProtocolVersions.SUPPORTED // "d0 was updated"
        wall += DiscoveryTags.WINDOW_MS
        mono += ConnectPolicy.COOLDOWN_MS + 1
        seeEachOther("d0b", e0, "d1b", e1)
        awaitFinished(4, e0, e1)
        assertEquals(emptyMap(), e0.status.value.outdated)
        assertEquals(emptyMap(), e1.status.value.outdated)
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
        assertEquals(e0.advert.value!!.vectorTag.toList(), e1.advert.value!!.vectorTag.toList()) // adverts refreshed
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
        logs.clear()
        seeEachOther("d0-rpa2", e0, "d1-rpa2", e1) // new addresses
        delay(300)
        assertEquals(1, connects.get(), "cooldown must follow the device, not its address or tag")
        assertTrue(skipped(d0, d1, "cooldown") && skipped(d1, d0, "cooldown"), "decided by the cooldown: $logs")

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

        logs.clear()
        seeEachOther("d0", e0, "d1", e1)
        delay(300)
        assertEquals(1, connects.get(), "retried inside the one-minute backoff")
        assertTrue(skipped(d0, d1, "cooldown") || skipped(d1, d0, "cooldown"), "decided by the backoff: $logs")

        mono += 61_000
        seeEachOther("d0", e0, "d1", e1)
        awaitFinished(4, e0, e1)
        assertEquals(2, connects.get())
        assertEquals(d0.store.versionVector(), d1.store.versionVector())
    }

    @Test
    fun tenNoResourcesDialsInARowAskForOneRadioRestart() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.toString(), 5)))
        val restarts = AtomicInteger()
        val stuck = object : BleLink {
            override suspend fun connect(address: PeerAddress): ByteStream {
                connects.incrementAndGet()
                throw IOException("android.bluetooth.BluetoothSocketException: No resources Available")
            }
        }
        val e0 = engine(g, d0, link = stuck, onLinkExhausted = { restarts.incrementAndGet() })
        val e1 = engine(g, d1, link = stuck, onLinkExhausted = { restarts.incrementAndGet() })
        repeat(LinkExhaustion.THRESHOLD) { round ->
            assertEquals(0, restarts.get(), "restart asked after $round dials")
            seeEachOther("d0", e0, "d1", e1)
            awaitFinished(round + 1, e0, e1) // the smaller tag dials, the other prepares
            mono += 61_000 // past the failure backoff
        }
        assertEquals(LinkExhaustion.THRESHOLD, connects.get())
        assertEquals(1, restarts.get())
    }

    @Test
    fun twoFastPeersWhoseVectorsNeverMatchStopAfterOneEmptySession() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.toString(), 5)))
        val e0 = engine(g, d0)
        // A child: it never takes the other's own entries, so the two vectors never match (emulator run 2026-10-10).
        val e1 = engine(g, d1, syncPolicy = d1.policy().copy(showOthers = false))
        e0.setFast(Long.MAX_VALUE)
        e1.setFast(Long.MAX_VALUE)
        repeat(60) {
            seeEachOther("d0", e0, "d1", e1)
            delay(30)
            withTimeout(5.seconds) { while (e0.busy.value || e1.busy.value) delay(10) }
            mono += 1_000 // a fast-mode scan reports every advert at once: one batch a second for a minute
        }
        assertTrue(d0.store.versionVector() != d1.store.versionVector())
        assertTrue(connects.get() <= 2, "a fast minute dialled ${connects.get()} times")
        assertTrue(logs.count { it.contains(": skip ") } <= 4 * 2, "skip lines are rate-limited: ${logs.filter { it.contains(": skip ") }}")
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

        logs.clear()
        e0.onSightings(listOf(Sighting(Addr("d2"), e2.advert.value!!, rssi = -50)))
        delay(300)
        assertEquals(1, connects.get(), "the failure backoff must hold for d2, the device the Noise handshake proved")
        assertTrue(skipped(d0, d2, "cooldown"), "decided by d2's backoff: $logs")
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
        send(SyncMessage(cloud_handoff = CloudHandoff()))
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

    @Test
    fun anExceptionAfterTheSessionIsContainedAndReleasesTheGate() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.toString(), 5)))
        val broken = AtomicBoolean(false)
        val failing = object : EntryStore by d1.store {
            override suspend fun versionVector() = if (broken.get()) throw IllegalStateException("database closed") else d1.store.versionVector()
        }
        val e0 = engine(g, d0)
        val e1 = engine(g, d1, store = failing, onLog = { if ("Completed(" in it) broken.set(true) })
        seeEachOther("d0", e0, "d1", e1)
        awaitFinished(2, e0, e1)
        assertTrue(uncaught.isEmpty(), "escaped: ${uncaught.toList()}")
        assertEquals(1, e1.status.value.finishedSessions)
        assertTrue(!e1.status.value.syncing)
        assertEquals(d0.store.versionVector(), d1.store.versionVector())
    }

    @Test
    fun aHangingPrepareDoesNotHoldTheSession() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.toString(), 5)))
        val e0 = engine(g, d0, prepareTimeout = 200.milliseconds) { awaitCancellation() }
        val e1 = engine(g, d1, prepareTimeout = 200.milliseconds) { awaitCancellation() }
        seeEachOther("d0", e0, "d1", e1)
        awaitFinished(2, e0, e1)
        assertEquals(d0.store.versionVector(), d1.store.versionVector())
        assertTrue(logs.any { it.endsWith("prepare timed out after 200ms") }, "$logs")
    }

    @Test
    fun aStreamReturnedAfterTheConnectTimeoutFiredIsNeverLeaked() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.toString(), 5)))
        val (late, farEnd) = MemoryPipe.pair()
        val slowLink = object : BleLink {
            override suspend fun connect(address: PeerAddress): ByteStream {
                Thread.sleep(600) // blocking, like a radio call that ignores cancellation: returns after the timeout fired
                return late
            }
        }
        // The connect never suspends, so withTimeout runs it undispatched and hands back its value even though the
        // timeout fired meanwhile: the late stream is used (the silent far end then trips the session timeout).
        val e0 = engine(g, d0, connectTimeout = 100.milliseconds, sessionTimeout = 300.milliseconds, link = slowLink)
        val e1 = engine(g, d1)
        while (true) { // a window in which d0 connects
            e0.refreshAdvert()
            e1.refreshAdvert()
            if (e0.advert.value!!.deviceTagValue < e1.advert.value!!.deviceTagValue) break
            wall += DiscoveryTags.WINDOW_MS
        }
        e0.onSightings(listOf(Sighting(Addr("d1"), e1.advert.value!!, rssi = -50)))
        awaitFinished(1, e0)
        assertEquals(SyncProblem.LINK_FAILED, e0.status.value.lastProblem)
        // The far end sees the handshake's first message, then the close.
        withTimeout(2.seconds) { assertFailsWith<IOException> { while (true) farEnd.readFully(1) } }
    }

    @Test
    fun aStreamThatASuspendingConnectReturnsAfterTheTimeoutIsClosed() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.toString(), 5)))
        val (late, farEnd) = MemoryPipe.pair()
        val stubbornLink = object : BleLink {
            override suspend fun connect(address: PeerAddress): ByteStream {
                // Suspends past the timeout, then returns anyway (a radio callback that ignores the cancellation).
                // withTimeout drops a value that arrives after it fired on this dispatched path: only the engine's
                // holder can close the stream.
                try {
                    delay(10_000)
                } catch (e: CancellationException) {
                    // ignored on purpose
                }
                return late
            }
        }
        val e0 = engine(g, d0, connectTimeout = 100.milliseconds, link = stubbornLink)
        val e1 = engine(g, d1)
        while (true) { // a window in which d0 connects
            e0.refreshAdvert()
            e1.refreshAdvert()
            if (e0.advert.value!!.deviceTagValue < e1.advert.value!!.deviceTagValue) break
            wall += DiscoveryTags.WINDOW_MS
        }
        e0.onSightings(listOf(Sighting(Addr("d1"), e1.advert.value!!, rssi = -50)))
        awaitFinished(1, e0)
        assertEquals(SyncProblem.LINK_FAILED, e0.status.value.lastProblem)
        assertTrue(logs.any { it.startsWith("d0: connect to ") && it.endsWith(" timed out") }, "$logs")
        withTimeout(2.seconds) { assertFailsWith<IOException> { farEnd.readFully(1) } } // closed, nothing written
    }

    @Test
    fun aStrayCancellationFromPrepareDoesNotStopTheEngine() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.toString(), 5)))
        // An inner withTimeout of the callback leaks its TimeoutCancellationException: the first call on each engine.
        fun strayOnce(): suspend () -> Unit {
            val first = AtomicBoolean(true)
            return { if (first.getAndSet(false)) withTimeout(1.milliseconds) { awaitCancellation() } }
        }
        val e0 = engine(g, d0, prepare = strayOnce())
        val e1 = engine(g, d1, prepare = strayOnce())
        seeEachOther("d0", e0, "d1", e1) // the initiator prepares inside its session, the responder in its sightings loop
        awaitFinished(2, e0, e1)
        assertEquals(d0.store.versionVector(), d1.store.versionVector())

        // Both sightings loops still run: a later round is decided and synced.
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.minusDays(1).toString(), 7)))
        mono += 11 * 60_000
        seeEachOther("d0-rpa2", e0, "d1-rpa2", e1)
        awaitFinished(4, e0, e1)
        assertEquals(d0.store.versionVector(), d1.store.versionVector())
    }

    @Test
    fun aFailureWhileRecordingStillReportsTheSessionOnce() = runBlocking<Unit> {
        val g = TestGroup.create(2)
        val (d0, d1) = g.devices
        d0.store.applyBatch(listOf(d0.author.summary(TODAY.toString(), 5)))
        val e0 = engine(g, d0)
        val e1 = engine(g, d1, onLog = { if (it.startsWith("session ") && "Completed(" in it) error("log sink broken") })
        seeEachOther("d0", e0, "d1", e1)
        awaitFinished(2, e0, e1)
        delay(200)
        assertEquals(1, e1.status.value.finishedSessions)
        assertTrue(!e1.status.value.syncing)
        assertEquals(SyncProblem.LINK_FAILED, e1.status.value.lastProblem)
    }
}
