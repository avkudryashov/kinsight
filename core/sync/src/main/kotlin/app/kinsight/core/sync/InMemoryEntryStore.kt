package app.kinsight.core.sync

import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TreeSet

class InMemoryEntryStore(override val groupId: GroupId) : EntryStore {
    private val mutex = Mutex()
    private val byKey = HashMap<Pair<DeviceId, Long>, DecodedEntry>()
    private val bySupersede = HashMap<String, DecodedEntry>()
    private val contig = HashMap<DeviceId, Long>()
    private val above = HashMap<DeviceId, TreeSet<Long>>() // accounted seqs above contig, sparse

    override suspend fun versionVector() = mutex.withLock { VersionVector(contig.filterValues { it > 0 }) }

    override suspend fun isAccounted(author: DeviceId, seq: Long) = mutex.withLock { accounted(author, seq) }

    override suspend fun membershipEntries() = mutex.withLock { byKey.values.filter { it.isMembershipRelevant } }

    override suspend fun entriesAfter(author: DeviceId, afterSeq: Long, limit: Int) = mutex.withLock {
        byKey.values.filter { it.author == author && it.seq > afterSeq }.sortedBy { it.seq }.take(limit)
    }

    override suspend fun entriesAfter(author: DeviceId, afterSeq: Long, kinds: Set<EntryKind>, limit: Int) = mutex.withLock {
        byKey.values.filter { it.author == author && it.seq > afterSeq && it.kind in kinds }.sortedBy { it.seq }.take(limit)
    }

    override suspend fun applyBatch(entries: List<DecodedEntry>, seen: Map<DeviceId, Set<Long>>) = mutex.withLock {
        for (e in entries) {
            insert(e)
            account(e.author, e.seq)
        }
        for ((author, seqs) in seen) for (seq in seqs) account(author, seq)
    }

    override suspend fun completeAuthor(author: DeviceId, upToSeq: Long) = mutex.withLock {
        if (upToSeq <= (contig[author] ?: 0L)) return@withLock
        above[author]?.headSet(upToSeq, true)?.clear()
        contig[author] = upToSeq
        advance(author)
    }

    override suspend fun storeMembership(entries: List<DecodedEntry>) = mutex.withLock {
        entries.forEach(::insert)
    }

    override suspend fun liveEntries() = mutex.withLock {
        byKey.values.sortedWith(compareBy({ it.author.hex }, { it.seq }))
    }

    override suspend fun prune(policy: SyncPolicy) = mutex.withLock {
        val expired = byKey.values.filter(policy::isExpired)
        for (e in expired) {
            byKey.remove(e.author to e.seq)
            e.supersedeKey?.let { key -> if (bySupersede[key] == e) bySupersede.remove(key) }
        }
    }

    private fun accounted(author: DeviceId, seq: Long) =
        seq <= (contig[author] ?: 0L) || above[author]?.contains(seq) == true

    private fun account(author: DeviceId, seq: Long) {
        if (seq < 1 || accounted(author, seq)) return
        above.getOrPut(author) { TreeSet() } += seq
        advance(author)
    }

    private fun advance(author: DeviceId) {
        val pending = above[author] ?: return
        var c = contig[author] ?: 0L
        while (pending.remove(c + 1)) c++
        contig[author] = c
        if (pending.isEmpty()) above.remove(author)
    }

    private fun insert(e: DecodedEntry) {
        val existing = byKey[e.author to e.seq]
        if (existing != null) {
            // Equivocation (same author and seq, different bytes) on a membership op: keep the min-hex copy,
            // the one Membership.relevant picks, so every replica converges on the same bytes.
            if (e.isMembershipRelevant && existing.isMembershipRelevant && e.bytes.toHex() < existing.bytes.toHex()) {
                byKey[e.author to e.seq] = e
            }
            return
        }
        val key = e.supersedeKey
        if (key != null) {
            val old = bySupersede[key]
            if (old != null) {
                if (old.seq >= e.seq) return
                byKey.remove(old.author to old.seq)
            }
            bySupersede[key] = e
        }
        byKey[e.author to e.seq] = e
    }
}
