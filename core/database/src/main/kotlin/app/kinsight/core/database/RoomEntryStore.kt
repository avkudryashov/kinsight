package app.kinsight.core.database

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import app.kinsight.core.sync.EntryStore
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.VersionVector

/**
 * Room-backed [EntryStore] with exactly the semantics of InMemoryEntryStore (pinned by EntryStoreContract).
 * Every mutation runs in one IMMEDIATE transaction, so a batch is applied completely or not at all (spec §5.6).
 */
class RoomEntryStore(private val db: KinsightDatabase, override val groupId: GroupId) : EntryStore {
    private val entryDao = db.entryDao()
    private val vector = db.vectorDao()

    /** Test hook, called inside the [applyBatch] transaction after each entry with its index, then once with -1 after the seen accounting. */
    internal var faultInjector: ((Int) -> Unit)? = null

    override suspend fun versionVector(): VersionVector =
        VersionVector(vector.positive().associate { DeviceId(it.author) to it.contig })

    override suspend fun isAccounted(author: DeviceId, seq: Long): Boolean =
        seq <= 0 || vector.isAccounted(author.hex, seq)

    override suspend fun membershipEntries(): List<DecodedEntry> = entryDao.membershipBytes().map(DecodedEntry::decode)

    override suspend fun entriesAfter(author: DeviceId, afterSeq: Long, limit: Int): List<DecodedEntry> {
        require(limit >= 0) { "Requested element count $limit is less than zero." }
        return entryDao.bytesAfter(author.hex, afterSeq, limit).map(DecodedEntry::decode)
    }

    override suspend fun applyBatch(entries: List<DecodedEntry>, seen: Map<DeviceId, Set<Long>>): Unit = write {
        entries.forEachIndexed { i, e ->
            insert(e)
            account(e.author.hex, e.seq)
            faultInjector?.invoke(i)
        }
        for ((author, seqs) in seen) for (seq in seqs) account(author.hex, seq)
        faultInjector?.invoke(SEEN_ACCOUNTED)
    }

    override suspend fun completeAuthor(author: DeviceId, upToSeq: Long): Unit = write {
        val a = author.hex
        if (upToSeq > (vector.contig(a) ?: 0L)) {
            vector.deleteAboveUpTo(a, upToSeq)
            vector.setContig(ContigRow(a, upToSeq))
            advance(a)
        }
    }

    override suspend fun storeMembership(entries: List<DecodedEntry>): Unit = write {
        entries.forEach { insert(it) }
    }

    override suspend fun liveEntries(): List<DecodedEntry> = entryDao.allBytes().map(DecodedEntry::decode)

    override suspend fun prune(policy: SyncPolicy): Unit = write {
        for (bytes in entryDao.bytesWithUsageDate()) {
            val e = DecodedEntry.decode(bytes)
            if (policy.isExpired(e)) entryDao.delete(e.author.hex, e.seq)
        }
    }

    private companion object {
        const val SEEN_ACCOUNTED = -1
    }

    private suspend fun <T> write(block: suspend () -> T): T =
        db.useWriterConnection { transactor -> transactor.immediateTransaction { block() } }

    private suspend fun account(author: String, seq: Long) {
        if (seq < 1 || vector.isAccounted(author, seq)) return
        vector.addAbove(AboveRow(author, seq))
        advance(author)
    }

    /** Moves contig over the accounted seqs directly above it (all stored seqs are > contig). */
    private suspend fun advance(author: String) {
        val pending = vector.above(author)
        if (pending.isEmpty()) return
        var c = vector.contig(author) ?: 0L
        for (s in pending) if (s == c + 1) c++ else break
        vector.deleteAboveUpTo(author, c)
        vector.setContig(ContigRow(author, c))
    }

    private suspend fun insert(e: DecodedEntry) {
        val a = e.author.hex
        val existing = entryDao.get(a, e.seq)
        if (existing != null) {
            // Equivocation (same author and seq, different bytes) on a membership op: keep the min-hex copy,
            // the one Membership.relevant picks, so every replica converges on the same bytes.
            if (e.isMembershipRelevant && existing.membership && e.bytes.toHex() < existing.bytes.toHex()) {
                entryDao.replaceBytes(a, e.seq, e.bytes)
            }
            return
        }
        val key = e.supersedeKey
        if (key != null) {
            val old = entryDao.bySupersedeKey(key)
            if (old != null) {
                if (old.seq >= e.seq) return
                entryDao.delete(old.author, old.seq)
            }
        }
        entryDao.insert(
            EntryRow(
                author = a,
                seq = e.seq,
                bytes = e.bytes,
                supersedeKey = key,
                membership = e.isMembershipRelevant,
                usageDate = e.body.usage_summary?.local_date ?: e.body.usage_detail?.local_date,
            ),
        )
    }
}
