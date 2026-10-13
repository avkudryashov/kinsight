package app.kinsight.core.transport.cloud

/** A key generation and the [GroupKey.fingerprint] of the key: two admins rotating at once give one generation two keys. */
data class KeyId(val epoch: Int, val fingerprint: String)

/**
 * A pack that could not be opened yet, sealed with generation [epoch] (-1 when unknown). [failedKey]: the fingerprint of our
 * key of [epoch] under which the ciphertext did not open; the pack is retried only once the ring holds another key for it.
 */
data class DeferredPack(val epoch: Int, val failedKey: String? = null)

/**
 * Per-device cloud progress. [uploadedUpTo]: own seqs up to here are in packs on the Disk. [seen]: packs already handled,
 * "<device>/<name>/<sha256>" to the key generation they were sealed with (-1 when they did not open), pruned to the last
 * listing. [lastCompactionMs]: wall clock of the last compaction. [ownKeys]: keys the own packs on the Disk are sealed with.
 * [deferred]: packs that could not be opened yet (unknown author, key generation or key), same keys as [seen]; retried once
 * the ring holds a key that may open them, and they keep their generation in the ring.
 */
data class CloudCursor(
    val uploadedUpTo: Long = 0,
    val seen: Map<String, Int> = emptyMap(),
    val lastCompactionMs: Long = 0,
    val ownKeys: Set<KeyId> = emptySet(),
    val deferred: Map<String, DeferredPack> = emptyMap(),
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
