package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId

/**
 * Local replica of the group log. Implementations must make [applyBatch] atomic.
 * Entries are kept as received; a newer entry with the same supersedeKey replaces the older one.
 *
 * Every seq of an author is either unaccounted or accounted for. A seq is accounted for once a validly signed
 * entry with it went through [applyBatch] (stored, superseded, duplicate or reported as seen-but-expired), or
 * once the author itself covered it via [completeAuthor]. The advertised vector is contiguous: for each author
 * the highest N such that every seq up to N is accounted for. Only the author can move it across a gap.
 */
interface EntryStore {
    val groupId: GroupId

    /** Contiguous vector: per author, the highest N with every seq ≤ N accounted for (zero entries omitted). */
    suspend fun versionVector(): VersionVector

    suspend fun isAccounted(author: DeviceId, seq: Long): Boolean

    suspend fun membershipEntries(): List<DecodedEntry>

    /** Live entries of [author] with seq > [afterSeq], ascending by seq. */
    suspend fun entriesAfter(author: DeviceId, afterSeq: Long, limit: Int): List<DecodedEntry>

    /** Atomically stores [entries] and accounts for their seqs and for every seq in [seen] (not stored). */
    suspend fun applyBatch(entries: List<DecodedEntry>, seen: Map<DeviceId, Set<Long>> = emptyMap())

    /**
     * Author completion: the authenticated author [author] has delivered everything it holds up to [upToSeq],
     * so every seq ≤ [upToSeq] counts as accounted for. Never lowers the vector.
     */
    suspend fun completeAuthor(author: DeviceId, upToSeq: Long)

    /** Stores membership entries without accounting for them (they arrive outside the author log flow). */
    suspend fun storeMembership(entries: List<DecodedEntry>)

    suspend fun liveEntries(): List<DecodedEntry>

    /** Highest Lamport value among entries ever passed to [applyBatch] or [storeMembership]; feeds EntryAuthor. */
    suspend fun maxLamport(): Long

    /** Deletes entries older than the retention horizon of [policy]; accounting is unchanged. */
    suspend fun prune(policy: SyncPolicy)
}
