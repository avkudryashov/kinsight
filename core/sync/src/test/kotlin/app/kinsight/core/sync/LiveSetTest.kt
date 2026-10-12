package app.kinsight.core.sync

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.Heartbeat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveSetTest {
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "a")
    private fun authorAt(seq: Long) = EntryAuthor(genesis.groupId, keys, lastSeq = seq - 1)

    @Test
    fun roundTripLeavesTheCurrentHeartbeatOut() {
        val hb = LiveSet.heartbeat("1.0", PROTOCOL_VERSION, own = listOf(1, 4, 7, 9), currentHeartbeat = 7)
        val set = assertNotNull(LiveSet.of(authorAt(10).create { it.copy(heartbeat = hb) }))
        assertEquals(0L, set.floor)
        assertEquals(9L, set.upTo)
        assertEquals(listOf(1L, 4L, 9L), set.seqs.toList())
    }

    @Test
    fun overflowListsTheNewestSeqsAndRaisesTheFloor() {
        val own = (1L..2_500L).toList()
        val hb = LiveSet.heartbeat("1.0", PROTOCOL_VERSION, own, currentHeartbeat = 2_500)
        val set = assertNotNull(LiveSet.of(authorAt(2_501).create { it.copy(heartbeat = hb) }))
        assertEquals(LiveSet.MAX_SEQS, set.seqs.size)
        assertEquals(499L, set.floor)
        assertEquals(500L, set.seqs.first())
        assertEquals(2_499L, set.seqs.last())
        assertEquals(2_500L, set.upTo)
    }

    @Test
    fun malformedStatementsAreIgnored() {
        fun of(seq: Long, h: Heartbeat) = LiveSet.of(authorAt(seq).create { it.copy(heartbeat = h) })
        assertNull(of(5, Heartbeat(app_version = "x"))) // no statement at all (0.7.2)
        assertNull(of(5, Heartbeat(live_upto = 5, live_deltas = listOf(1L)))) // upTo must be below the Heartbeat's own seq
        assertNull(of(9, Heartbeat(live_upto = 5, live_floor = 5))) // empty range
        assertNull(of(9, Heartbeat(live_upto = 5, live_deltas = listOf(1L, 0L)))) // not strictly ascending
        assertNull(of(9, Heartbeat(live_upto = 5, live_deltas = listOf(3L, 3L)))) // past upTo
        assertNull(of(9_999, Heartbeat(live_upto = 9_000, live_deltas = List(LiveSet.MAX_SEQS + 1) { 1L })))
        // uint64 values at or above 2^63 decode as negative Longs.
        assertNull(of(9, Heartbeat(live_upto = -1, live_deltas = listOf(1L))))
        assertNull(of(9, Heartbeat(live_upto = 5, live_floor = -1, live_deltas = listOf(2L))))
        assertNull(of(9, Heartbeat(live_upto = 5, live_deltas = listOf(-1L))))
        assertNull(of(9, Heartbeat(live_upto = 5, live_deltas = listOf(1L, Long.MIN_VALUE))))
    }

    @Test
    fun deadSeqsAreAccountedUpToTheFirstMissingLiveOne() = runTest {
        val store = InMemoryEntryStore(genesis.groupId)
        val a = authorAt(2)
        store.applyBatch(listOf(genesis.entry)) // 1
        a.summary("2026-10-08", 1) // 2: superseded at the author, never seen here
        val s3 = a.summary("2026-10-08", 2) // 3: live, here
        val d4 = a.detail("2026-10-08") // 4: live, missing here
        a.summary("2026-10-07", 1) // 5: superseded
        val s6 = a.summary("2026-10-07", 2) // 6: live, here
        store.applyBatch(listOf(s3, s6))
        store.accountLiveSet(keys.deviceId, LiveSet(0, 6, longArrayOf(1, 3, 4, 6)))
        assertEquals(3L, store.versionVector()[keys.deviceId])
        assertTrue(store.isAccounted(keys.deviceId, 5))
        store.applyBatch(listOf(d4))
        assertEquals(6L, store.versionVector()[keys.deviceId])
    }

    @Test
    fun sparseAccountingIsCapped() = runTest {
        val store = InMemoryEntryStore(genesis.groupId)
        store.accountLiveSet(keys.deviceId, LiveSet(0, 1_000_000, longArrayOf(1))) // 1 is live and missing
        assertEquals(0L, store.versionVector()[keys.deviceId])
        assertTrue(store.isAccounted(keys.deviceId, 1L + LiveSet.MAX_SPARSE))
        assertFalse(store.isAccounted(keys.deviceId, 2L + LiveSet.MAX_SPARSE))
    }

    @Test
    fun nothingAtOrBelowTheFloorIsAccounted() = runTest {
        val store = InMemoryEntryStore(genesis.groupId)
        store.accountLiveSet(keys.deviceId, LiveSet(10, 20, longArrayOf(15)))
        assertEquals(0L, store.versionVector()[keys.deviceId])
        assertFalse(store.isAccounted(keys.deviceId, 10))
        assertTrue(store.isAccounted(keys.deviceId, 11))
        assertFalse(store.isAccounted(keys.deviceId, 15))
        assertTrue(store.isAccounted(keys.deviceId, 20))
    }
}
