package app.kinsight.core.transport.cloud

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.StorageOwnerState
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.sync.CLOUD_PROTOCOL_VERSION
import okio.ByteString.Companion.toByteString

/** Rulings Q14: [owner]'s newest seq this device has seen, and when (this device's clock) it first saw it. */
data class OwnerHeard(val owner: DeviceId, val seq: Long, val heardAtMs: Long)

/** Why this phone may not leave yet (spec §7.1): it holds the storage, or its old storage is still in use. */
enum class OwnerLeaveBlock { HANDOVER_NEEDED, SWITCHING }

/**
 * The storage owner's lifecycle as the signed log states it (spec §7). Pure: callers pass the membership and the
 * StorageHandover and Heartbeat entries they hold. Whether the token is exposed is causal (the departures the signer of
 * the token knew of), never the replay order of ops: a leaver picks its own Lamport value.
 */
object OwnerLifecycle {
    /** Spec §7.2 «Владелец недоступен»: nothing heard from the owner for this long (or it departed). */
    const val UNAVAILABLE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    private fun ownerOps(m: MembershipState): List<DecodedEntry> =
        m.effectiveEntries.filter { it.body.membership_op?.storage_owner != null }
            .sortedBy { it.body.membership_op!!.storage_owner!!.generation }

    /** The effective StorageOwner ops with their states, by generation (Membership keeps them strictly increasing). */
    private fun ownerStates(m: MembershipState): List<Pair<DecodedEntry, StorageOwnerState>> = ownerOps(m).map { e ->
        val so = e.body.membership_op!!.storage_owner!!
        e to StorageOwnerState.of(DeviceId.fromBytes(so.device_id.toByteArray()), so)
    }

