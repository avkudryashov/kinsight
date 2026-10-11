package app.kinsight.core.keystore

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Writes [bytes] to a unique temp file next to [target], syncs it, renames it over [target] and syncs the
 * directory, so readers never see half a file and the rename survives power loss.
 */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    val dir = target.absoluteFile.parentFile ?: throw KeyStorageException("no parent dir for ${target.name}")
    var tmp: File? = null
    try {
        dir.mkdirs()
        val created = File.createTempFile(target.name, TEMP_SUFFIX, dir)
        tmp = created
        FileOutputStream(created).use {
            it.write(bytes)
            it.fd.sync()
        }
        Files.move(created.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        tmp = null
        syncDirectory(dir)
    } catch (e: IOException) {
        throw KeyStorageException("cannot write ${target.name}", e)
    } finally {
        tmp?.delete()
    }
}

/** Temp files left behind by [writeAtomically] for [target] (e.g. after a crash). */
internal fun staleTempFiles(target: File): List<File> {
    val dir = target.absoluteFile.parentFile ?: return emptyList()
    return dir.listFiles { f -> f.name.startsWith(target.name) && f.name.endsWith(TEMP_SUFFIX) }?.toList().orEmpty()
}

private const val TEMP_SUFFIX = ".tmp"

/** Flushes the directory entry itself (a create, rename or delete in [dir]); skipped where a directory cannot be opened. */
fun syncDirectory(dir: File) {
    val channel = try {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ)
    } catch (_: IOException) {
        // Opening a directory is not supported on every platform; only this open may be skipped.
        return
    }
    channel.use { it.force(true) }
}
