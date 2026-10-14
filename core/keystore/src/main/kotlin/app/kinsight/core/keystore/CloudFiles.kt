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

    private val keyLock = Any()

    @Volatile
    private var sharedWrap: KeyWrap? = null

    /**
     * The one wrap over the Keystore key DeviceKeyProvider.WRAP_ALIAS for all cloud secret files. Memoised; the
     * create-if-missing key lookup is serialised inside keystoreAesKey, the same lock the identity's software backend
     * takes, so the alias is never generated from two places at once.
     */
    fun wrap(context: Context): KeyWrap {
        sharedWrap?.let { return it }
        return synchronized(keyLock) {
            sharedWrap ?: run {
                val app = context.applicationContext
                AesGcmWrap { keystoreAesKey(app, DeviceKeyProvider.WRAP_ALIAS) }.also { sharedWrap = it }
            }
        }
    }
}
