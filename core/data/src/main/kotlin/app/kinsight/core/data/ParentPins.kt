package app.kinsight.core.data

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.MembershipState
import app.kinsight.core.sync.ParentPinRules
import java.security.MessageDigest

/**
 * The parents' PIN for a child's phone (spec §10), resolved like [SafeZones]: among the ParentPin entries an admin
 * parent signed while it was one, the largest (rank, author hex) wins, so every phone checks the same PIN. Only its
 * Argon2id hash exists anywhere; the PIN itself is typed, hashed and forgotten.
 */
object ParentPins {
    /** OWASP's Argon2id minimum (19 MiB, 2 passes, 1 lane): about 0.1 to 0.3 s on a phone. */
    const val MEMORY_KIB = 19_456
    const val ITERATIONS = 2
    val LENGTH = 4..8

    /** Wrong tries before a child's phone waits; then [FIRST_WAIT_MS], doubling per wrong try, at most [MAX_WAIT_MS]. */
    const val FREE_TRIES = 5
    const val FIRST_WAIT_MS = 30_000L
    const val MAX_WAIT_MS = 15 * 60_000L

    fun winner(entries: Collection<DecodedEntry>, membership: MembershipState): DecodedEntry? =
        entries.asSequence()
            .filter { it.kind == EntryKind.PARENT_PIN && SafeZones.signedByAnAdmin(it, membership) }
            .filter { ParentPinRules.violation(it, membership) == null }
            .maxWithOrNull(compareBy<DecodedEntry>({ it.body.parent_pin!!.rank }, { it.author.hex }))

    /** 4 to 8 ASCII digits. */
    fun valid(pin: String): Boolean = pin.length in LENGTH && pin.all { it in '0'..'9' }

    /** Argon2id of [pin] with parallelism 1; the PIN's byte copy is zeroed after use. */
    fun hash(pin: String, salt: ByteArray, memoryKib: Int = MEMORY_KIB, iterations: Int = ITERATIONS): ByteArray {
        val bytes = pin.toByteArray(Charsets.US_ASCII)
        try {
            return Primitives.argon2id(bytes, salt, memoryKib, iterations)
        } finally {
            bytes.fill(0)
        }
    }

    /** [pin] hashes to [entry]'s hash under its salt and cost; compared in constant time. Slow: call off the main thread. */
    fun matches(pin: String, entry: DecodedEntry): Boolean {
        val p = entry.body.parent_pin ?: return false
        if (!valid(pin)) return false
        return MessageDigest.isEqual(hash(pin, p.salt.toByteArray(), p.memory_kib, p.iterations), p.hash.toByteArray())
    }

    /** The rank of a new PIN over [winner]; saturates at ParentPinRules.MAX_RANK. */
    fun nextRank(winner: DecodedEntry?): Long = minOf((winner?.body?.parent_pin?.rank ?: 0L) + 1, ParentPinRules.MAX_RANK)

    /** How long a child's phone waits after [failures] wrong tries in a row; null: no wait. */
    fun waitAfter(failures: Int): Long? {
        if (failures < FREE_TRIES) return null
        val doublings = (failures - FREE_TRIES).coerceAtMost(20)
        return minOf(FIRST_WAIT_MS shl doublings, MAX_WAIT_MS)
    }
}
