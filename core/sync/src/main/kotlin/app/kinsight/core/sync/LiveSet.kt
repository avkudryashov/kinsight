package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.Heartbeat

/**
 * An author's live set (audit A-2, spec §5): of its own seqs in ([floor], [upTo]] it still held exactly [seqs]
 * (ascending) when it signed the Heartbeat carrying it; every other seq in that range was superseded or pruned there for
 * good. Only the author can sign it, so a relay still cannot make a receiver skip an entry; and an old statement stays
 * true (a dead seq never comes back), so its age does not matter.
 */
class LiveSet(val floor: Long, val upTo: Long, val seqs: LongArray) {
    companion object {
        /** Most seqs one Heartbeat lists; an author with more lists its newest ones and raises the floor. */
        const val MAX_SEQS = 2_000

        /** Most dead seqs above the first missing live one that one statement accounts one by one. */
        const val MAX_SPARSE = 4_096

        /** The statement in [e] if it is a Heartbeat with a well-formed one; anything else is ignored (never invalid). */
        fun of(e: DecodedEntry): LiveSet? {
            val h = e.body.heartbeat ?: return null
            val upTo = h.live_upto
            val floor = h.live_floor
            if (upTo <= 0 || upTo >= e.seq || floor < 0 || floor >= upTo) return null
            if (h.live_deltas.size > MAX_SEQS) return null
            val seqs = LongArray(h.live_deltas.size)
            var prev = floor
            for ((i, d) in h.live_deltas.withIndex()) {
                if (d <= 0 || d > upTo - prev) return null
                prev += d
                seqs[i] = prev
            }
            return LiveSet(floor, upTo, seqs)
        }

        /**
         * The Heartbeat of an author whose own live seqs are [own] (ascending, as stored now) and whose current Heartbeat,
         * superseded by this one, has seq [currentHeartbeat]: that one is left out, it is dead once this one exists.
         */
        fun heartbeat(appVersion: String, protocolVersion: Int, own: List<Long>, currentHeartbeat: Long?): Heartbeat {
            val upTo = own.lastOrNull() ?: 0L
            val live = own.filter { it != currentHeartbeat }
            val kept = live.takeLast(MAX_SEQS)
            val floor = if (kept.size < live.size) live[live.size - kept.size - 1] else 0L
            var prev = floor
            val deltas = kept.map { s -> (s - prev).also { prev = s } }
            return Heartbeat(app_version = appVersion, protocol_version = protocolVersion, live_upto = upTo, live_floor = floor, live_deltas = deltas)
        }
    }
}

/**
 * Accounts every seq of [author] that [set] declares dead. While everything below is accounted, dead runs move the
 * contiguous vector in one step (completeAuthor); above the first live seq still missing here, dead seqs are accounted
 * one by one, at most [LiveSet.MAX_SPARSE] per call (a later statement does the rest). Monotone and idempotent; two
 * store calls, each atomic.
 */
suspend fun EntryStore.accountLiveSet(author: DeviceId, set: LiveSet) {
    val start = versionVector()[author]
    var contig = start
    var running = contig >= set.floor // nothing at or below the floor is described: contig cannot cross it
    val sparse = HashSet<Long>()
    var prev = set.floor
    var i = 0
    while (true) {
        val next = if (i < set.seqs.size) set.seqs[i] else set.upTo + 1
        val deadTo = minOf(next - 1, set.upTo)
        if (prev + 1 <= deadTo) {
            if (running) {
                contig = maxOf(contig, deadTo)
            } else {
                var s = maxOf(prev + 1, contig + 1)
                while (s <= deadTo && sparse.size < LiveSet.MAX_SPARSE) sparse += s++
            }
        }
        if (i >= set.seqs.size) break
        if (running && next > contig && !isAccounted(author, next)) running = false
        if (running) contig = maxOf(contig, next)
        prev = next
        i++
        if (!running && sparse.size >= LiveSet.MAX_SPARSE) break
    }
    if (contig > start) completeAuthor(author, contig)
    if (sparse.isNotEmpty()) applyBatch(emptyList(), mapOf(author to sparse))
}
