package app.kinsight.core.sync

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.MembershipOp
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString

class Node(
    val name: String,
    val keys: DeviceKeys,
    val author: EntryAuthor,
    var store: EntryStore,
    var policy: SyncPolicy,
)

/**
 * Node 0 creates the group and adds the others; every node starts with the full membership.
 * Each node's author observes the Lamport values of its own (current) store, as the app does.
 */
suspend fun buildGroup(
    size: Int,
    showOthers: (Int) -> Boolean = { true },
    role: (Int) -> DeviceRole = { DeviceRole.DEVICE_ROLE_UNSPECIFIED },
): List<Node> {
    var now = 1_000L
    val clock = { ++now }
    val creatorKeys = DeviceKeys.generate()
    val genesis = GroupGenesis.create(creatorKeys, "node0", clock)
    val stores = List(size) { InMemoryEntryStore(genesis.groupId) }
    val nodes = mutableListOf<Node>()
    fun observed(i: Int): () -> Long = { runBlocking { lamportOf(nodes.getOrNull(i)?.store ?: stores[i]) } }
    val creator = EntryAuthor(
        genesis.groupId, creatorKeys, lastSeq = 1, clock = clock,
        lastLamport = genesis.entry.lamport, observedLamport = observed(0),
    )
    val otherKeys = (1 until size).map { DeviceKeys.generate() }
    val adds = otherKeys.mapIndexed { i, k ->
        creator.create {
            it.copy(membership_op = MembershipOp(add = AddMember(k.sign.publicKey.toByteString(), k.dh.publicKey.toByteString(), "node${i + 1}", role(i + 1))))
        }
    }
    val membership = listOf(genesis.entry) + adds
    for (i in 0 until size) {
        val keys = if (i == 0) creatorKeys else otherKeys[i - 1]
        val author = if (i == 0) creator else EntryAuthor(genesis.groupId, keys, lastSeq = 0, clock = clock, observedLamport = observed(i))
        val store = stores[i]
        store.storeMembership(membership)
        if (i == 0) store.applyBatch(membership)
        nodes += Node("node$i", keys, author, store, SyncPolicy(keys.deviceId, showOthers(i), today = { TODAY }))
    }
    return nodes
}

/** What an author on this store observes as the group's Lamport clock. */
suspend fun lamportOf(store: EntryStore): Long = store.observedLamport()

fun Node.lamportSource(): () -> Long = { runBlocking { lamportOf(store) } }

suspend fun Node.publish(vararg entries: app.kinsight.core.model.DecodedEntry) = store.applyBatch(entries.toList())

suspend fun syncPair(
    a: Node,
    b: Node,
    faults: LinkFaults = LinkFaults(),
    capacity: Int = Channel.UNLIMITED,
    versionA: Int = PROTOCOL_VERSION,
    versionB: Int = PROTOCOL_VERSION,
): Pair<SessionResult, SessionResult> = coroutineScope {
    val (sa, sb) = MemoryPipe.pair(faults, capacity)
    val prologue = SecureChannel.prologue(a.store.groupId)
    val ra = async {
        runCatching { SyncSession(a.store, a.policy, protocolVersion = versionA).run(SecureChannel.initiate(sa, a.keys.dh, prologue)) }
            .getOrElse { SessionResult.Failed(it) }
    }
    val rb = async {
        runCatching { SyncSession(b.store, b.policy, protocolVersion = versionB).run(SecureChannel.accept(sb, b.keys.dh, prologue)) }
            .getOrElse { SessionResult.Failed(it) }
    }
    ra.await() to rb.await()
}
