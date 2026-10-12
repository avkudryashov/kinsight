package app.kinsight.core.data

import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.sync.LiveSet
import app.kinsight.core.sync.accountLiveSet
import app.kinsight.core.sync.detail
import app.kinsight.core.sync.summary
import app.kinsight.core.transport.ble.Heartbeats
import app.kinsight.core.transport.ble.ownLog
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Audit A-2 over the real wiring: UsagePipeline.appendOwn, the Room own log, the signed live set, a receiver. */
class HeartbeatLiveSetTest {
    private val mom = TestDevice()
    private val receiver = TestDevice()

    @AfterTest
    fun cleanUp() {
        mom.close()
        receiver.close()
    }

    private fun beats() = Heartbeats(
        mom.transport,
        "0.1.0",
        append = { build -> mom.pipeline.appendOwn(build = build) },
        ownLog = { author -> RoomEntryStore(mom.db, author.groupId).ownLog(author.keys.deviceId) },
        clock = { mom.now },
    )

    @Test
    fun theSignedLiveSetClosesAReceiversGapAndFollowsTheOwnLog() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val self = mom.keys.deviceId
        val store = mom.store()
        var dead = 0L
        mom.pipeline.appendOwn { a ->
            val superseded = a.summary("2026-10-08", 1).also { dead = it.seq }
            listOf(superseded, a.summary("2026-10-08", 2), a.detail("2026-10-08"))
        }
        val own = store.ownLiveSeqs(self)
        assertFalse(dead in own)

        assertTrue(beats().emitIfDue())
        val hb = store.heartbeats().single { it.author == self }
        val set = LiveSet.of(hb)!!
        assertEquals(own.last(), set.upTo)
        assertEquals(own, set.seqs.toList(), "every stored own seq of every kind, the Heartbeat itself not yet")
        assertEquals(own + hb.seq, store.ownLiveSeqs(self))

        // A receiver holding every live own entry still has a gap at the superseded seq; the statement closes it.
        val other = RoomEntryStore(receiver.db, store.groupId)
        other.applyBatch(store.entriesAfter(self, 0, Int.MAX_VALUE))
        assertEquals(dead - 1, other.versionVector()[self])
        other.accountLiveSet(self, LiveSet.of(other.heartbeats().single())!!)
        assertEquals(hb.seq, other.versionVector()[self])

        // The own log did not move: past the moved floor still nothing is signed.
        mom.now += Heartbeats.MOVED_INTERVAL_MS
        assertFalse(beats().emitIfDue())
        assertEquals(hb.seq, store.authorState(self).lastSeq)

        // A new own entry moves the log: the next Heartbeat supersedes the old one and leaves it out of the set.
        var fresh = 0L
        mom.pipeline.appendOwn { a -> listOf(a.summary("2026-10-09", 3).also { fresh = it.seq }) }
        assertTrue(beats().emitIfDue())
        val hb2 = store.heartbeats().single { it.author == self }
        val set2 = LiveSet.of(hb2)!!
        assertEquals(fresh, set2.upTo)
        assertEquals(own + fresh, set2.seqs.toList(), "the superseded Heartbeat ${hb.seq} is dead")

        // Signed only now: another new own entry within the hour waits for the floor.
        mom.pipeline.appendOwn { a -> listOf(a.summary("2026-10-10", 4)) }
        assertFalse(beats().emitIfDue())
    }
}
