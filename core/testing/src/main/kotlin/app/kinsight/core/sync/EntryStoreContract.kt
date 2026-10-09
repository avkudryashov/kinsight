package app.kinsight.core.sync

import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour every [EntryStore] implementation must share. Subclass it in the implementation's tests and supply
 * [newStore]; JUnit creates a fresh instance per test and calls [disposeStores] after it.
 */
abstract class EntryStoreContract {
    protected abstract fun newStore(groupId: GroupId): EntryStore

    /** Releases whatever [newStore] opened (databases, temp files). */
    protected open fun disposeStores() {}

    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "a")
    private val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1)
    private val store: EntryStore by lazy { newStore(genesis.groupId) }

    @AfterTest
    fun tearDownStores() = disposeStores()

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
    fun observedLamportComesOnlyFromEffectiveMembership() = runTest {
        assertEquals(0L, store.observedLamport())
        store.storeMembership(listOf(genesis.entry))
        assertEquals(genesis.entry.lamport, store.observedLamport())
        // A usage entry with a huge Lamport value is stored but not observed.
        val high = EntryAuthor(genesis.groupId, keys, lastSeq = 1, observedLamport = { 1_000_000L }).summary("2026-10-09", 1)
        store.applyBatch(listOf(high))
        assertEquals(genesis.entry.lamport, store.observedLamport())
        // An effective admin op is observed.
        val k = DeviceKeys.generate()
        val add = EntryAuthor(genesis.groupId, keys, lastSeq = 2, observedLamport = { 41L }).create {
            it.copy(membership_op = MembershipOp(add = AddMember(k.sign.publicKey.toByteString(), k.dh.publicKey.toByteString(), "k")))
        }
        store.applyBatch(listOf(add))
        assertEquals(42L, store.observedLamport())
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
            val viaBatch = newStore(genesis.groupId)
            order.forEach { viaBatch.applyBatch(listOf(it)) }
            assertEquals(listOf(min), viaBatch.membershipEntries())
            val viaOffer = newStore(genesis.groupId)
            order.forEach { viaOffer.storeMembership(listOf(it)) }
            assertEquals(listOf(min), viaOffer.membershipEntries())
        }
    }

    // --- added with the Room implementation: pin behaviour the in-memory store had implicitly ---

    @Test
    fun liveEntriesAreOrderedByAuthorHexThenSeq() = runTest {
        val otherAuthor = EntryAuthor(genesis.groupId, DeviceKeys.generate(), lastSeq = 0)
        val mine = author.summary("2026-10-09", 1)
        val theirs = (1..3).map { otherAuthor.summary("2026-10-0$it", it.toLong()) }
        val all: List<DecodedEntry> = theirs + genesis.entry + mine
        store.applyBatch(all.shuffled())
        assertEquals(all.sortedWith(compareBy({ it.author.hex }, { it.seq })), store.liveEntries())
    }

    @Test
    fun duplicateInsideOneBatchIsStoredOnce() = runTest {
        val e = author.summary("2026-10-09", 1)
        store.applyBatch(listOf(genesis.entry, e, e))
        assertEquals(listOf(genesis.entry, e), store.liveEntries())
        assertEquals(2L, store.versionVector()[keys.deviceId])
    }

    @Test
    fun nonPositiveSeenSeqsAreIgnored() = runTest {
        store.applyBatch(emptyList(), mapOf(keys.deviceId to setOf(0L, -5L)))
        assertEquals(VersionVector.EMPTY, store.versionVector())
    }

    @Test
    fun completeAuthorStartsVectorForUnknownAuthor() = runTest {
        val stranger = DeviceKeys.generate().deviceId
        store.completeAuthor(stranger, 3)
        assertEquals(3L, store.versionVector()[stranger])
        assertTrue(store.isAccounted(stranger, 3))
        assertFalse(store.isAccounted(stranger, 4))
    }

    @Test
    fun leaveEventCountsAsMembershipEntry() = runTest {
        val leave = author.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.LEFT_GROUP)) }
        val heartbeat = author.create { it.copy(heartbeat = Heartbeat(app_version = "1", protocol_version = 1)) }
        store.applyBatch(listOf(genesis.entry, leave, heartbeat))
        assertEquals(setOf(genesis.entry, leave), store.membershipEntries().toSet())
    }

    @Test
    fun entriesAfterIsPerAuthor() = runTest {
        val otherAuthor = EntryAuthor(genesis.groupId, DeviceKeys.generate(), lastSeq = 0)
        val theirs = otherAuthor.summary("2026-10-09", 1)
        val mine = author.summary("2026-10-09", 2)
        store.applyBatch(listOf(genesis.entry, theirs, mine))
        assertEquals(listOf(mine), store.entriesAfter(keys.deviceId, 1, 10))
        assertEquals(listOf(theirs), store.entriesAfter(otherAuthor.keys.deviceId, 0, 10))
    }

    @Test
    fun sparseAccountingSurvivesSeveralGaps() = runTest {
        store.applyBatch(emptyList(), mapOf(keys.deviceId to setOf(2L, 4L, 6L)))
        assertEquals(0L, store.versionVector()[keys.deviceId])
        assertTrue(store.isAccounted(keys.deviceId, 4))
        store.applyBatch(listOf(genesis.entry))
        assertEquals(2L, store.versionVector()[keys.deviceId])
        store.applyBatch(emptyList(), mapOf(keys.deviceId to setOf(3L)))
        assertEquals(4L, store.versionVector()[keys.deviceId])
        store.completeAuthor(keys.deviceId, 5)
        assertEquals(6L, store.versionVector()[keys.deviceId])
        assertFalse(store.isAccounted(keys.deviceId, 7))
    }
}
