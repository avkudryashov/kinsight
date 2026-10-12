package app.kinsight.core.model

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.RotateDiscoveryKey
import okio.ByteString
import okio.ByteString.Companion.toByteString

data class Member(
    val id: DeviceId,
    val signPublicKey: ByteString,
    val dhPublicKey: ByteString,
    val displayName: String,
    val isAdmin: Boolean,
    val role: DeviceRole = DeviceRole.DEVICE_ROLE_UNSPECIFIED,
) {
    /** Only a CHILD shares its own usage (spec §4.8); unspecified reads as a parent. */
    val isChild: Boolean get() = role == DeviceRole.CHILD
}

data class Departed(val member: Member, val lastSeq: Long)

data class MembershipState(
    val groupId: GroupId,
    val active: Map<DeviceId, Member>,
    val departed: Map<DeviceId, Departed>,
    val discoveryRotation: RotateDiscoveryKey?,
    val effectiveEntries: Set<DecodedEntry>,
) {
    fun signKeyOf(id: DeviceId): ByteArray? =
        (active[id] ?: departed[id]?.member)?.signPublicKey?.toByteArray()

    fun roleOf(id: DeviceId): DeviceRole? = (active[id] ?: departed[id]?.member)?.role

    fun acceptsEntryFrom(id: DeviceId, seq: Long): Boolean =
        id in active || departed[id]?.let { seq <= it.lastSeq } == true

    /**
     * Highest Lamport value among effective membership ops (admin-authored): the clock an author should observe.
     * Leaves are excluded: any member may leave with an arbitrary valid value, and a departed id cannot come
     * back, while the per-author rule already orders a leaver's own ops.
     */
    val maxLamport: Long get() = effectiveEntries.filter { it.body.membership_op != null }.maxOfOrNull { it.lamport } ?: 0L

    fun activeMemberByDhKey(dhPublicKey: ByteArray): Member? {
        val key = dhPublicKey.toByteString()
        return active.values.firstOrNull { it.dhPublicKey == key }
    }
}

/**
 * Deterministic membership from an unordered set of signed entries.
 * Replay order: (Lamport key, author, seq), where an author's key is the running max of its own Lamport values
 * in seq order, so an author's higher seq never replays before its lower seq. Wall-clock time is not used.
 * Removal wins over addition and is permanent.
 * Cutoffs found in one pass void later-seq ops of removed or demoted authors in the next pass.
 */
object Membership {

    /** Final member/admin cutoffs actually used for a result (fixed point or settled cycle star). */
    internal data class Cutoffs(
        val member: Map<DeviceId, Long> = emptyMap(),
        val admin: Map<DeviceId, Long> = emptyMap(),
    )

    private class Pass(val state: MembershipState, val cutoffs: Cutoffs)

    /**
     * Membership-relevant entries of the group within MembershipLimits whose signature verifies against a key
     * introduced by some create/add op, with equivocations (same author+seq) reduced to the min-hex copy.
     * Signatures are checked before deduplication so a forged copy can never displace a genuine entry.
     */
    fun relevant(groupId: GroupId, entries: Collection<DecodedEntry>): List<DecodedEntry> {
        val candidates = entries.filter { it.groupId == groupId && it.isMembershipRelevant && MembershipLimits.withinLimits(it) }
        val keys = HashMap<DeviceId, ByteArray>()
        for (e in candidates) {
            val op = e.body.membership_op ?: continue
            val pk = (op.create?.sign_public_key ?: op.add?.sign_public_key)?.toByteArray() ?: continue
            if (pk.size == 32) keys[DeviceId.fromSignPublicKey(pk)] = pk
        }
        return candidates
            .filter { e -> keys[e.author]?.let { e.verifySignature(it) } == true }
            .groupBy { it.author to it.seq }
            .map { (_, same) -> same.minBy { it.bytes.toHex() } }
    }

