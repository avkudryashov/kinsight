package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.InMemoryEntryStore
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.TODAY
import app.kinsight.core.sync.observedLamport
import kotlinx.coroutines.runBlocking

class Phone(
    val name: String,
    val keys: DeviceKeys,
    val author: EntryAuthor,
    val store: InMemoryEntryStore,
    val isChild: Boolean,
    val ring: InMemoryKeyRing,
    val cursor: InMemoryCursorStore = InMemoryCursorStore(),
) {
    val id get() = keys.deviceId

    fun sync(disk: DiskApi, now: () -> Long = { 1_000L }, maxPackEntries: Int = CloudSync.MAX_PACK_ENTRIES) = CloudSync(
        store, keys, SyncPolicy(keys.deviceId, showOthers = !isChild, today = { TODAY }), disk, ring, cursor, now,
        maxPackEntries = maxPackEntries,
    )

    suspend fun publish(vararg e: DecodedEntry) = store.applyBatch(e.toList())

    suspend fun membership(): MembershipState = Membership.compute(store.groupId, store.membershipEntries())!!
}

/** The generation 0 group key every phone of [family] got at pairing. */
val GEN0: ByteArray = Primitives.randomBytes(32)

/**
 * Phone 0 is the parent who created the group and added the others; every phone starts with the full membership, an
 * empty log of its own and generation 0 of the group key. [roles] after the first: CHILD or PARENT.
 */
suspend fun family(vararg roles: DeviceRole): List<Phone> {
    val creatorKeys = DeviceKeys.generate()
    val genesis = GroupGenesis.create(creatorKeys, "Parent")
    val keys = listOf(creatorKeys) + roles.map { DeviceKeys.generate() }
    val stores = keys.map { InMemoryEntryStore(genesis.groupId) }
    val creator = EntryAuthor(
        genesis.groupId, creatorKeys, lastSeq = 1, lastLamport = genesis.entry.lamport,
        observedLamport = { runBlocking { stores[0].observedLamport() } },
    )
    val adds = roles.mapIndexed { i, role -> creator.addMember(keys[i + 1].sign.publicKey, keys[i + 1].dh.publicKey, "phone${i + 1}", role) }
    val membership = listOf(genesis.entry) + adds
    stores.forEach { it.storeMembership(membership) }
    stores[0].applyBatch(membership)
    return keys.indices.map { i ->
        val author = if (i == 0) creator else EntryAuthor(genesis.groupId, keys[i], lastSeq = 0, observedLamport = { runBlocking { stores[i].observedLamport() } })
        Phone("phone$i", keys[i], author, stores[i], isChild = i > 0 && roles[i - 1] == DeviceRole.CHILD, ring = InMemoryKeyRing(mapOf(0 to GEN0)))
    }
}
