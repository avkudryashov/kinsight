package app.kinsight.core.model

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.proto.RotateDiscoveryKey
import okio.ByteString
import okio.ByteString.Companion.toByteString

data class Member(
    val id: DeviceId,
    val signPublicKey: ByteString,
    val dhPublicKey: ByteString,
    val displayName: String,
    val isAdmin: Boolean,
)

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

    fun acceptsEntryFrom(id: DeviceId, seq: Long): Boolean =
        id in active || departed[id]?.let { seq <= it.lastSeq } == true

    fun activeMemberByDhKey(dhPublicKey: ByteArray): Member? {
        val key = dhPublicKey.toByteString()
        return active.values.firstOrNull { it.dhPublicKey == key }
    }
}

/**
 * Deterministic membership from an unordered set of signed entries.
 * Replay order: (created_at_ms, author, seq). Removal wins over addition and is permanent.
 * Cutoffs found in one pass void later-seq ops of removed or demoted authors in the next pass.
 */
object Membership {

    private data class Cutoffs(
        val member: Map<DeviceId, Long> = emptyMap(),
        val admin: Map<DeviceId, Long> = emptyMap(),
    )

    private class Pass(val state: MembershipState, val cutoffs: Cutoffs)

    /**
     * Membership-relevant entries of the group whose signature verifies against a key
     * introduced by some create/add op, with equivocations (same author+seq) reduced to the min-hex copy.
     * Signatures are checked before deduplication so a forged copy can never displace a genuine entry.
     */
    fun relevant(groupId: GroupId, entries: Collection<DecodedEntry>): List<DecodedEntry> {
        val candidates = entries.filter { it.groupId == groupId && it.isMembershipRelevant }
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

    fun compute(groupId: GroupId, entries: Collection<DecodedEntry>): MembershipState? {
        val ops = relevant(groupId, entries)
        val genesis = ops.filter { isValidGenesis(groupId, it) }.minByOrNull { it.bytes.toHex() } ?: return null
        val ordered = ops.filter { it != genesis }
            .sortedWith(compareBy<DecodedEntry>({ it.body.created_at_ms }, { it.author.hex }, { it.seq }))
        // Each pass derives cutoffs only from ops effective under the previous cutoffs.
        // A fixed point is the answer; a cycle is resolved by the min-union of the cycle's cutoffs.
        val seen = mutableListOf<Cutoffs>()
        var cutoffs = Cutoffs()
        while (true) {
            val at = seen.indexOf(cutoffs)
            if (at >= 0) {
                val cycle = seen.subList(at, seen.size)
                if (cycle.size == 1) return replay(groupId, genesis, ordered, cutoffs).state
                val star = cycle.reduce(::merge)
                return settle(replay(groupId, genesis, ordered, star), star)
            }
            seen += cutoffs
            cutoffs = replay(groupId, genesis, ordered, cutoffs).cutoffs
        }
    }

    /** Makes a state replayed under merged cycle cutoffs consistent with those cutoffs. */
    private fun settle(pass: Pass, star: Cutoffs): MembershipState {
        val s = pass.state
        val active = s.active.toMutableMap()
        val departed = s.departed.toMutableMap()
        for ((id, cut) in star.member) {
            active.remove(id)?.let { departed[id] = Departed(it, cut) }
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
            e.verifySignature(pk)
    }

    private fun idOrNull(bytes: ByteString): DeviceId? =
        if (bytes.size == 16) DeviceId.fromBytes(bytes.toByteArray()) else null

    private fun replay(groupId: GroupId, genesis: DecodedEntry, ordered: List<DecodedEntry>, cutoffs: Cutoffs): Pass {
        val create = genesis.body.membership_op!!.create!!
        val active = linkedMapOf(
            genesis.author to Member(genesis.author, create.sign_public_key, create.dh_public_key, create.display_name, isAdmin = true),
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
                            active[id] = Member(id, add.sign_public_key, add.dh_public_key, add.display_name, isAdmin = false)
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
                        memberCut[id] = minOf(memberCut[id] ?: op.remove!!.last_seq, op.remove!!.last_seq)
                        active.remove(id)?.let { departed[id] = Departed(it, op.remove!!.last_seq) }
                        true
                    }
                }
                op.set_admin != null -> {
                    val sa = op.set_admin!!
                    val id = idOrNull(sa.device_id)
                    val m = id?.let { active[it] }
                    if (m == null || (sa.admin && id in demoted)) {
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
