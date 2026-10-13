package app.kinsight.core.transport.cloud

/**
 * Per-device cloud progress. [uploadedUpTo]: own seqs up to here are in packs on the Disk. [seen]: packs already handled,
 * "<device>/<name>/<sha256>" to the key generation they were sealed with (-1 when they did not open), pruned to the last
 * listing. [lastCompactionMs]: wall clock of the last compaction. [ownEpochs]: key generations of the own packs on the Disk.
 * [deferred]: packs that could not be opened yet (unknown author or key generation), same keys as [seen] to the generation
 * they were sealed with (-1 when unknown); retried once the ring holds it, and they keep that generation in the ring.
 */
data class CloudCursor(
    val uploadedUpTo: Long = 0,
    val seen: Map<String, Int> = emptyMap(),
    val lastCompactionMs: Long = 0,
    val ownEpochs: Set<Int> = emptySet(),
    val deferred: Map<String, Int> = emptyMap(),
)

interface CloudCursorStore {
    suspend fun load(): CloudCursor

    /** Must be durable when it returns: a crash after an upload must not upload the range again with a gap below. */
    suspend fun save(cursor: CloudCursor)
}

class InMemoryCursorStore(var cursor: CloudCursor = CloudCursor()) : CloudCursorStore {
    override suspend fun load() = cursor

    override suspend fun save(cursor: CloudCursor) {
        this.cursor = cursor
    }
}
