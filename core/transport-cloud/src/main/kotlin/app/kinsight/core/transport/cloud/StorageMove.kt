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
     * Copies every file of every device folder of [groupId] from [from] to [to], same names; `storage.id` and folders that
     * are not device ids stay behind. A file already on [to] with the same size and sha256 is skipped, so a copy cut short
     * resumes. Returns the number of files written.
     */
    suspend fun copy(from: DiskApi, to: DiskApi, groupId: GroupId): Int {
        val root = CloudPaths.root(groupId)
        val top = try {
            from.list(root)
        } catch (e: DiskException.NotFound) {
            return 0
        }
        var copied = 0
        for (dir in top) {
            if (!dir.isDir || CloudPaths.parseDevice(dir.name) == null) continue
            val path = "$root/${dir.name}"
            val items = try {
                from.list(path)
            } catch (e: DiskException.NotFound) {
                continue
            }
            for (d in listOf(CloudPaths.APP_ROOT, root, path)) to.mkdir(d)
            val there = try {
                to.list(path)
            } catch (e: DiskException.NotFound) {
                emptyList()
            }.filter { !it.isDir }.associateBy { it.name }
            for (i in items) {
                if (i.isDir) continue
                val same = there[i.name]?.let { t -> t.size == i.size && t.sha256 != null && t.sha256 == i.sha256 } == true
                if (same) continue
                val bytes = try {
                    from.download("$path/${i.name}")
                } catch (e: DiskException.NotFound) {
                    continue // compacted away meanwhile
                }
                to.upload("$path/${i.name}", bytes)
                copied++
            }
        }
        return copied
    }
}
