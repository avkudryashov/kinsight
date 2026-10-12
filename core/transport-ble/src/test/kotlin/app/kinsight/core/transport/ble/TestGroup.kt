package app.kinsight.core.transport.ble

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.sync.InMemoryEntryStore
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.TODAY
import okio.ByteString.Companion.toByteString

class TestDevice(val name: String, val keys: DeviceKeys, val author: EntryAuthor, val store: InMemoryEntryStore)

fun TestDevice.policy() = SyncPolicy(keys.deviceId, today = { TODAY })

/** Device 0 creates the group and adds the others; every store starts with the whole membership accounted for. */
class TestGroup(val groupId: GroupId, val discoveryKey: ByteArray, val devices: List<TestDevice>) {
    companion object {
        suspend fun create(size: Int): TestGroup {
            val creatorKeys = DeviceKeys.generate()
            val genesis = GroupGenesis.create(creatorKeys, "d0")
            val creator = EntryAuthor(genesis.groupId, creatorKeys, lastSeq = 1, lastLamport = genesis.entry.lamport)
            val others = (1 until size).map { DeviceKeys.generate() }
            val adds = others.mapIndexed { i, k ->
                creator.create {
                    it.copy(membership_op = MembershipOp(add = AddMember(k.sign.publicKey.toByteString(), k.dh.publicKey.toByteString(), "d${i + 1}")))
                }
            }
            val membership = listOf(genesis.entry) + adds
            val devices = (0 until size).map { i ->
                val keys = if (i == 0) creatorKeys else others[i - 1]
                val store = InMemoryEntryStore(genesis.groupId).also { it.applyBatch(membership) }
                val author = if (i == 0) creator else EntryAuthor(genesis.groupId, keys, lastSeq = 0)
                TestDevice("d$i", keys, author, store)
            }
            return TestGroup(genesis.groupId, Primitives.randomBytes(32), devices)
        }
    }
}
