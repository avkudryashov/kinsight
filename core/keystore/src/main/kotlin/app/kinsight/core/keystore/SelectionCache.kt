package app.kinsight.core.keystore

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Caches the first successful [select] for the process (3a review: one identity lookup shared by the service, the
 * worker and the screens). Failures are not cached: a KeyStorageException repeats on the next call by itself, and a
 * transient one gets another chance. [get] runs select() on [io] without interruption (no runInterruptible): a
 * cancelled caller must not cut a first-run key or choice write in half; select() finishes and the result is cached.
 */
class SelectionCache(
    private val select: () -> KeySelection,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    private var cached: KeySelection? = null
    private val lock = Any()

    /** Blocking; never on the main thread. */
    fun getBlocking(): KeySelection = cached ?: synchronized(lock) { cached ?: select().also { cached = it } }

    /** Drops the cached selection, then runs [wipe]; no select() can run in between (both hold [lock]). */
    fun reset(wipe: () -> Unit): Unit = synchronized(lock) {
        cached = null
        wipe()
    }

    suspend fun get(): KeySelection = cached ?: withContext(io) { getBlocking() }
}