    fun hash(groupId: GroupId, entries: Collection<DecodedEntry>): ByteArray =
        Primitives.sha256(*relevant(groupId, entries).map { it.bytes }.sortedBy { it.toHex() }.toTypedArray())

    /** A computed state together with the cutoffs it was derived under. Test hook. */
    internal data class Detailed(val state: MembershipState, val cutoffs: Cutoffs)

    fun compute(groupId: GroupId, entries: Collection<DecodedEntry>): MembershipState? =
        computeDetailed(groupId, entries)?.state

    internal fun computeDetailed(groupId: GroupId, entries: Collection<DecodedEntry>): Detailed? {
        val ops = relevant(groupId, entries)
        val genesis = ops.filter { isValidGenesis(groupId, it) }.minByOrNull { it.bytes.toHex() } ?: return null
        val ordered = replayOrder(ops.filter { it != genesis })
        // Each pass derives cutoffs only from ops effective under the previous cutoffs.
        // A fixed point is the answer; a cycle is resolved by the min-union of the cycle's cutoffs.
        val seen = mutableListOf<Cutoffs>()
        var cutoffs = Cutoffs()
        while (true) {
            val at = seen.indexOf(cutoffs)
            if (at >= 0) {
                val cycle = seen.subList(at, seen.size)
                if (cycle.size == 1) return Detailed(replay(groupId, genesis, ordered, cutoffs).state, cutoffs)
                var star = cycle.reduce(::merge)
                while (true) { // ops effective only under star may add or lower cutoffs; monotone, so it terminates
                    val next = merge(star, replay(groupId, genesis, ordered, star).cutoffs)
                    if (next == star) break
                    star = next
                }
                return Detailed(settle(replay(groupId, genesis, ordered, star), star), star)
            }
            seen += cutoffs
            cutoffs = replay(groupId, genesis, ordered, cutoffs).cutoffs
        }
    }

    private fun replayOrder(ops: List<DecodedEntry>): List<DecodedEntry> {
        val key = HashMap<DecodedEntry, Long>()
        for ((_, own) in ops.groupBy { it.author }) {
            var running = 0L
            for (e in own.sortedBy { it.seq }) {
                running = maxOf(running, e.lamport)
                key[e] = running
            }
        }
        return ops.sortedWith(compareBy<DecodedEntry>({ key.getValue(it) }, { it.author.hex }, { it.seq }))
    }

    /** Makes a state replayed under merged cycle cutoffs consistent with those cutoffs. */
    private fun settle(pass: Pass, star: Cutoffs): MembershipState {
        val s = pass.state
        val active = s.active.toMutableMap()
        val departed = s.departed.toMutableMap()
        for ((id, cut) in star.member) {
            val gone = active.remove(id)
            if (gone != null) {
                departed[id] = Departed(gone, cut)
            } else {
                departed[id]?.let { departed[id] = it.copy(lastSeq = minOf(it.lastSeq, cut)) }
            }
        }
        for (id in star.admin.keys) active[id]?.let { active[id] = it.copy(isAdmin = false) }
        return s.copy(active = active, departed = departed)
    }

    private fun mergeMin(a: Map<DeviceId, Long>, b: Map<DeviceId, Long>): Map<DeviceId, Long> {
        val out = a.toMutableMap()
        for ((k, v) in b) out[k] = minOf(out[k] ?: v, v)
        return out
    }

    private fun merge(a: Cutoffs, b: Cutoffs) = Cutoffs(mergeMin(a.member, b.member), mergeMin(a.admin, b.admin))

    private fun isValidGenesis(groupId: GroupId, e: DecodedEntry): Boolean {
        val create = e.body.membership_op?.create ?: return false
        val pk = create.sign_public_key.toByteArray()
        if (pk.size != 32 || create.dh_public_key.size != 32) return false
        return e.seq == 1L &&
            e.author == DeviceId.fromSignPublicKey(pk) &&
            GroupId.derive(pk, create.salt.toByteArray()) == groupId &&
            e.verifySignature(pk) &&
            create.role != DeviceRole.CHILD
    }

