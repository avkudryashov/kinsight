package app.kinsight.core.data

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.Animal
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.Mood

/** Animal avatars (spec 2026-10-24): who is which animal and how it feels. Pure, nothing is stored or sent. */
object Mascots {
    /**
     * Every member, departed ones included, gets an animal once, in the order they joined: the genesis creator, then the
     * effective AddMember entries by (Lamport, author, seq), the same on every replica. The start is
     * SHA-256(groupId ‖ deviceId) mod 10; the first animal not yet taken going round from it is the member's. A newcomer
     * is appended last, so nobody else changes; with more than 10 members the start animal repeats.
     */
    fun assign(groupId: GroupId, membership: MembershipState): Map<DeviceId, Animal> {
        val animals = Animal.entries
        val creator = membership.effectiveEntries.firstOrNull { it.body.membership_op?.create != null }
        val adds = membership.effectiveEntries
            .filter { it.body.membership_op?.add != null }
            .sortedWith(compareBy<DecodedEntry>({ it.lamport }, { it.author.hex }, { it.seq }))
        val order = LinkedHashSet<DeviceId>()
        creator?.let { order += it.author }
        for (e in adds) {
            val pk = e.body.membership_op!!.add!!.sign_public_key.toByteArray()
            if (pk.size == 32) order += DeviceId.fromSignPublicKey(pk)
        }
        // Members the entries do not explain (not expected) follow, by id, so the result still covers everyone.
        (membership.active.keys + membership.departed.keys).sorted().forEach { order += it }

        val taken = HashSet<Animal>()
        val out = LinkedHashMap<DeviceId, Animal>()
        for (id in order) {
            val h = Primitives.sha256(groupId.bytes(), id.bytes())
            val start = (((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
                ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)).mod(animals.size.toLong()).toInt()
            val pick = (0 until animals.size).map { animals[(start + it) % animals.size] }.firstOrNull { it !in taken } ?: animals[start]
            taken += pick
            out[id] = pick
        }
        return out
    }

    /** Today's time against the day limit (spec decision 3); no limit or no data reads as CONTENT. */
    fun moodOf(usedMs: Long?, limitMs: Long?): Mood {
        if (usedMs == null || limitMs == null || limitMs <= 0L) return Mood.CONTENT
        val ratio = usedMs.toDouble() / limitMs.toDouble()
        return when {
            ratio < 0.25 -> Mood.HAPPY
            ratio < 0.5 -> Mood.CONTENT
            ratio < 0.75 -> Mood.CALM
            ratio < 0.9 -> Mood.WORRIED
            ratio < 1.0 -> Mood.UPSET
            else -> Mood.UNWELL
        }
    }
}
