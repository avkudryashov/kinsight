package app.kinsight.core.transport.cloud

import java.io.IOException

/**
 * [sha256], [md5] and [modified] are what the Disk reports to tell two versions of one file apart; any may be missing.
 * The size never does: a pack sealed again under the same key keeps it.
 */
class DiskItem(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val sha256: String?,
    val md5: String? = null,
    val modified: String? = null,
)

/** Disk failures the sync reacts to (spec §11); plain IOExceptions are network failures. */
sealed class DiskException(message: String) : IOException(message) {
    class Unauthorized : DiskException("unauthorized")
    class NotFound(val path: String) : DiskException("not found: $path")
    class Conflict(val error: String?) : DiskException("conflict: $error")
    class RateLimited(val retryAfterMs: Long?) : DiskException("rate limited")
    class StorageFull : DiskException("storage full")
    class Server(val code: Int) : DiskException("server error $code")
    class Unexpected(val code: Int, val detail: String) : DiskException("unexpected $code: $detail")
}

/** The few Yandex Disk operations the sync needs, on app-folder paths (`app:/...`). */
interface DiskApi {
    /** Items directly under [path]. Throws [DiskException.NotFound] when it does not exist. */
    suspend fun list(path: String): List<DiskItem>

    /** Creates folder [path]; nothing happens when it exists. [DiskException.Conflict] when its parent is missing. */
    suspend fun mkdir(path: String)

    /** Writes [bytes] to [path], replacing a file there. [DiskException.Conflict] when the folder is missing. */
    suspend fun upload(path: String, bytes: ByteArray)

    suspend fun download(path: String): ByteArray

    /** Deletes [path] for good (no Disk trash); nothing happens when it does not exist. */
    suspend fun delete(path: String)
}
