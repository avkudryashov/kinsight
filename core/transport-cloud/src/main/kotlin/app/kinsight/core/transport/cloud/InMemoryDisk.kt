package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.toHex
import java.util.TreeMap
import java.util.TreeSet

/** A Disk in memory with the real one's folder rules (tests and the Y2 debug build). */
class InMemoryDisk : DiskApi {
    private val lock = Any()
    private val files = TreeMap<String, ByteArray>()
    private val dirs = TreeSet<String>().apply { add("app:") }
    private val downloadLog = mutableListOf<String>()

    /** Test seam: after this many more successful uploads, every upload fails with an IOException. */
    var failUploadsAfter: Int? = null

    val downloads: List<String> get() = synchronized(lock) { downloadLog.toList() }

    override suspend fun list(path: String): List<DiskItem> = synchronized(lock) {
        if (path !in dirs) throw DiskException.NotFound(path)
        val prefix = "$path/"
        val subdirs = dirs.filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
            .map { DiskItem(it.removePrefix(prefix), isDir = true, size = 0, sha256 = null) }
        val own = files.filterKeys { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
            .map { (p, b) -> DiskItem(p.removePrefix(prefix), isDir = false, size = b.size.toLong(), sha256 = Primitives.sha256(b).toHex()) }
        subdirs + own
    }

    override suspend fun mkdir(path: String) = synchronized(lock) {
        if (path in dirs) return@synchronized
        if (parent(path) !in dirs) throw DiskException.Conflict("DiskPathDoesntExistsError")
        dirs += path
    }

    override suspend fun upload(path: String, bytes: ByteArray) = synchronized(lock) {
        if (failUploadsAfter == 0) throw java.io.IOException("injected upload failure")
        if (parent(path) !in dirs) throw DiskException.Conflict("DiskPathDoesntExistsError")
        files[path] = bytes.copyOf()
        failUploadsAfter = failUploadsAfter?.minus(1)
    }

    override suspend fun download(path: String): ByteArray = synchronized(lock) {
        downloadLog += path
        files[path]?.copyOf() ?: throw DiskException.NotFound(path)
    }

    override suspend fun delete(path: String) = synchronized(lock) { removeTree(path) }

    /** As deleting a folder in the Disk web UI (spec §11). */
    fun deleteTree(path: String) = synchronized(lock) { removeTree(path) }

    fun files(prefix: String): Map<String, ByteArray> = synchronized(lock) {
        files.filterKeys { it.startsWith(prefix) }.mapValues { (_, b) -> b.copyOf() }
    }

    private fun removeTree(path: String) {
        files.keys.removeIf { it == path || it.startsWith("$path/") }
        dirs.removeIf { it == path || it.startsWith("$path/") }
    }

    private fun parent(path: String) = path.substringBeforeLast('/')
}
