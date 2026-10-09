package app.kinsight.core.sync

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.MembershipOp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okio.ByteString.Companion.toByteString

class Node(
    val name: String,
    val keys: DeviceKeys,
    val author: EntryAuthor,
    var store: EntryStore,
    var policy: SyncPolicy,
)

/** Node 0 creates the group and adds the others; every node starts with the full membership. */
suspend fun buildGroup(size: Int, showOthers: (Int) -> Boolean = { true }): List<Node> {
    var now = 1_000L
    val clock = { ++now }
    val creatorKeys = DeviceKeys.generate()
    val genesis = GroupGenesis.create(creatorKeys, "node0", clock)
    val creator = EntryAuthor(genesis.groupId, creatorKeys, lastSeq = 1, clock = clock)
    val otherKeys = (1 until size).map { DeviceKeys.generate() }
    val adds = otherKeys.mapIndexed { i, k ->
        creator.create {
            it.copy(membership_op = MembershipOp(add = AddMember(k.sign.publicKey.toByteString(), k.dh.publicKey.toByteString(), "node${i + 1}")))
        }
    }
    val membership = listOf(genesis.entry) + adds
    val nodes = mutableListOf<Node>()
    for (i in 0 until size) {
        val keys = if (i == 0) creatorKeys else otherKeys[i - 1]
        val author = if (i == 0) creator else EntryAuthor(genesis.groupId, keys, lastSeq = 0, clock = clock)
        val store = InMemoryEntryStore(genesis.groupId)
        store.storeMembership(membership)
        if (i == 0) store.applyBatch(membership)
        nodes += Node("node$i", keys, author, store, SyncPolicy(keys.deviceId, showOthers(i), today = { TODAY }))
    }
    return nodes
}

suspend fun Node.publish(vararg entries: app.kinsight.core.model.DecodedEntry) = store.applyBatch(entries.toList())

suspend fun syncPair(a: Node, b: Node, faults: LinkFaults = LinkFaults()): Pair<SessionResult, SessionResult> = coroutineScope {
    val (sa, sb) = MemoryPipe.pair(faults)
    val prologue = SecureChannel.prologue(a.store.groupId, PROTOCOL_VERSION)
    val ra = async {
        runCatching { SyncSession(a.store, a.policy).run(SecureChannel.initiate(sa, a.keys.dh, prologue)) }
            .getOrElse { SessionResult.Failed(it) }
    }
    val rb = async {
        runCatching { SyncSession(b.store, b.policy).run(SecureChannel.accept(sb, b.keys.dh, prologue)) }
            .getOrElse { SessionResult.Failed(it) }
    }
    ra.await() to rb.await()
}
