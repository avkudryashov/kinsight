package app.kinsight.core.sync

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.crypto.toHex
import okio.ByteString.Companion.toByteString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntryStoreTest {
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "a")
    private val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1)
    private val store = InMemoryEntryStore(genesis.groupId)

    @Test
    fun applyBumpsVectorAndIsIdempotent() = runTest {
        val e = author.summary("2026-10-09", 10)
        store.applyBatch(listOf(genesis.entry, e))
        store.applyBatch(listOf(e))
        assertEquals(2L, store.versionVector()[keys.deviceId])
        assertEquals(listOf(genesis.entry, e), store.liveEntries())
    }

    @Test
    fun olderSupersededVersionArrivingLaterIsIgnored() = runTest {
        val old = author.summary("2026-10-09", 10)
        val new = author.summary("2026-10-09", 20)
        store.applyBatch(listOf(new))
        store.applyBatch(listOf(old))
        assertEquals(listOf(new), store.liveEntries())
        // The superseded copy still counts as received for the vector.
        assertTrue(store.isAccounted(keys.deviceId, old.seq))
    }

    @Test
    fun vectorIsContiguousAcrossGaps() = runTest {
        val e2 = author.summary("2026-10-08", 1) // seq 2
        val e3 = author.summary("2026-10-09", 1) // seq 3
        store.applyBatch(listOf(e3))
        assertEquals(0L, store.versionVector()[keys.deviceId])
        assertTrue(store.isAccounted(keys.deviceId, 3))
        assertFalse(store.isAccounted(keys.deviceId, 2))
        store.applyBatch(listOf(genesis.entry))
        assertEquals(1L, store.versionVector()[keys.deviceId])
        store.applyBatch(listOf(e2))
        assertEquals(3L, store.versionVector()[keys.deviceId])
    }

    @Test
    fun authorCompletionCrossesGap() = runTest {
        author.summary("2026-10-08", 1) // seq 2, never delivered (e.g. superseded at the author)
        val e3 = author.summary("2026-10-09", 1) // seq 3
        author.summary("2026-10-07", 1) // seq 4, never delivered
        val e5 = author.summary("2026-10-06", 1) // seq 5
        store.applyBatch(listOf(genesis.entry, e3, e5))
        assertEquals(1L, store.versionVector()[keys.deviceId])
        store.completeAuthor(keys.deviceId, 2)
        assertEquals(3L, store.versionVector()[keys.deviceId]) // 2 completed, 3 already accounted
        store.completeAuthor(keys.deviceId, 1) // never moves back
        assertEquals(3L, store.versionVector()[keys.deviceId])
        store.completeAuthor(keys.deviceId, 4)
        assertEquals(5L, store.versionVector()[keys.deviceId])
    }

    @Test
    fun newerVersionReplacesOlder() = runTest {
        val old = author.summary("2026-10-09", 10)
        val other = author.summary("2026-10-08", 5)
        val new = author.summary("2026-10-09", 20)
        store.applyBatch(listOf(old, other, new))
        assertEquals(setOf(other, new), store.liveEntries().toSet())
    }

    @Test
    fun storeMembershipDoesNotBumpVector() = runTest {
        store.storeMembership(listOf(genesis.entry))
        assertEquals(0L, store.versionVector()[keys.deviceId])
        assertEquals(listOf(genesis.entry), store.membershipEntries())
    }

    @Test
    fun maxLamportCoversStoredAndMembershipEntries() = runTest {
        assertEquals(0L, store.maxLamport())
        store.storeMembership(listOf(genesis.entry))
        assertEquals(genesis.entry.lamport, store.maxLamport())
        val high = EntryAuthor(genesis.groupId, keys, lastSeq = 1, observedLamport = { 41L }).summary("2026-10-09", 1)
        store.applyBatch(listOf(high))
        assertEquals(42L, store.maxLamport())
    }

    @Test
    fun seenOnlyBumpsVectorWithoutStoring() = runTest {
        store.applyBatch(emptyList(), mapOf(keys.deviceId to (1L..7L).toSet()))
        assertEquals(7L, store.versionVector()[keys.deviceId])
        assertTrue(store.liveEntries().isEmpty())
        store.applyBatch(emptyList(), mapOf(keys.deviceId to setOf(9L)))
        assertEquals(7L, store.versionVector()[keys.deviceId]) // seq 8 not accounted
    }

    @Test
    fun entriesAfterIsAscendingAndLimited() = runTest {
        val es = (1..5).map { author.summary("2026-10-0$it", it.toLong()) }
        store.applyBatch(es.shuffled())
        assertEquals(es.drop(1).take(2), store.entriesAfter(keys.deviceId, es[0].seq, 2))
    }

    @Test
    fun pruneDropsExpiredButKeepsVector() = runTest {
        val oldDetail = author.detail("2026-07-10")
        val oldSummary = author.summary("2026-07-10", 1)
        val fresh = author.detail("2026-10-09")
        store.applyBatch(listOf(genesis.entry, oldDetail, oldSummary, fresh))
        store.prune(SyncPolicy(self = keys.deviceId, today = { TODAY }))
        assertEquals(setOf(genesis.entry, oldSummary, fresh), store.liveEntries().toSet())
        assertEquals(fresh.seq, store.versionVector()[keys.deviceId])
    }

    @Test
    fun equivocatingMembershipEntryKeepsMinHexCopy() = runTest {
        fun add() = EntryAuthor(genesis.groupId, keys, lastSeq = 1).create {
            val k = DeviceKeys.generate()
            it.copy(membership_op = MembershipOp(add = AddMember(k.sign.publicKey.toByteString(), k.dh.publicKey.toByteString(), "x")))
        }
        val (x, y) = add() to add() // same author and seq 2, different bytes
        val min = listOf(x, y).minBy { it.bytes.toHex() }
        for (order in listOf(listOf(x, y), listOf(y, x))) {
            val viaBatch = InMemoryEntryStore(genesis.groupId)
            order.forEach { viaBatch.applyBatch(listOf(it)) }
            assertEquals(listOf(min), viaBatch.membershipEntries())
            val viaOffer = InMemoryEntryStore(genesis.groupId)
            order.forEach { viaOffer.storeMembership(listOf(it)) }
            assertEquals(listOf(min), viaOffer.membershipEntries())
        }
    }

    @Test
    fun vectorShortHashIsStableAndOrderFree() {
        val a = DeviceKeys.generate().deviceId
        val b = DeviceKeys.generate().deviceId
        val v1 = VersionVector(linkedMapOf(a to 1L, b to 2L))
        val v2 = VersionVector(linkedMapOf(b to 2L, a to 1L))
        assertEquals(4, v1.shortHash().size)
        assertTrue(v1.shortHash().contentEquals(v2.shortHash()))
        assertFalse(v1.shortHash().contentEquals(VersionVector(mapOf(a to 1L)).shortHash()))
        assertEquals(v1, VersionVector.fromProto(v1.toProto()))
    }

    @Test
    fun policyExpiryAndVisibility() {
        val policy = SyncPolicy(self = keys.deviceId, showOthers = false, today = { TODAY })
        assertTrue(policy.isExpired(author.detail("2026-07-10")))      // 91 days
        assertFalse(policy.isExpired(author.detail("2026-07-11")))     // 90 days
        assertFalse(policy.isExpired(author.summary("2026-07-10", 1)))
        assertTrue(policy.acceptsFromPeers(author.summary("2026-10-09", 1)))
        val stranger = EntryAuthor(genesis.groupId, DeviceKeys.generate(), 0)
        assertFalse(policy.acceptsFromPeers(stranger.summary("2026-10-09", 1)))
    }
}
