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
}
