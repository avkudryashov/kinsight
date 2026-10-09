package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.proto.Reject
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncSessionTest {
    @Test
    fun twoNodesExchangeBothWays() = runBlocking {
        val (a, b) = buildGroup(2)
        val ea = a.author.summary("2026-10-09", 100)
        val eb = b.author.summary("2026-10-09", 200)
        a.publish(ea)
        b.publish(eb)
        val (ra, rb) = syncPair(a, b)
        assertIs<SessionResult.Completed>(ra)
        assertIs<SessionResult.Completed>(rb)
        assertEquals(a.store.liveEntries().toSet(), b.store.liveEntries().toSet())
        assertTrue(eb in a.store.liveEntries() && ea in b.store.liveEntries())
        assertEquals(a.store.versionVector(), b.store.versionVector())
    }

    @Test
    fun secondSessionSendsNothing() = runBlocking {
        val (a, b) = buildGroup(2)
        a.publish(a.author.summary("2026-10-09", 1))
        syncPair(a, b)
        val (ra, _) = syncPair(a, b)
        assertEquals(0, (ra as SessionResult.Completed).sentBatches)
    }

    @Test
    fun relayThroughThirdNode() = runBlocking {
        val (a, b, c) = buildGroup(3)
        val ea = a.author.summary("2026-10-09", 1)
        a.publish(ea)
        syncPair(a, b)
        syncPair(b, c)
        assertTrue(ea in c.store.liveEntries())
    }

    @Test
    fun largeHistorySpansManyBatches() = runBlocking {
        val (a, b) = buildGroup(2)
        b.policy = b.policy.copy(summaryRetentionDays = 10_000)
        // ~160 bytes per entry: 800 entries ≈ 128 KB, more than two 60 KB batches.
        val days = (0 until 800).map { TODAY.minusDays(it.toLong()).toString() }
        a.store.applyBatch(days.map { a.author.summary(it, it.length.toLong()) })
        val (ra, _) = syncPair(a, b)
        assertTrue((ra as SessionResult.Completed).sentBatches >= 2)
        assertEquals(a.store.liveEntries().toSet(), b.store.liveEntries().toSet())
    }

    @Test
    fun droppedLinkThenCleanSessionConverges() = runBlocking {
        val (a, b) = buildGroup(2)
        a.store.applyBatch((0 until 200).map { a.author.summary(TODAY.minusDays(it.toLong()).toString(), 1) })
        // 200 entries ≈ 28.5 KB on the wire; the budget must cut the link mid-transfer.
        val (ra, rb) = syncPair(a, b, LinkFaults(byteBudget = 15_000))
        assertTrue(ra is SessionResult.Failed || rb is SessionResult.Failed)
        val partial = b.store.liveEntries().count { it.author == a.keys.deviceId && it.body.usage_summary != null }
        assertTrue(partial < 200)
        val (ra2, rb2) = syncPair(a, b)
        assertIs<SessionResult.Completed>(ra2)
        assertIs<SessionResult.Completed>(rb2)
        assertEquals(a.store.liveEntries().toSet(), b.store.liveEntries().toSet())
    }

    @Test
    fun showOthersOffReceivesNoForeignEntriesButStillShares() = runBlocking {
        val (a, b) = buildGroup(2, showOthers = { it != 1 })
        val ea = a.author.summary("2026-10-09", 1)
        val eb = b.author.summary("2026-10-09", 2)
        a.publish(ea)
        b.publish(eb)
        syncPair(a, b)
        assertTrue(eb in a.store.liveEntries())
        assertTrue(b.store.liveEntries().none { it.author == a.keys.deviceId && !it.isMembershipRelevant })
    }

    @Test
    fun nonMemberIsRejected() = runBlocking {
        val (a, _) = buildGroup(2)
        val strangerKeys = DeviceKeys.generate()
        val stranger = Node(
            "stranger", strangerKeys, EntryAuthor(a.store.groupId, strangerKeys, 0),
            InMemoryEntryStore(a.store.groupId).also { it.storeMembership(a.store.membershipEntries()) },
            SyncPolicy(strangerKeys.deviceId, today = { TODAY }),
        )
        val (ra, _) = syncPair(a, stranger)
        assertEquals(SessionResult.Rejected(Reject.Reason.NOT_A_MEMBER, byPeer = false), ra)
    }

    @Test
    fun tamperedRelayIsCutOffAfterTenInvalid() = runBlocking {
        val (a, b) = buildGroup(2)
        a.store.applyBatch((0 until 20).map { a.author.summary(TODAY.minusDays(it.toLong()).toString(), 1) })
        a.store = TamperingStore(a.store, tamperAuthor = a.keys.deviceId)
        val (_, rb) = syncPair(a, b)
        assertEquals(SessionResult.TooManyInvalid(a.keys.deviceId), rb)
        assertTrue(b.store.liveEntries().none { it.body.usage_summary != null })
    }
}

/** Serves entries of [tamperAuthor] with a flipped last byte (inside the signature). */
private class TamperingStore(private val inner: EntryStore, private val tamperAuthor: DeviceId) : EntryStore by inner {
    override suspend fun entriesAfter(author: DeviceId, afterSeq: Long, limit: Int): List<DecodedEntry> =
        inner.entriesAfter(author, afterSeq, limit).map { e ->
            if (e.author != tamperAuthor || e.isMembershipRelevant) e
            else DecodedEntry.decode(e.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        }
}
