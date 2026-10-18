package app.kinsight.core.transport.cloud

import app.kinsight.core.model.GroupId

/**
 * The storage's identity file and the move of a storage to another Disk (spec §5, §7.1 «копирует всё содержимое
 * app:/Kinsight/<groupId>/ со старого Диска на свой»). Errors are the Disks' own: a [DiskException.Unauthorized] from the
 * source means the shared token was revoked (spec §7.2: the new storage then fills from the phones' own copies).
 */
object StorageMove {
    /** The 16-byte storage id in `storage.id` of [disk], or null when there is none or it is not 16 bytes. */
    suspend fun storageIdOf(disk: DiskApi, groupId: GroupId): ByteArray? = try {
        disk.download(CloudPaths.storageIdFile(groupId)).takeIf { it.size == 16 }
    } catch (e: DiskException.NotFound) {
        null
    }

    /** Writes [storageId] as `storage.id` of [disk], creating the folders. */
    suspend fun writeStorageId(disk: DiskApi, groupId: GroupId, storageId: ByteArray) {
        require(storageId.size == 16) { "storage id must be 16 bytes" }
        for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(groupId))) disk.mkdir(d)
        disk.upload(CloudPaths.storageIdFile(groupId), storageId)
    }

    /**
     * Copies every `*.kb` pack of every device folder of [groupId] from [from] to [to], same names; `storage.id`, other
     * files and folders that are not device ids stay behind. A file already on [to] with the same size and sha256 is
     * skipped, so a copy cut short resumes. Returns the number of files written. A Disk failure arrives as
     * [StorageMoveException] naming the side: [Side.SOURCE] 401 = the old token was revoked, [Side.DESTINATION] 401 = the
     * new token is bad.
     */
    suspend fun copy(from: DiskApi, to: DiskApi, groupId: GroupId): Int {
        val root = CloudPaths.root(groupId)
        val top = try {
            from.on(Side.SOURCE) { list(root) }
        } catch (e: StorageMoveException) {
            if (e.cause is DiskException.NotFound) return 0 else throw e
        }
        var copied = 0
        var rootMade = false
        for (dir in top) {
            if (!dir.isDir || CloudPaths.parseDevice(dir.name) == null) continue
            val path = "$root/${dir.name}"
            val items = try {
                from.on(Side.SOURCE) { list(path) }
            } catch (e: StorageMoveException) {
                if (e.cause is DiskException.NotFound) continue else throw e
            }
            val packs = items.filter { !it.isDir && CloudPaths.parsePack(it.name) != null }
            if (packs.isEmpty()) continue
            if (!rootMade) {
                to.on(Side.DESTINATION) { mkdir(CloudPaths.APP_ROOT) }
                to.on(Side.DESTINATION) { mkdir(root) }
                rootMade = true
            }
            to.on(Side.DESTINATION) { mkdir(path) }
            val there = try {
                to.on(Side.DESTINATION) { list(path) }
            } catch (e: StorageMoveException) {
                if (e.cause is DiskException.NotFound) emptyList() else throw e
            }.filter { !it.isDir }.associateBy { it.name }
            for (i in packs) {
                val same = there[i.name]?.let { t -> t.size == i.size && t.sha256 != null && t.sha256 == i.sha256 } == true
                if (same) continue
                val bytes = try {
                    from.on(Side.SOURCE) { download("$path/${i.name}") }
                } catch (e: StorageMoveException) {
                    if (e.cause is DiskException.NotFound) continue else throw e // NotFound: compacted away meanwhile
                }
                to.on(Side.DESTINATION) { upload("$path/${i.name}", bytes) }
                copied++
            }
        }
        return copied
    }

    private suspend fun <T> DiskApi.on(side: Side, block: suspend DiskApi.() -> T): T = try {
        block()
    } catch (e: java.io.IOException) {
        throw StorageMoveException(side, e)
    }

    enum class Side { SOURCE, DESTINATION }
}

/** A Disk failure during [StorageMove.copy], with the Disk it came from; [cause] is what that Disk threw. */
class StorageMoveException(val side: StorageMove.Side, override val cause: java.io.IOException) :
    java.io.IOException("${side.name.lowercase()} disk: ${cause.message}", cause)
