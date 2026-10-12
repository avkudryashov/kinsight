package app.kinsight.core.transport.ble

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.usage.UsageSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncPoliciesTest {
    private val momKeys = DeviceKeys.generate()
    private val kidKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1, lastLamport = genesis.entry.lamport)
    private val add = mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)
    private val state = Membership.compute(genesis.groupId, listOf(genesis.entry, add))

    @Test
    fun childSyncPolicyRefusesForeignEntries() {
        val p = SyncPolicies.forDevice(state, kidKeys.deviceId, UsageSettings(detailRetentionDays = 30, summaryRetentionDays = 200))
        assertFalse(p.showOthers)
        assertFalse(p.acceptsFromPeers(mom.create { it.copy(heartbeat = Heartbeat(app_version = "t")) }))
        assertEquals(30L to 200L, p.detailRetentionDays to p.summaryRetentionDays)
    }

    @Test
    fun parentAndUnknownMembershipShowOthers() {
        assertTrue(SyncPolicies.forDevice(state, momKeys.deviceId, UsageSettings()).showOthers)
        assertTrue(SyncPolicies.forDevice(null, momKeys.deviceId, UsageSettings()).showOthers)
    }
}
