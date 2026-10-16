package app.kinsight.core.sync

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.locationEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VectorAndPolicyTest {
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "a")
    private val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1)

    @Test
    fun vectorCanonicalBytesAreSortedIdsWithBigEndianSeqs() {
        val a = DeviceKeys.generate().deviceId
        val b = DeviceKeys.generate().deviceId
        val v1 = VersionVector(linkedMapOf(a to 1L, b to 2L))
        val v2 = VersionVector(linkedMapOf(b to 2L, a to 1L))
        assertEquals(2 * 24, v1.canonicalBytes().size)
        assertTrue(v1.canonicalBytes().contentEquals(v2.canonicalBytes()))
        assertFalse(v1.canonicalBytes().contentEquals(VersionVector(mapOf(a to 1L)).canonicalBytes()))
        val (low, high) = if (a < b) a to b else b to a
        val expected = low.bytes() + longBytes(v1[low]) + high.bytes() + longBytes(v1[high])
        assertTrue(expected.contentEquals(v1.canonicalBytes()))
        assertEquals(0, VersionVector.EMPTY.canonicalBytes().size)
        assertEquals(v1, VersionVector.fromProto(v1.toProto()))
    }

    private fun longBytes(v: Long) = java.nio.ByteBuffer.allocate(8).putLong(v).array()

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

    @Test
    fun aLocationExpiresByItsFixTimeAfter48Hours() {
        val now = 1_760_000_000_000L
        val hour = 3_600_000L
        val policy = SyncPolicy(self = keys.deviceId, nowMs = { now })
        fun at(ms: Long) = author.locationEntry(1, 2, 10, ms, app.kinsight.core.model.proto.Location.Provider.GPS)
        assertTrue(policy.isExpired(at(now - 48 * hour - 1)))
        assertFalse(policy.isExpired(at(now - 47 * hour)))
        assertFalse(policy.isExpired(at(now + hour)))
        assertTrue(policy.isExpired(at(now + 48 * hour + 1)), "a fix time that far ahead is garbage and would never go")
        assertEquals(48 * hour, SyncPolicy.LOCATION_RETENTION_MS)
    }
}
