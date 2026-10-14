package app.kinsight.core.keystore

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The cloud secret files of this app in noBackupFilesDir (excluded from backups like the identity), sealed with the
 * identity's Keystore AES key (DeviceKeyProvider.WRAP_ALIAS; the files carry their own AAD). One process-wide instance
 * per file, so the instances' @Synchronized excludes concurrent callers (a rotated refresh token must not be lost).
 * Construction is cheap; the Keystore key is fetched on first use, off the main thread.
 */
object CloudFiles {
    private val vaults = ConcurrentHashMap<String, CloudTokenVault>()
    private val keyFiles = ConcurrentHashMap<String, GroupKeyFile>()

    fun vault(context: Context): CloudTokenVault = vault(File(context.noBackupFilesDir, CloudTokenVault.FILE_NAME)) { wrap(context) }

    fun groupKeys(context: Context): GroupKeyFile = groupKeys(File(context.noBackupFilesDir, GroupKeyFile.FILE_NAME)) { wrap(context) }

    internal fun vault(file: File, wrap: () -> KeyWrap): CloudTokenVault =
        vaults.computeIfAbsent(file.canonicalPath) { CloudTokenVault(file, wrap()) }

    internal fun groupKeys(file: File, wrap: () -> KeyWrap): GroupKeyFile =
        keyFiles.computeIfAbsent(file.canonicalPath) { GroupKeyFile(file, wrap()) }

    private fun wrap(context: Context): KeyWrap {
        val app = context.applicationContext
        return AesGcmWrap { keystoreAesKey(app, DeviceKeyProvider.WRAP_ALIAS) }
    }
}
