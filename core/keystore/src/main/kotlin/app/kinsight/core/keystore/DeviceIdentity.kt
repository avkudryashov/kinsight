package app.kinsight.core.keystore

import android.content.Context

/** The process-wide device identity: DeviceKeyProvider.select() once, then cached (see SelectionCache). */
object DeviceIdentity {
    @Volatile
    private var cache: SelectionCache? = null

    private fun cache(context: Context): SelectionCache {
        val app = context.applicationContext
        return cache ?: synchronized(this) {
            cache ?: SelectionCache({ DeviceKeyProvider.select(app) }).also { cache = it }
        }
    }

    /** Blocking; never on the main thread. Throws KeyStorageException. */
    fun getBlocking(context: Context): KeySelection = cache(context).getBlocking()

    /** Throws KeyStorageException. */
    suspend fun get(context: Context): KeySelection = cache(context).get()

    /**
     * Blocking; never on the main thread. A new identity on the next get(): run inside the usage pipeline lock and
     * only after the group data is wiped (core/data LocalReset). Throws KeyStorageException.
     */
    fun resetBlocking(context: Context) {
        val app = context.applicationContext
        cache(app).reset { DeviceKeyProvider.reset(app) }
    }
}
