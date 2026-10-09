package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.Membership
import app.kinsight.core.model.proto.Hello
import app.kinsight.core.model.proto.MembershipOffer
import app.kinsight.core.model.proto.Reject
import app.kinsight.core.model.proto.SyncMessage
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
    fun shortRetentionRelayDoesNotMakeReceiverSkipOldEntries() = runBlocking {
        val (c, a, b) = buildGroup(3)
        b.policy = b.policy.copy(detailRetentionDays = 5)
        val old = a.author.detail(TODAY.minusDays(30).toString()) // a#1, expired for b only
        val fresh = a.author.summary(TODAY.toString(), 1) // a#2
        a.publish(old, fresh)
        syncPair(a, b)
        assertTrue(old !in b.store.liveEntries() && fresh in b.store.liveEntries())
        syncPair(b, c)
        assertTrue(fresh in c.store.liveEntries())
        assertEquals(0L, c.store.versionVector()[a.keys.deviceId], "contig must stay below the gap at a#1")
        assertTrue(old !in c.store.liveEntries())
        syncPair(a, c)
        assertTrue(old in c.store.liveEntries())
        assertEquals(2L, c.store.versionVector()[a.keys.deviceId])
    }

    @Test
    fun omittingRelayCannotHideAnAuthorsEntry() = runBlocking {
        val (c, a, b) = buildGroup(3)
        val pause = a.author.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.SHARING_PAUSED)) } // a#1
        val later = a.author.summary(TODAY.toString(), 1) // a#2
        a.publish(pause, later)
        syncPair(a, b)
        assertTrue(pause in b.store.liveEntries())
        b.store = HidingStore(b.store, pause)
        syncPair(b, c)
        assertTrue(later in c.store.liveEntries())
        assertEquals(0L, c.store.versionVector()[a.keys.deviceId], "contig must stay below the hidden a#1")
        assertTrue(pause !in c.store.liveEntries())
        syncPair(a, c)
        assertTrue(pause in c.store.liveEntries())
        assertEquals(2L, c.store.versionVector()[a.keys.deviceId])
    }

    @Test
    fun authorCompletionClosesSupersededGap() = runBlocking {
        val (c, a) = buildGroup(2)
        a.publish(a.author.summary(TODAY.toString(), 1)) // a#1, superseded below
        val newer = a.author.summary(TODAY.toString(), 2) // a#2
        a.publish(newer)
        assertEquals(listOf(newer), a.store.liveEntries().filter { it.author == a.keys.deviceId })
        syncPair(a, c)
        assertEquals(2L, c.store.versionVector()[a.keys.deviceId])
        val (ra, _) = syncPair(a, c)
        assertEquals(0, (ra as SessionResult.Completed).sentBatches)
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
    fun strangerWithGenesisLearnsNoMembershipAndNoVector() = runBlocking {
        val (a, _) = buildGroup(2)
        val genesis = a.store.membershipEntries().single { it.seq == 1L && it.author == a.keys.deviceId }
        val strangerKeys = DeviceKeys.generate()
        val stranger = Node(
            "stranger", strangerKeys, EntryAuthor(a.store.groupId, strangerKeys, 0),
            InMemoryEntryStore(a.store.groupId).also { it.storeMembership(listOf(genesis)) },
            SyncPolicy(strangerKeys.deviceId, today = { TODAY }),
        )
        val (ra, _) = syncPair(a, stranger)
        assertEquals(SessionResult.Rejected(Reject.Reason.NOT_A_MEMBER, byPeer = false), ra)
        assertEquals(listOf(genesis), stranger.store.membershipEntries())
        assertEquals(VersionVector.EMPTY, stranger.store.versionVector())
    }

    @Test
    fun rawStrangerReceivesOnlyHelloEmptyOfferAndReject() = runBlocking {
        val (a, _) = buildGroup(2)
        val groupId = a.store.groupId
        val genesis = a.store.membershipEntries().single { it.seq == 1L && it.author == a.keys.deviceId }
        val strangerKeys = DeviceKeys.generate()
        val (sa, sb) = MemoryPipe.pair()
        val prologue = SecureChannel.prologue(groupId)
        coroutineScope {
            val ra = async { SyncSession(a.store, a.policy).run(SecureChannel.accept(sb, a.keys.dh, prologue)) }
            val ch = SecureChannel.initiate(sa, strangerKeys.dh, prologue)
            fun encode(m: SyncMessage) = SyncMessage.ADAPTER.encode(m)
            ch.send(
                encode(
                    SyncMessage(
                        hello = Hello(
                            protocol_version = PROTOCOL_VERSION,
                            group_id = groupId.bytes().toByteString(),
                            membership_hash = Membership.hash(groupId, listOf(genesis)).toByteString(),
                            wants_foreign_entries = true,
                        ),
                    ),
                ),
            )
            ch.send(encode(SyncMessage(membership = MembershipOffer(entries = listOf(genesis.bytes.toByteString())))))
            val received = mutableListOf<SyncMessage>()
            try {
                while (true) received += SyncMessage.ADAPTER.decode(ch.receive())
            } catch (e: IOException) {
                // a hung up after rejecting
            }
            assertEquals(SessionResult.Rejected(Reject.Reason.NOT_A_MEMBER, byPeer = false), ra.await())
            assertNotNull(received.first().hello)
            val offers = received.mapNotNull { it.membership }
            assertTrue(offers.isNotEmpty(), "got $received")
            assertTrue(offers.all { it.entries.isEmpty() }, "a non-member got membership entries: $offers")
            assertTrue(received.none { it.vector_announce != null || it.ack != null || it.batch != null }, "got $received")
            assertEquals(Reject.Reason.NOT_A_MEMBER, received.last().reject?.reason)
        }
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

/** A relay that silently omits [hidden] when serving entries. */
private class HidingStore(private val inner: EntryStore, private val hidden: DecodedEntry) : EntryStore by inner {
    override suspend fun entriesAfter(author: DeviceId, afterSeq: Long, limit: Int): List<DecodedEntry> =
        inner.entriesAfter(author, afterSeq, limit).filter { it != hidden }
}

/** Serves entries of [tamperAuthor] with a flipped last byte (inside the signature). */
private class TamperingStore(private val inner: EntryStore, private val tamperAuthor: DeviceId) : EntryStore by inner {
    override suspend fun entriesAfter(author: DeviceId, afterSeq: Long, limit: Int): List<DecodedEntry> =
        inner.entriesAfter(author, afterSeq, limit).map { e ->
            if (e.author != tamperAuthor || e.isMembershipRelevant) e
            else DecodedEntry.decode(e.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        }
}
