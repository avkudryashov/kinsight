package app.kinsight.core.transport.cloud

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.StorageOwnerState
import app.kinsight.core.model.proto.StateEvent
import okio.ByteString.Companion.toByteString

/** Why this phone may not leave yet (spec §7.1): it holds the storage, or its old storage is still in use. */
enum class OwnerLeaveBlock { HANDOVER_NEEDED, SWITCHING }

/**
 * The storage owner's lifecycle as the signed log states it (spec §7). Pure: callers pass the membership and the
 * StorageHandover and Heartbeat entries they hold. Order between ops is Membership's replay order (Lamport, author, seq).
 */
object OwnerLifecycle {
    /** Spec §7.2 «Владелец недоступен»: nothing heard from the owner for this long (or it departed). */
    const val UNAVAILABLE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    private fun ownerOps(m: MembershipState): List<DecodedEntry> =
        m.effectiveEntries.filter { it.body.membership_op?.storage_owner != null }
            .sortedBy { it.body.membership_op!!.storage_owner!!.generation }

    /** The StorageOwner in force right before the current one, or null. */
    fun previousOwner(m: MembershipState): StorageOwnerState? {
        val current = m.storageOwner ?: return null
        val so = ownerOps(m).map { it.body.membership_op!!.storage_owner!! }.lastOrNull { it.generation < current.generation } ?: return null
        return StorageOwnerState(DeviceId.fromBytes(so.device_id.toByteArray()), so.storage_id, so.generation)
    }

    /** Spec §7.1: the parent the current owner asked to take the current storage, or null. */
    fun handoverTo(m: MembershipState, handovers: List<DecodedEntry>): DeviceId? {
        val owner = m.storageOwner ?: return null
        val h = handovers.filter { it.kind == EntryKind.STORAGE_HANDOVER && it.author == owner.deviceId }
            .maxByOrNull { it.seq }?.body?.storage_handover ?: return null
        if (h.generation != owner.generation || h.to_device_id.size != 16) return null
        val to = DeviceId.fromBytes(h.to_device_id.toByteArray())
        val target = m.active[to] ?: return null
        return to.takeIf { it != owner.deviceId && target.isAdmin && !target.isChild }
    }

    /** The newest creation time among [owner]'s entries in [entries] (its Heartbeats and events), or null. */
    fun lastHeard(owner: DeviceId, entries: List<DecodedEntry>): Long? =
        entries.filter { it.author == owner }.maxOfOrNull { it.body.created_at_ms }

    /** Spec §7.2: the owner departed, or nothing was heard from it for [UNAVAILABLE_AFTER_MS]. Never heard of: available. */
    fun ownerUnavailable(m: MembershipState, ownerLastHeardMs: Long?, nowMs: Long): Boolean {
        val owner = m.storageOwner ?: return false
        if (owner.deviceId !in m.active) return true
        return ownerLastHeardMs != null && nowMs - ownerLastHeardMs > UNAVAILABLE_AFTER_MS
    }

    /** An active admin parent other than the owner, asked by the owner (§7.1) or with the owner unavailable (§7.2, §7.3). */
    fun canTakeOver(m: MembershipState, self: DeviceId, handovers: List<DecodedEntry>, ownerLastHeardMs: Long?, nowMs: Long): Boolean {
        val owner = m.storageOwner ?: return false
        val me = m.active[self] ?: return false
        if (owner.deviceId == self || !me.isAdmin || me.isChild) return false
        return handoverTo(m, handovers) == self || ownerUnavailable(m, ownerLastHeardMs, nowMs)
    }

    /**
     * Spec §7.4: a departed device may know the token of the current generation: its departure (LEFT_GROUP or a RemoveMember
     * naming it) replays after the StorageOwner op in force. The owner then gets a new token. Not exposing (§7.3, 0 taps):
     * the previous owner leaving with LEFT_GROUP after its role moved to another phone on the same storage.
     */
    fun tokenExposed(m: MembershipState): Boolean {
        val owner = m.storageOwner ?: return false
        val current = ownerOps(m).lastOrNull { it.body.membership_op!!.storage_owner!!.generation == owner.generation } ?: return false
        val previous = previousOwner(m)
        return m.departed.keys.any { d ->
            departures(m, d).any { e ->
                val handedOver = previous != null && d == previous.deviceId && e.body.state_event != null && previous.storageId == owner.storageId
                after(e, current) && !handedOver
            }
        }
    }

    /** True when every active member but [self] reports, in its newest Heartbeat, a storage generation above [generation]. */
    fun allSwitched(m: MembershipState, heartbeats: List<DecodedEntry>, generation: Long, self: DeviceId): Boolean {
        val newest = heartbeats.filter { it.kind == EntryKind.HEARTBEAT }.groupBy { it.author }.mapValues { (_, b) -> b.maxBy { it.seq } }
        return m.active.keys.filter { it != self }.all { id -> (newest[id]?.body?.heartbeat?.storage_generation ?: 0L) > generation }
    }

    /**
     * Spec §7.1: the owner does not leave while another admin parent could take the storage; a former owner whose storage
     * moved to another Disk leaves once every phone switched (its old token is revoked then).
     */
    fun leaveBlock(m: MembershipState, self: DeviceId, heartbeats: List<DecodedEntry>): OwnerLeaveBlock? {
        val owner = m.storageOwner ?: return null
        if (self !in m.active) return null
        if (owner.deviceId == self && m.active.values.any { it.id != self && it.isAdmin && !it.isChild }) return OwnerLeaveBlock.HANDOVER_NEEDED
        val selfBytes = self.bytes().toByteString()
        val mine = ownerOps(m).map { it.body.membership_op!!.storage_owner!! }
            .filter { it.generation < owner.generation && it.device_id == selfBytes && it.storage_id != owner.storageId }
            .maxOfOrNull { it.generation } ?: return null
        return OwnerLeaveBlock.SWITCHING.takeIf { !allSwitched(m, heartbeats, mine, self) }
    }

    private fun departures(m: MembershipState, d: DeviceId): List<DecodedEntry> = m.effectiveEntries.filter { e ->
        (e.author == d && e.body.state_event?.kind == StateEvent.Kind.LEFT_GROUP) ||
            e.body.membership_op?.remove?.device_id?.let { it.size == 16 && DeviceId.fromBytes(it.toByteArray()) == d } == true
    }

    private fun after(a: DecodedEntry, b: DecodedEntry): Boolean =
        compareValuesBy(a, b, { it.lamport }, { it.author.hex }, { it.seq }) > 0
}