    private fun idOrNull(bytes: ByteString): DeviceId? =
        if (bytes.size == 16) DeviceId.fromBytes(bytes.toByteArray()) else null

    private fun replay(groupId: GroupId, genesis: DecodedEntry, ordered: List<DecodedEntry>, cutoffs: Cutoffs): Pass {
        val create = genesis.body.membership_op!!.create!!
        val active = linkedMapOf(
            genesis.author to Member(genesis.author, create.sign_public_key, create.dh_public_key, create.display_name, isAdmin = true, role = create.role),
        )
        val departed = mutableMapOf<DeviceId, Departed>()
        val removedForever = mutableSetOf<DeviceId>()
        val memberCut = mutableMapOf<DeviceId, Long>()
        val adminCut = mutableMapOf<DeviceId, Long>()
        val demoted = mutableSetOf<DeviceId>()
        var rotation: RotateDiscoveryKey? = null
        val effective = mutableSetOf(genesis)

        for (e in ordered) {
            val cut = cutoffs.member[e.author]
            if (cut != null && e.seq > cut) continue
            val author = active[e.author] ?: continue

            if (e.body.state_event != null) { // LEFT_GROUP
                active.remove(e.author)
                departed[e.author] = Departed(author, e.seq)
                removedForever += e.author
                memberCut[e.author] = minOf(memberCut[e.author] ?: e.seq, e.seq)
                effective += e
                continue
            }

            val op = e.body.membership_op ?: continue
            if (!author.isAdmin) continue
            val adminCutoff = cutoffs.admin[e.author]
            if (adminCutoff != null && e.seq > adminCutoff) continue

            val applied = when {
                op.add != null -> {
                    val add = op.add!!
                    val pk = add.sign_public_key.toByteArray()
                    if (pk.size != 32 || add.dh_public_key.size != 32) {
                        false
                    } else {
                        val id = DeviceId.fromSignPublicKey(pk)
                        if (id in active || id in removedForever || active.values.any { it.dhPublicKey == add.dh_public_key }) {
                            false
                        } else {
                            active[id] = Member(id, add.sign_public_key, add.dh_public_key, add.display_name, isAdmin = false, role = add.role)
                            true
                        }
                    }
                }
                op.remove != null -> {
                    val id = idOrNull(op.remove!!.device_id)
                    if (id == null) {
                        false
                    } else {
                        removedForever += id
                        // The genesis entry (seq 1) is always effective, so its author's cutoff is never below 1.
                        val lastSeq = if (id == genesis.author) maxOf(1L, op.remove!!.last_seq) else op.remove!!.last_seq
                        memberCut[id] = minOf(memberCut[id] ?: lastSeq, lastSeq)
                        val gone = active.remove(id)
                        if (gone != null) {
                            departed[id] = Departed(gone, lastSeq)
                        } else {
                            departed[id]?.let { departed[id] = it.copy(lastSeq = minOf(it.lastSeq, lastSeq)) }
                        }
                        true
                    }
                }
                op.set_admin != null -> {
                    val sa = op.set_admin!!
                    val id = idOrNull(sa.device_id)
                    val m = id?.let { active[it] }
                    if (m == null || (sa.admin && (id in demoted || m.isChild))) {
                        false
                    } else {
                        active[id] = m.copy(isAdmin = sa.admin)
                        if (!sa.admin && m.isAdmin) {
                            demoted += id
                            adminCut[id] = minOf(adminCut[id] ?: sa.last_seq, sa.last_seq)
                        }
                        true
                    }
                }
                op.rotate != null -> {
                    val r = op.rotate!!
                    if (rotation == null || r.epoch > rotation!!.epoch) {
                        rotation = r
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
            if (applied) effective += e
        }
        val state = MembershipState(groupId, active.toMap(), departed.toMap(), rotation, effective.toSet())
        return Pass(state, Cutoffs(memberCut.toMap(), adminCut.toMap()))
    }
}
