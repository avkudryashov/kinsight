package app.kinsight.core.transport.ble

import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.sync.LiveSet
import app.kinsight.core.sync.PROTOCOL_VERSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportStateTest {
    private val dir = Files.createTempDirectory("kinsight-transport").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val state = TransportStateStore.create(File(dir, "transport.preferences_pb"), scope)
    private var now = 1_000_000L
    private val recorded = mutableListOf<Pair<Long, Long>>()
    private val tracker = BluetoothOffTracker(state, record = { from, to -> recorded += from to to }, clock = { now })

    @AfterTest
    fun cleanUp() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        dir.deleteRecursively()
    }

    @Test
    fun offThenOnRecordsOneInterval() = runBlocking {
        tracker.onOff()
        now += 5_000
        tracker.onOff() // a second OFF broadcast keeps the first moment
        now += 5_000
        tracker.onOn()
        tracker.onOn() // nothing left to record
        assertEquals(listOf(1_000_000L to 1_010_000L), recorded)
        assertNull(state.bluetoothOffSinceMs())
    }

    @Test
    fun onWithoutOffRecordsNothing() = runBlocking {
        tracker.onOn()
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aFailedRecordKeepsTheIntervalForTheNextTry() = runBlocking {
        var fail = true
        val flaky = BluetoothOffTracker(state, record = { from, to -> if (fail) error("db busy") else recorded += from to to }, clock = { now })
        flaky.onOff()
        now += 1_000
        runCatching { flaky.onOn() }
        fail = false
        flaky.onOn()
        assertEquals(listOf(1_000_000L to 1_001_000L), recorded)
    }

    @Test
    fun lastSyncIsKept() = runBlocking {
        assertNull(state.lastSyncMs())
        state.setLastSyncMs(42)
        assertEquals(42L, state.lastSyncMs())
    }

    @Test
    fun stoppedByUserIsKeptUntilCleared() = runBlocking {
        assertFalse(state.stoppedByUser())
        state.setStoppedByUser(true)
        assertTrue(state.stoppedByUser())
        state.setStoppedByUser(false)
        assertFalse(state.stoppedByUser())
    }

    @Test
    fun aCorruptFileReadsAsEmptyState() = runBlocking {
        val file = File(dir, "corrupt.preferences_pb")
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x04, 0x05))
        val corrupt = TransportStateStore.create(file, scope)
        assertNull(corrupt.lastSyncMs())
        assertFalse(corrupt.stoppedByUser())
        corrupt.setLastSyncMs(5)
        assertEquals(5L, corrupt.lastSyncMs())
    }

    @Test
    fun heartbeatIsDueOnceADayOrOnAVersionChange() = runBlocking {
        val group = TestGroup.create(1)
        val d = group.devices[0]
        val appended = mutableListOf<app.kinsight.core.model.DecodedEntry>()
        val append: suspend (suspend (EntryAuthor) -> List<app.kinsight.core.model.DecodedEntry>) -> Boolean =
            { build -> appended += build(d.author); true }
        fun beats(version: String) = Heartbeats(state, version, append, clock = { now })

        assertTrue(beats("0.1.0").emitIfDue())
        val hb = appended.single()
        assertEquals(EntryKind.HEARTBEAT, hb.kind)
        assertEquals("0.1.0", hb.body.heartbeat!!.app_version)
        assertEquals(PROTOCOL_VERSION, hb.body.heartbeat!!.protocol_version)

        now += Heartbeats.INTERVAL_MS - 1
        assertFalse(beats("0.1.0").emitIfDue())
        assertTrue(beats("0.2.0").emitIfDue()) // new app version
        now += Heartbeats.INTERVAL_MS
        assertTrue(beats("0.2.0").emitIfDue()) // a day later
        now -= 10 * Heartbeats.INTERVAL_MS
        assertTrue(beats("0.2.0").emitIfDue()) // clock set back: never stuck
        assertEquals(4, appended.size)
    }

    @Test
    fun heartbeatWithoutAGroupIsNotMarkedSent() = runBlocking {
        val beats = Heartbeats(state, "0.1.0", append = { false }, clock = { now })
        assertFalse(beats.emitIfDue())
        assertNull(state.heartbeat())
    }

    @Test
    fun heartbeatSignsTheOwnLiveSetAndFollowsTheOwnLog() = runBlocking {
        val group = TestGroup.create(1)
        val author = EntryAuthor(group.groupId, group.devices[0].keys, lastSeq = 10)
        val appended = mutableListOf<app.kinsight.core.model.DecodedEntry>()
        var log = OwnLog(liveSeqs = listOf(1, 2, 5), heartbeatSeq = 2)
        val beats = Heartbeats(state, "0.1.0", append = { build -> appended += build(author); true }, ownLog = { log }, clock = { now })

        assertTrue(beats.emitIfDue()) // first one
        val set = LiveSet.of(appended.last())!!
        assertEquals(5L, set.upTo)
        assertEquals(listOf(1L, 5L), set.seqs.toList()) // the superseded Heartbeat 2 is not live
        log = OwnLog(listOf(1, 5, 11), heartbeatSeq = 11) // the new Heartbeat is the newest own entry
        assertFalse(beats.emitIfDue())
        log = OwnLog(listOf(1, 5, 11, 12), heartbeatSeq = 11) // a new own entry since
        assertTrue(beats.emitIfDue())
        assertEquals(2, appended.size)
    }
}
