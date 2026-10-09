package app.kinsight.core.sync

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VectorAndPolicyTest {
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "a")
    private val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1)

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
