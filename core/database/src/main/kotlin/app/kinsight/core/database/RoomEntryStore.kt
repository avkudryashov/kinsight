package app.kinsight.core.database

import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.Membership
import app.kinsight.core.sync.EntryStore
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.VersionVector
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
    private val usageDao = db.usageDao()

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
        val touched = HashSet<String>()
        entries.forEachIndexed { i, e ->
            insert(e)
            account(e.author.hex, e.seq, touched)
            faultInjector?.invoke(i)
        }
        for ((author, seqs) in seen) for (seq in seqs) account(author.hex, seq, touched)
        touched.forEach { advance(it) }
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

    /**
     * The stored seqs of [author], ascending, whatever their kind: for the local author, the live set its next
     * Heartbeat signs (A-2). Read it under the pipeline lock, so no own append slips in before that Heartbeat.
     */
    suspend fun ownLiveSeqs(author: DeviceId): List<Long> = entryDao.seqs(author.hex)

    /** Live UsageSummary entries of every author (the v1 screens read only these, spec §9.3). */
    suspend fun summaries(): List<DecodedEntry> = entryDao.bytesOfKind(EntryKinds.USAGE_SUMMARY).map(DecodedEntry::decode)

    /** Live UsageDetail entries of [author] for local dates [from] to [to] inclusive (level 4, spec §9.3). */
    suspend fun details(author: DeviceId, from: LocalDate, to: LocalDate): List<DecodedEntry> =
        entryDao.bytesBySupersedeKeyRange("${author.hex}/det/$from", "${author.hex}/det/$to")
            .map(DecodedEntry::decode).filter { it.kind == EntryKind.USAGE_DETAIL && it.author == author }

    suspend fun catalog(): List<DecodedEntry> = entryDao.bytesOfKind(EntryKinds.APP_CATALOG).map(DecodedEntry::decode)

    /** Live CategoryOverride entries: each author's latest per package (spec §4.4); never pruned. */
    suspend fun categoryOverrides(): List<DecodedEntry> = entryDao.bytesOfKind(EntryKinds.CATEGORY_OVERRIDE).map(DecodedEntry::decode)

    suspend fun heartbeats(): List<DecodedEntry> = entryDao.bytesOfKind(EntryKinds.HEARTBEAT).map(DecodedEntry::decode)

    /** Every StateEvent, LEFT_GROUP included, ordered by author and seq. */
    suspend fun stateEvents(): List<DecodedEntry> = entryDao.bytesOfKind(EntryKinds.STATE_EVENT).map(DecodedEntry::decode)

    /** The live StorageHandover entries (spec §7.1), one per author. */
    suspend fun storageHandovers(): List<DecodedEntry> = entryDao.bytesOfKind(EntryKinds.STORAGE_HANDOVER).map(DecodedEntry::decode)

    /** Every LOCATION entry (spec §8), each author's by seq; at most 48 h of them after the daily prune. */
    suspend fun locations(): List<DecodedEntry> = entryDao.bytesOfKind(EntryKinds.LOCATION).map(DecodedEntry::decode)

    /** The live SafeZone entries (spec §8.1), one per author and zone; read through core/data SafeZones. */
    suspend fun safeZones(): List<DecodedEntry> = entryDao.bytesOfKind(EntryKinds.SAFE_ZONE).map(DecodedEntry::decode)

    override suspend fun prune(policy: SyncPolicy): Unit = db.write {
        for (bytes in entryDao.bytesWithUsageDate() + entryDao.bytesOfKind(EntryKinds.LOCATION)) {
            val e = DecodedEntry.decode(bytes)
            if (policy.isExpired(e)) entryDao.delete(e.author.hex, e.seq)
        }
    }

    /**
     * Local history beyond the summary retention (audit A-6), next to [prune]; RoomEntryStore only, never part of the
     * sync contract. The cutoff is the start of (today minus [summaryRetentionDays]) in [zone], the oldest day whose
     * summary [prune] keeps, so that day's events survive. StateEvents that ended before it go, except each author's
     * newest per event kind and earliest SHARING_* event. AppCatalog entries older than it go when no live
     * UsageSummary of the same author names the package any more, but only [self]'s own (the publisher's hash goes
     * too, so the app is described again if it comes back) and departed authors'. Another active author re-publishes
     * a catalog entry only when its own hash changes, so its pruned row would be lost for good. The pruned seqs stay
     * accounted in the vector: no peer sends them again.
     */
    suspend fun pruneHistory(
        summaryRetentionDays: Long,
        nowMs: Long,
        self: DeviceId,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Unit = db.write {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val before = today.minusDays(summaryRetentionDays).atStartOfDay(zone).toInstant().toEpochMilli()
        entryDao.pruneStateEvents(EntryKinds.STATE_EVENT, before)
        val departed = Membership.compute(groupId, entryDao.membershipBytes().map(DecodedEntry::decode))?.departed?.keys.orEmpty()
        val named = HashMap<DeviceId, MutableSet<String>>()
        for (bytes in entryDao.bytesOfKind(EntryKinds.USAGE_SUMMARY)) {
            val s = DecodedEntry.decode(bytes)
            s.body.usage_summary!!.apps.mapTo(named.getOrPut(s.author) { HashSet() }) { it.package_name }
        }
        for (bytes in entryDao.bytesOfKind(EntryKinds.APP_CATALOG)) {
            val c = DecodedEntry.decode(bytes)
            if (c.author != self && c.author !in departed) continue
            val pkg = c.body.app_catalog!!.package_name
            if (c.body.created_at_ms >= before || pkg in named[c.author].orEmpty()) continue
            entryDao.delete(c.author.hex, c.seq)
            if (c.author == self) usageDao.deleteCatalogHash(pkg)
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
        val touched = HashSet<String>()
        for (e in entries) {
            check(e.seq == state.lastSeq + 1) { "own entry seq ${e.seq} does not follow ${state.lastSeq}" }
            insert(e)
            account(a, e.seq, touched)
            state = AuthorState(e.seq, maxOf(state.lastLamport, e.lamport))
        }
        touched.forEach { advance(it) }
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

    /**
     * Marks [seq] accounted without moving contig; the caller runs [advance] once per [touched] author after the
     * batch. Advancing per seq would rescan every pending row each time: quadratic for a live set's
     * [app.kinsight.core.sync.LiveSet.MAX_SPARSE] dead seqs.
     */
    private suspend fun account(author: String, seq: Long, touched: MutableSet<String>) {
        if (seq < 1 || vector.isAccounted(author, seq)) return
        vector.addAbove(AboveRow(author, seq))
        touched += author
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
        val c = EntryColumns.of(e)
        entryDao.insert(
            EntryRow(
                author = a,
                seq = e.seq,
                kind = c.kind,
                supersedeKey = key,
                membership = e.isMembershipRelevant,
                usageDate = e.body.usage_summary?.local_date ?: e.body.usage_detail?.local_date,
                eventKind = c.eventKind,
                eventToMs = c.eventToMs,
                bytes = e.bytes,
            ),
        )
    }
}