    /** The StorageOwner in force right before the current one, or null. */
    fun previousOwner(m: MembershipState): StorageOwnerState? {
        val current = m.storageOwner ?: return null
        return ownerStates(m).map { it.second }.lastOrNull { it.generation < current.generation }
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

    /**
     * Rulings Q14: when this device last saw [owner]'s log grow, on its own clock. A first sighting of an owner (or another
     * owner) counts as heard [nowMs], so the week of §7.2 starts when this device began to track it; a [newestSeq] above
     * the one seen before is heard [nowMs]; anything else keeps [previous]. The owner's own stamps never enter.
     */
    fun ownerHeard(previous: OwnerHeard?, owner: DeviceId, newestSeq: Long, nowMs: Long): OwnerHeard = when {
        previous == null || previous.owner != owner || newestSeq > previous.seq -> OwnerHeard(owner, newestSeq, nowMs)
        else -> previous
    }

    /**
     * Spec §7.2: the owner departed, or this device has not seen its log grow for [UNAVAILABLE_AFTER_MS]. [ownerHeardAtMs]
     * is [OwnerHeard.heardAtMs] for the current owner, on this device's clock like [nowMs] (rulings Q14); null (never
     * tracked here) is available.
     */
    fun ownerUnavailable(m: MembershipState, ownerHeardAtMs: Long?, nowMs: Long): Boolean {
        val owner = m.storageOwner ?: return false
        if (owner.deviceId !in m.active) return true
        return ownerHeardAtMs != null && nowMs - ownerHeardAtMs >= UNAVAILABLE_AFTER_MS
    }

    /** An active admin parent other than the owner, asked by the owner (§7.1) or with the owner unavailable (§7.2, §7.3). */
    fun canTakeOver(m: MembershipState, self: DeviceId, handovers: List<DecodedEntry>, ownerHeardAtMs: Long?, nowMs: Long): Boolean {
        val owner = m.storageOwner ?: return false
        val me = m.active[self] ?: return false
        if (owner.deviceId == self || !me.isAdmin || me.isChild) return false
        return handoverTo(m, handovers) == self || ownerUnavailable(m, ownerHeardAtMs, nowMs)
    }

    /**
     * Spec §7.4, rulings Q3: the token in use may be known to a departed device. The op that issued the token (the op in
     * force, or for the same non-empty token_id the first op that carried it) lists the departures its signer knew of;
     * any other departed member counts as exposed, whatever its replay position, so an unseen or concurrent departure
     * errs on the safe side. The owner then gets a new token. Not exposing (§7.3, 0 taps): the previous owner of the same
     * token and storage leaving with LEFT_GROUP after a transfer it signed (it signed the new owner's op, or added it or
     * made it admin after its own StorageOwner op).
     */
    fun tokenExposed(m: MembershipState): Boolean {
        val owner = m.storageOwner ?: return false
        val ops = ownerStates(m)
        val current = ops.indexOfLast { it.second.generation == owner.generation }
        if (current < 0) return false
        var issuer = current
        if (owner.tokenId.size > 0) while (issuer > 0 && ops[issuer - 1].second.tokenId == owner.tokenId) issuer--
        val known = ops[issuer].second.departed
        return m.departed.keys.any { d -> d !in known && !handedOverAndLeft(m, ops, issuer, current, d) }
    }

    /** §7.3: [d] owned this token on this storage, signed its transfer to the next owner, then left by itself. */
    private fun handedOverAndLeft(m: MembershipState, ops: List<Pair<DecodedEntry, StorageOwnerState>>, issuer: Int, current: Int, d: DeviceId): Boolean {
        val gone = departures(m, d)
        if (gone.none { it.author == d && it.body.state_event?.kind == StateEvent.Kind.LEFT_GROUP }) return false
        if (gone.any { it.body.membership_op?.remove != null }) return false
        return (issuer + 1..current).any { j ->
            val (prev, next) = ops[j - 1] to ops[j]
            prev.second.deviceId == d && prev.second.storageId == next.second.storageId &&
                (next.first.author == d || vouched(m, next.second.deviceId, by = d, after = prev.first))
        }
    }

    /**
     * [by] signed the transfer to [id] (Y3 parked minor): an AddMember or a GrantAdmin of [id] signed by [by] after
     * [after], [by]'s own StorageOwner op (its seq, so causally after). An admin added long before is no transfer.
     */
    private fun vouched(m: MembershipState, id: DeviceId, by: DeviceId, after: DecodedEntry): Boolean {
        if (after.author != by) return false
        return m.effectiveEntries.any { e ->
            val op = e.body.membership_op
            e.author == by && e.seq > after.seq && (
                op?.add?.sign_public_key?.let { pk -> pk.size == 32 && DeviceId.fromSignPublicKey(pk.toByteArray()) == id } == true ||
                    op?.set_admin?.let { g -> g.admin && g.device_id.size == 16 && DeviceId.fromBytes(g.device_id.toByteArray()) == id } == true
                )
        }
    }

    /**
     * True when every active member but [self] reports, in its newest Heartbeat, a storage generation above [generation].
     * A member whose newest Heartbeat speaks a protocol older than [CLOUD_PROTOCOL_VERSION] is not waited for: that build
     * has no cloud code and never uses a token. A member without any Heartbeat yet still is.
     */
    fun allSwitched(m: MembershipState, heartbeats: List<DecodedEntry>, generation: Long, self: DeviceId): Boolean {
        val newest = heartbeats.filter { it.kind == EntryKind.HEARTBEAT }.groupBy { it.author }.mapValues { (_, b) -> b.maxBy { it.seq } }
        return m.active.keys.filter { it != self }.all { id ->
            val beat = newest[id]?.body?.heartbeat
            beat != null && (beat.protocol_version < CLOUD_PROTOCOL_VERSION || beat.storage_generation > generation)
        }
    }

    /**
     * Spec §7.1 «есть другой родитель»: the owner does not leave while any other parent could take the storage (one not
     * yet an admin is made one by the handover request, spec §3: the owner is an admin parent); a former owner whose
     * storage moved to another Disk leaves once every phone switched (its old token is revoked then).
     */
    fun leaveBlock(m: MembershipState, self: DeviceId, heartbeats: List<DecodedEntry>): OwnerLeaveBlock? {
        val owner = m.storageOwner ?: return null
        if (self !in m.active) return null
        if (owner.deviceId == self && m.active.values.any { it.id != self && !it.isChild }) return OwnerLeaveBlock.HANDOVER_NEEDED
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
}
