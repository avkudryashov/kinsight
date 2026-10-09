package app.kinsight.core.keystore

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes [bytes] to a temp file, syncs it and renames it over [target], so readers never see half a file. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    try {
        val dir = target.absoluteFile.parentFile
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: IOException) {
        throw KeyStorageException("cannot write ${target.name}", e)
    }
}
