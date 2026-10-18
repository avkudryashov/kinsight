package app.kinsight.core.transport.cloud

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The group keys this device holds, by generation (spec §5: readers keep the previous key while files of its
 * generation remain). Secret: implementations keep it encrypted at rest (Y2: core/keystore).
 */
interface GroupKeyRing {
    suspend fun keys(): Map<Int, ByteArray>

    suspend fun put(epoch: Int, key: ByteArray)

    /** Drops every generation not in [keep]. */
    suspend fun retain(keep: Set<Int>)
}

class InMemoryKeyRing(initial: Map<Int, ByteArray> = emptyMap()) : GroupKeyRing {
    private val mutex = Mutex()
    private val ring = HashMap(initial)

    override suspend fun keys(): Map<Int, ByteArray> = mutex.withLock { HashMap(ring) }

    override suspend fun put(epoch: Int, key: ByteArray) = mutex.withLock { ring[epoch] = key.copyOf() }

    override suspend fun retain(keep: Set<Int>) = mutex.withLock { ring.keys.retainAll(keep); Unit }
}
