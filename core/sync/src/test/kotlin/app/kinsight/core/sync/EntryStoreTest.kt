package app.kinsight.core.sync

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
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
        store.applyBatch(listOf(e))
        store.applyBatch(listOf(e))
        assertEquals(2L, store.versionVector()[keys.deviceId])
        assertEquals(listOf(e), store.liveEntries())
    }

    @Test
    fun olderSupersededVersionArrivingLaterIsIgnored() = runTest {
        val old = author.summary("2026-10-09", 10)
        val new = author.summary("2026-10-09", 20)
        store.applyBatch(listOf(new))
        store.applyBatch(listOf(old))
        assertEquals(listOf(new), store.liveEntries())
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
    fun seenOnlyBumpsVectorWithoutStoring() = runTest {
        store.applyBatch(emptyList(), mapOf(keys.deviceId to 7L))
        assertEquals(7L, store.versionVector()[keys.deviceId])
        assertTrue(store.liveEntries().isEmpty())
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
        store.applyBatch(listOf(oldDetail, oldSummary, fresh))
        store.prune(SyncPolicy(self = keys.deviceId, today = { TODAY }))
        assertEquals(setOf(oldSummary, fresh), store.liveEntries().toSet())
        assertEquals(fresh.seq, store.versionVector()[keys.deviceId])
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
