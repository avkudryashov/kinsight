package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipLimits
import app.kinsight.core.model.proto.Hello
import app.kinsight.core.model.proto.MembershipOffer
import app.kinsight.core.model.proto.Reject
import app.kinsight.core.model.proto.SetAdmin
import app.kinsight.core.model.proto.SyncMessage
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.proto.UsageSummary
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
    fun aVersionRejectTellsEachSideWhoMustUpdate() = runBlocking<Unit> {
        val (a, b) = buildGroup(2)
        val (ra, rb) = syncPair(a, b, versionsA = 1..1, versionsB = 2..2, legacyA = true)
        assertEquals(SessionResult.Rejected(Reject.Reason.PROTOCOL_VERSION, byPeer = false, peerNewer = true), ra)
        assertEquals(SessionResult.Rejected(Reject.Reason.PROTOCOL_VERSION, byPeer = false, peerNewer = false), rb)
    }

    @Test
    fun protocolOneMeetsCurrentCleanlyRejectedWithoutTransfer() = runBlocking {
        assertEquals(2, PROTOCOL_VERSION)
        val (old, new) = buildGroup(2)
        new.publish(new.author.categoryOverride("com.example.game", 1))
        val before = old.store.liveEntries().toSet()
        for ((va, vb) in listOf(1..1 to ProtocolVersions.SUPPORTED, ProtocolVersions.SUPPORTED to 1..1)) {
            val (ra, rb) = syncPair(old, new, versionsA = va, versionsB = vb, legacyA = va == 1..1, legacyB = vb == 1..1)
            // A version reject, never TOO_MANY_INVALID (which would block the peer for an hour), and nothing crosses.
            for (r in listOf(ra, rb)) {
                val rejected = assertIs<SessionResult.Rejected>(r)
                assertEquals(Reject.Reason.PROTOCOL_VERSION, rejected.reason)
                assertEquals(false, rejected.byPeer)
            }
            assertEquals(before, old.store.liveEntries().toSet())
        }
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
    fun nonAdminMembershipSpamDoesNotBreakSessions() = runBlocking {
        val (a, b, c) = buildGroup(3)
        // b is not an admin; its AddMember ops are never effective, and carry ~40 KB names each.
        val spam = List(2) {
            val k = DeviceKeys.generate()
            b.author.create {
                it.copy(membership_op = MembershipOp(add = AddMember(k.sign.publicKey.toByteString(), k.dh.publicKey.toByteString(), "x".repeat(40_000))))
            }
        }
        b.publish(*spam.toTypedArray())
        val (rb, rc) = syncPair(b, c)
        assertIs<SessionResult.Completed>(rb)
        assertIs<SessionResult.Completed>(rc)
        a.publish(a.author.addMember(DeviceKeys.generate())) // membership now differs everywhere
        for ((x, y) in listOf(a to c, a to b, b to c)) {
            val (rx, ry) = syncPair(x, y)
            assertIs<SessionResult.Completed>(rx, "${x.name}-${y.name}")
            assertIs<SessionResult.Completed>(ry, "${x.name}-${y.name}")
        }
        assertTrue(spam.none { it in a.store.liveEntries() || it in c.store.liveEntries() })
    }

    @Test
    fun largeMembershipSyncsViaPagedOffer() = runBlocking {
        val (a, b) = buildGroup(2)
        val adds = List(300) { a.author.addMember(DeviceKeys.generate(), "m".repeat(60)) }
        a.publish(*adds.toTypedArray())
        assertTrue(adds.sumOf { it.bytes.size } > SecureChannel.MAX_PLAINTEXT)
        val (ra, rb) = syncPair(a, b)
        assertIs<SessionResult.Completed>(ra)
        assertIs<SessionResult.Completed>(rb)
        val view = Membership.compute(b.store.groupId, b.store.membershipEntries())!!
        assertEquals(302, view.active.size)
    }

    @Test
    fun twoMultiPageOffersOverBoundedPipeComplete() = runBlocking {
        val (a, b) = buildGroup(2)
        a.publish(a.author.create { it.copy(membership_op = MembershipOp(set_admin = SetAdmin(b.keys.deviceId.bytes().toByteString(), admin = true))) })
        syncPair(a, b)
        // Partitioned admins each add 300 members: both offers span several pages.
        a.publish(*List(300) { a.author.addMember(DeviceKeys.generate(), "a".repeat(60)) }.toTypedArray())
        b.publish(*List(300) { b.author.addMember(DeviceKeys.generate(), "b".repeat(60)) }.toTypedArray())
        val (ra, rb) = withTimeout(20.seconds) { syncPair(a, b, capacity = 1) }
        assertIs<SessionResult.Completed>(ra)
        assertIs<SessionResult.Completed>(rb)
        assertEquals(602, Membership.compute(a.store.groupId, a.store.membershipEntries())!!.active.size)
        assertEquals(602, Membership.compute(b.store.groupId, b.store.membershipEntries())!!.active.size)
    }

    @Test
    fun longCyrillicNameWithin64CodePointsIsActiveOnAllReplicas() = runBlocking {
        val (a, b, c) = buildGroup(3)
        val name = "Планшет бабушки Валентины Петровны"
        assertEquals(34, name.codePointCount(0, name.length))
        assertEquals(65, name.encodeToByteArray().size)
        val tablet = DeviceKeys.generate()
        a.publish(a.author.addMember(tablet, name))
        for ((x, y) in listOf(a to b, b to c, a to c)) {
            val (rx, ry) = syncPair(x, y)
            assertIs<SessionResult.Completed>(rx, "${x.name}-${y.name}")
            assertIs<SessionResult.Completed>(ry, "${x.name}-${y.name}")
        }
        for (n in listOf(a, b, c)) {
            val view = Membership.compute(n.store.groupId, n.store.membershipEntries())!!
            assertEquals(name, view.active[tablet.deviceId]?.displayName, n.name)
        }
        assertEquals(1, listOf(a, b, c).map { effectiveHash(it) }.toSet().size)
    }

    @Test
    fun forgedOverCapNameIsIgnoredByEveryReplica() = runBlocking {
        val (a, b, c) = buildGroup(3)
        val forged = DeviceKeys.generate()
        a.publish(a.author.addMember(forged, "x".repeat(65))) // bypasses the creation check, as a forger would
        for ((x, y) in listOf(a to b, b to c, a to c, a to b)) {
            val (rx, ry) = syncPair(x, y)
            assertIs<SessionResult.Completed>(rx, "${x.name}-${y.name}")
            assertIs<SessionResult.Completed>(ry, "${x.name}-${y.name}")
        }
        for (n in listOf(a, b, c)) {
            val view = Membership.compute(n.store.groupId, n.store.membershipEntries())!!
            assertTrue(forged.deviceId !in view.active, "${n.name} sees the forged member")
        }
        assertEquals(1, listOf(a, b, c).map { effectiveHash(it) }.toSet().size)
    }

    @Test
    fun nonAdminLamportPoisonCannotReorderMembership() = runBlocking {
        val (a, b) = buildGroup(2)
        // b (not an admin) publishes a usage entry with a near-maximal Lamport value; it reaches a's store.
        val poison = b.author.create {
            it.copy(usage_summary = UsageSummary(local_date = TODAY.toString(), zone_id = "UTC"), lamport = Long.MAX_VALUE - 1)
        }
        a.store.applyBatch(listOf(poison))
        var dKeys = DeviceKeys.generate()
        while (dKeys.deviceId.hex >= a.keys.deviceId.hex) dKeys = DeviceKeys.generate() // d sorts before a
        a.publish(a.author.addMember(dKeys, "d"))
        val d = EntryAuthor(a.store.groupId, dKeys, lastSeq = 0, observedLamport = a.lamportSource())
        val leave = d.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.LEFT_GROUP)) }
        a.store.applyBatch(listOf(leave))
        val view = Membership.compute(a.store.groupId, a.store.membershipEntries())!!
        assertTrue(dKeys.deviceId !in view.active, "d's leave was ignored")
        assertTrue(dKeys.deviceId in view.departed)
        assertTrue(leave in view.effectiveEntries)
    }

    @Test
    fun leavingMemberCannotPinTheGroupClock() = runBlocking {
        val (a, x) = buildGroup(2)
        // Non-admin x leaves with the largest valid Lamport value; a learns the (effective) leave by syncing.
        val poisonLeave = x.author.create {
            it.copy(state_event = StateEvent(kind = StateEvent.Kind.LEFT_GROUP), lamport = MembershipLimits.MAX_LAMPORT)
        }
        x.publish(poisonLeave)
        syncPair(a, x)
        assertTrue(poisonLeave in Membership.compute(a.store.groupId, a.store.membershipEntries())!!.effectiveEntries)
        var dKeys = DeviceKeys.generate()
        while (dKeys.deviceId.hex >= a.keys.deviceId.hex) dKeys = DeviceKeys.generate() // d sorts before a
        a.publish(a.author.addMember(dKeys, "d"))
        val d = EntryAuthor(a.store.groupId, dKeys, lastSeq = 0, observedLamport = a.lamportSource())
        val leave = d.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.LEFT_GROUP)) }
        a.store.applyBatch(listOf(leave))
        val view = Membership.compute(a.store.groupId, a.store.membershipEntries())!!
        assertTrue(leave in view.effectiveEntries, "d's leave was ignored")
        assertTrue(dKeys.deviceId !in view.active)
        assertTrue(dKeys.deviceId in view.departed)
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

    @Test
    fun aWipedDeviceDoesNotReuseItsOwnSeqs() = runBlocking<Unit> {
        val (a, b) = buildGroup(2)
        b.publish(*(1..5).map { b.author.summary(TODAY.minusDays(it.toLong()).toString(), it.toLong()) }.toTypedArray())
        syncPair(a, b)
        assertEquals(5L, a.store.versionVector()[b.keys.deviceId])
        // b lost its database but kept its keys (a reset cut short, then a Rewelcome into a freshly wiped database).
        b.store = InMemoryEntryStore(b.store.groupId).also { it.storeMembership(a.store.membershipEntries()) }
        assertIs<SessionResult.Completed>(syncPair(a, b).second)
        assertEquals(5L, b.store.versionVector()[b.keys.deviceId], "b must not hand out seqs 1..5 again")
    }
}

private fun EntryAuthor.addMember(k: DeviceKeys, name: String = "extra"): DecodedEntry = create {
    it.copy(membership_op = MembershipOp(add = AddMember(k.sign.publicKey.toByteString(), k.dh.publicKey.toByteString(), name)))
}

private suspend fun effectiveHash(n: Node): String {
    val view = Membership.compute(n.store.groupId, n.store.membershipEntries())!!
    return Membership.hash(n.store.groupId, view.effectiveEntries).toByteString().hex()
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
