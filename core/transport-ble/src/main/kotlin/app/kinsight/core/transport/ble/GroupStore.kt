package app.kinsight.core.transport.ble

import app.kinsight.core.model.GroupId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** The group this device syncs with and its discovery key (spec §6.5). Written by pairing (stage 4) or the debug seed. */
class GroupConfig(val groupId: GroupId, val discoveryKey: ByteArray) {
    init {
        require(discoveryKey.size == 32) { "discovery key must be 32 bytes" }
    }
}

class GroupStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * `noBackupFilesDir/kinsight-group.v1`: "KGRP", version byte 1, groupId (16 B), discovery key (32 B). Replaced
 * atomically (temp file, fsync, rename). A missing file means no group; an unreadable or malformed one is an error,
 * never "no group", so a damaged file cannot silently turn the transport off.
 */
class GroupStore(private val file: File) {
    @Volatile
    private var loaded = false

    @Volatile
    private var cached: GroupConfig? = null

    fun exists(): Boolean = file.exists()

    @Synchronized
    fun read(): GroupConfig? {
        if (!loaded) {
            cached = load()
            loaded = true
        }
        return cached
    }

    @Synchronized
    fun write(config: GroupConfig) {
        writeAtomically(MAGIC + byteArrayOf(VERSION) + config.groupId.bytes() + config.discoveryKey)
        cached = config
        loaded = true
    }

    /** Leaving the group or a new identity; the next read() sees no group. Idempotent. */
    @Synchronized
    fun delete() {
        if (file.exists() && !file.delete()) throw GroupStoreException("cannot delete ${file.name}")
        cached = null
        loaded = true
    }

    private fun load(): GroupConfig? {
        if (!file.exists()) return null
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            throw GroupStoreException("cannot read ${file.name}", e)
        }
        if (bytes.size != SIZE || !bytes.copyOfRange(0, 4).contentEquals(MAGIC) || bytes[4] != VERSION) {
            throw GroupStoreException("malformed ${file.name} (${bytes.size} bytes)")
        }
        return GroupConfig(GroupId.fromBytes(bytes.copyOfRange(5, 21)), bytes.copyOfRange(21, SIZE))
    }

    private fun writeAtomically(bytes: ByteArray) {
        val dir = file.absoluteFile.parentFile ?: throw GroupStoreException("no parent dir for ${file.name}")
        var tmp: File? = null
        try {
            dir.mkdirs()
            val created = File.createTempFile(file.name, ".tmp", dir)
            tmp = created
            FileOutputStream(created).use {
                it.write(bytes)
                it.fd.sync()
            }
            Files.move(created.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            tmp = null
            try {
                FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (_: IOException) {
                // Some file systems cannot sync a directory; the rename itself is already atomic.
            }
        } catch (e: IOException) {
            throw GroupStoreException("cannot write ${file.name}", e)
        } finally {
            tmp?.delete()
        }
    }

    companion object {
        const val FILE_NAME = "kinsight-group.v1"
        private val MAGIC = "KGRP".encodeToByteArray()
        private const val VERSION: Byte = 1
        private const val SIZE = 4 + 1 + 16 + 32
    }
}
