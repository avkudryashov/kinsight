package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId

/**
 * Local replica of the group log. Implementations must make [applyBatch] atomic.
 * Entries are kept as received; a newer entry with the same supersedeKey replaces the older one.
 */
interface EntryStore {
    val groupId: GroupId

    /** Highest seq seen per author through [applyBatch] (stored or seenOnly). */
    suspend fun versionVector(): VersionVector

    suspend fun membershipEntries(): List<DecodedEntry>

    /** Live entries of [author] with seq > [afterSeq], ascending by seq. */
    suspend fun entriesAfter(author: DeviceId, afterSeq: Long, limit: Int): List<DecodedEntry>

    /** Atomically stores [entries] and raises the vector, also for [seenOnly] authors. */
    suspend fun applyBatch(entries: List<DecodedEntry>, seenOnly: Map<DeviceId, Long> = emptyMap())

    /** Stores membership entries without touching the vector. */
    suspend fun storeMembership(entries: List<DecodedEntry>)

    suspend fun liveEntries(): List<DecodedEntry>

    /** Highest Lamport value among entries ever passed to [applyBatch] or [storeMembership]; feeds EntryAuthor. */
    suspend fun maxLamport(): Long

    /** Deletes entries older than the retention horizon of [policy]; the vector is unchanged. */
    suspend fun prune(policy: SyncPolicy)
}
