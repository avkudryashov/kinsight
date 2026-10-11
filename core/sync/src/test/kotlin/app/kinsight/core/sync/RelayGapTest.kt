package app.kinsight.core.sync

import app.kinsight.core.model.proto.DeviceRole
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Audit A-2: parent A meets child C and parent B; B never meets C. C supersedes its day summary and detail between
 * meetings. Once B holds everything A holds, a second A-B session must send nothing, and the two parents' vectors must
 * be equal (so their advert vector tags match and ConnectPolicy sees them «in sync»).
 */
class RelayGapTest {
    @Test
    fun relayedSupersededHistoryIsSentOnlyOnce() = runBlocking<Unit> {
        val (a, b, c) = buildGroup(3, showOthers = { it != 2 }, role = { if (it == 2) DeviceRole.CHILD else DeviceRole.PARENT })
        repeat(4) { round ->
            for (v in 1..3) c.publish(c.author.summary(TODAY.toString(), (round * 3 + v).toLong()))
            c.publish(c.author.detail(TODAY.toString()))
            c.heartbeat()
            assertIs<SessionResult.Completed>(syncPair(a, c).first)
            assertIs<SessionResult.Completed>(syncPair(a, b).first)
        }
        val (again, _) = syncPair(a, b)
        assertEquals(0, assertIs<SessionResult.Completed>(again).sentBatches, "the second A-B session resent C's history")
        assertEquals(a.store.versionVector(), b.store.versionVector())
        assertEquals(a.store.liveEntries().toSet(), b.store.liveEntries().toSet())
    }

    /** C writes two Heartbeats between meetings: the receivers never see the first one, so the second must declare it dead. */
    @Test
    fun aMissedPreviousHeartbeatIsAccounted() = runBlocking<Unit> {
        val (a, b, c) = buildGroup(3, showOthers = { it != 2 }, role = { if (it == 2) DeviceRole.CHILD else DeviceRole.PARENT })
        repeat(3) { round ->
            for (v in 1..3) c.publish(c.author.summary(TODAY.toString(), (round * 3 + v).toLong()))
            c.heartbeat()
            c.heartbeat()
            assertIs<SessionResult.Completed>(syncPair(a, c).first)
            assertIs<SessionResult.Completed>(syncPair(a, b).first)
        }
        val (again, _) = syncPair(a, b)
        assertEquals(0, assertIs<SessionResult.Completed>(again).sentBatches, "the second A-B session resent C's history")
        assertEquals(c.author.lastSeq, b.store.versionVector()[c.keys.deviceId])
        assertEquals(c.author.lastSeq, a.store.versionVector()[c.keys.deviceId])
    }

    /** B already holds C's Heartbeat (so it arrives as a Duplicate) but not its live set's effect: it still closes the gap. */
    @Test
    fun aDuplicateHeartbeatStillAccountsItsLiveSet() = runBlocking<Unit> {
        val (a, b, c) = buildGroup(3, showOthers = { it != 2 }, role = { if (it == 2) DeviceRole.CHILD else DeviceRole.PARENT })
        c.publish(c.author.summary(TODAY.toString(), 1)) // 1: superseded at C before anyone sees it
        c.publish(c.author.summary(TODAY.toString(), 2)) // 2: live
        val hb = c.heartbeat() // 3: live set {2}, 1 dead
        assertIs<SessionResult.Completed>(syncPair(a, c).first)
        b.store.applyBatch(listOf(hb)) // stored without its statement being applied
        assertEquals(0L, b.store.versionVector()[c.keys.deviceId])
        assertIs<SessionResult.Completed>(syncPair(a, b).first)
        assertEquals(3L, b.store.versionVector()[c.keys.deviceId])
    }
}
