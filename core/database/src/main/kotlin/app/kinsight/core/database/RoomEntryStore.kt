package app.kinsight.core.database

import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupId
import app.kinsight.core.sync.EntryStore
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.VersionVector
import java.time.LocalDate

/** The local author's last used seq and Lamport value (spec §7, «Состояние автора»). */
data class AuthorState(val lastSeq: Long, val lastLamport: Long)

/**
 * Room-backed [EntryStore] with exactly the semantics of InMemoryEntryStore (pinned by EntryStoreContract).
 * Every mutation runs in one IMMEDIATE transaction, so a batch is applied completely or not at all (spec §5.6).
 * Own new entries go through [appendOwn], which also persists the author state in the same transaction.
 */
class RoomEntryStore(private val db: KinsightDatabase, override val groupId: GroupId) : EntryStore {
    private val entryDao = db.entryDao()
    private val vector = db.vectorDao()
    private val authorDao = db.authorDao()

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

    override suspend fun applyBatch(entries: List<DecodedEntry>, seen: Map<DeviceId, Set<Long>>): Unit = db.write {
        entries.forEachIndexed { i, e ->
            insert(e)
            account(e.author.hex, e.seq)
            faultInjector?.invoke(i)
        }
        for ((author, seqs) in seen) for (seq in seqs) account(author.hex, seq)
        faultInjector?.invoke(SEEN_ACCOUNTED)
    }

    override suspend fun completeAuthor(author: DeviceId, upToSeq: Long): Unit = db.write {
        val a = author.hex
        if (upToSeq > (vector.contig(a) ?: 0L)) {
            vector.deleteAboveUpTo(a, upToSeq)
            vector.setContig(ContigRow(a, upToSeq))
            advance(a)
        }
    }

    override suspend fun storeMembership(entries: List<DecodedEntry>): Unit = db.write {
        entries.forEach { insert(it) }
    }

    override suspend fun liveEntries(): List<DecodedEntry> = entryDao.allBytes().map(DecodedEntry::decode)

    /** Live UsageSummary entries of every author (the v1 screens read only these, spec §9.3). */
    suspend fun summaries(): List<DecodedEntry> = entryDao.bytesBySupersedeKey("/sum/%").map(DecodedEntry::decode).filter { it.kind == EntryKind.USAGE_SUMMARY }

    /** Live UsageDetail entries of [author] for local dates [from] to [to] inclusive (level 4, spec §9.3). */
    suspend fun details(author: DeviceId, from: LocalDate, to: LocalDate): List<DecodedEntry> =
        entryDao.bytesBySupersedeKeyRange("${author.hex}/det/$from", "${author.hex}/det/$to")
            .map(DecodedEntry::decode).filter { it.kind == EntryKind.USAGE_DETAIL && it.author == author }

    suspend fun catalog(): List<DecodedEntry> = entryDao.bytesBySupersedeKey("/app/%").map(DecodedEntry::decode).filter { it.kind == EntryKind.APP_CATALOG }

    suspend fun heartbeats(): List<DecodedEntry> = entryDao.bytesBySupersedeKey("/hb").map(DecodedEntry::decode).filter { it.kind == EntryKind.HEARTBEAT }

    /** Every StateEvent, LEFT_GROUP included (stored as membership), ordered by author and seq. */
    suspend fun stateEvents(): List<DecodedEntry> =
        (entryDao.unkeyedBytes() + entryDao.membershipBytes())
            .map(DecodedEntry::decode)
            .filter { it.kind == EntryKind.STATE_EVENT }
            .sortedWith(compareBy({ it.author.hex }, { it.seq }))

    override suspend fun prune(policy: SyncPolicy): Unit = db.write {
        for (bytes in entryDao.bytesWithUsageDate()) {
            val e = DecodedEntry.decode(bytes)
            if (policy.isExpired(e)) entryDao.delete(e.author.hex, e.seq)
        }
    }

    suspend fun authorState(author: DeviceId): AuthorState = db.write { authorStateLocked(author.hex) }

    /**
     * Stores the local author's new [entries] (consecutive seqs right after [authorState]), accounts for them and
     * persists the author state, then runs [alsoInTransaction]; all in one transaction. Any failure leaves no entry
     * and no state change, so a fresh EntryAuthor reuses the seq nobody saw and the log never gets a gap.
     */
    suspend fun appendOwn(entries: List<DecodedEntry>, alsoInTransaction: suspend () -> Unit = {}): Unit = db.write {
        require(entries.isNotEmpty()) { "nothing to append" }
        val author = entries.first().author
        require(entries.all { it.author == author && it.groupId == groupId }) { "own entries must share author and group" }
        val a = author.hex
        var state = authorStateLocked(a)
        for (e in entries) {
            check(e.seq == state.lastSeq + 1) { "own entry seq ${e.seq} does not follow ${state.lastSeq}" }
            insert(e)
            account(a, e.seq)
            state = AuthorState(e.seq, maxOf(state.lastLamport, e.lamport))
        }
        authorDao.set(AuthorStateRow(a, state.lastSeq, state.lastLamport))
        alsoInTransaction()
    }

    private companion object {
        const val SEEN_ACCOUNTED = -1
    }

    private suspend fun authorStateLocked(a: String): AuthorState {
        val row = authorDao.get(a)
        val known = maxOf(entryDao.maxSeq(a) ?: 0L, vector.contig(a) ?: 0L, vector.above(a).lastOrNull() ?: 0L)
        if (row != null && row.lastSeq >= known) return AuthorState(row.lastSeq, row.lastLamport)
        // No row yet (only entries that came in another way, such as the genesis) or a row behind the log.
        val stored = entryDao.bytesAfter(a, 0, Int.MAX_VALUE).maxOfOrNull { DecodedEntry.decode(it).lamport } ?: 0L
        return AuthorState(maxOf(row?.lastSeq ?: 0L, known), maxOf(row?.lastLamport ?: 0L, stored))
    }

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
