package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.Reject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
        b.policy = b.policy.copy(summaryRetentionDays = 10_000)
        // ~142 bytes per entry: 800 entries ≈ 114 KB, two batches (~60 KB + ~54 KB).
        // A 90 KB budget lets the first batch through and drops the link during the second.
        a.store.applyBatch((0 until 800).map { a.author.summary(TODAY.minusDays(it.toLong()).toString(), 1) })
        val (ra, rb) = syncPair(a, b, LinkFaults(byteBudget = 90_000))
        assertTrue(ra is SessionResult.Failed || rb is SessionResult.Failed)
        val aId = a.keys.deviceId
        val partial = b.store.liveEntries().count { it.author == aId && it.body.usage_summary != null }
        assertTrue(partial in 1 until 800, "partial=$partial")
        val storedSeqs = b.store.liveEntries().filter { it.author == aId }.map { it.seq }.sorted()
        assertEquals((1L..storedSeqs.last()).toList(), storedSeqs, "stored entries of a are not a contiguous prefix")
        assertEquals(storedSeqs.last(), b.store.versionVector()[aId])
        val (ra2, rb2) = syncPair(a, b)
        assertIs<SessionResult.Completed>(ra2)
        assertIs<SessionResult.Completed>(rb2)
        assertEquals(a.store.liveEntries().toSet(), b.store.liveEntries().toSet())
    }

    @Test
    fun relayDoesNotCreateVectorGap() = runBlocking {
        val (a, b, c) = buildGroup(3)
        a.publish(a.author.summary("2026-10-08", 1))
        syncPair(a, b)
        syncPair(a, c)
        val missed = a.author.summary("2026-10-09", 2)
        val add = a.author.addMember(DeviceKeys.generate())
        a.publish(missed, add)
        // b learns the add out of band (as after a MembershipOffer): stored, vector not raised.
        b.store.storeMembership(listOf(add))
        syncPair(b, c)
        assertNoVectorGap(c, a)
        syncPair(a, c)
        assertNoVectorGap(c, a)
        assertTrue(missed in c.store.liveEntries())
    }

    @Test
    fun echoedOfferDoesNotCreateVectorGap() = runBlocking {
        val (a, b, c) = buildGroup(3)
        a.publish(a.author.summary("2026-10-08", 1))
        syncPair(a, b)
        syncPair(a, c)
        val missed = a.author.summary("2026-10-09", 2)
        val add = a.author.addMember(DeviceKeys.generate())
        a.publish(missed, add)
        // c learned the add via an offer; b has the older membership and gets it from c's offer.
        c.store.storeMembership(listOf(add))
        syncPair(b, c)
        assertNoVectorGap(c, a)
        syncPair(a, c)
        assertNoVectorGap(c, a)
        assertTrue(missed in c.store.liveEntries())
    }

    @Test
    fun largeTwoWaySyncOverBoundedPipeCompletes() = runBlocking {
        val (a, b) = buildGroup(2)
        a.policy = a.policy.copy(summaryRetentionDays = 10_000)
        b.policy = b.policy.copy(summaryRetentionDays = 10_000)
        val days = (0 until 800).map { TODAY.minusDays(it.toLong()).toString() }
        a.store.applyBatch(days.map { a.author.summary(it, 1) })
        b.store.applyBatch(days.take(500).map { b.author.summary(it, 2) })
        val (ra, rb) = withTimeout(60.seconds) { syncPair(a, b, capacity = 1) }
        assertTrue((assertIs<SessionResult.Completed>(ra)).sentBatches >= 2)
        assertTrue((assertIs<SessionResult.Completed>(rb)).sentBatches >= 2)
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
    fun protocolVersionMismatchIsRejectedOnBothSides() = runBlocking {
        val (a, b) = buildGroup(2)
        val (ra, rb) = syncPair(a, b, versionA = 1, versionB = 2)
        assertEquals(SessionResult.Rejected(Reject.Reason.PROTOCOL_VERSION, byPeer = false), ra)
        assertEquals(SessionResult.Rejected(Reject.Reason.PROTOCOL_VERSION, byPeer = false), rb)
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

private fun EntryAuthor.addMember(k: DeviceKeys): DecodedEntry = create {
    it.copy(membership_op = MembershipOp(add = AddMember(k.sign.publicKey.toByteString(), k.dh.publicKey.toByteString(), "extra")))
}

/** Every entry [author] still holds with seq up to [holder]'s vector for it must be in [holder]'s store. */
private suspend fun assertNoVectorGap(holder: Node, author: Node) {
    val id = author.keys.deviceId
    val upTo = holder.store.versionVector()[id]
    val held = holder.store.liveEntries().toSet()
    val missing = author.store.liveEntries().filter { it.author == id && it.seq <= upTo && it !in held }
    assertTrue(missing.isEmpty(), "vector says ${id.hex.take(6)}=$upTo but missing seqs ${missing.map { it.seq }}")
}

/** Serves entries of [tamperAuthor] with a flipped last byte (inside the signature). */
private class TamperingStore(private val inner: EntryStore, private val tamperAuthor: DeviceId) : EntryStore by inner {
    override suspend fun entriesAfter(author: DeviceId, afterSeq: Long, limit: Int): List<DecodedEntry> =
        inner.entriesAfter(author, afterSeq, limit).map { e ->
            if (e.author != tamperAuthor || e.isMembershipRelevant) e
            else DecodedEntry.decode(e.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        }
}
