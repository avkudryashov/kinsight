package app.kinsight.core.keystore

import android.content.Context
import java.io.File

/**
 * The cloud secret files of this app in noBackupFilesDir (excluded from backups like the identity), sealed with the
 * identity's Keystore AES key (DeviceKeyProvider.WRAP_ALIAS; the files carry their own AAD). Construction is cheap; the
 * Keystore key is fetched on first use, off the main thread.
 */
object CloudFiles {
    fun vault(context: Context): CloudTokenVault = CloudTokenVault(File(context.noBackupFilesDir, CloudTokenVault.FILE_NAME), wrap(context))

    fun groupKeys(context: Context): GroupKeyFile = GroupKeyFile(File(context.noBackupFilesDir, GroupKeyFile.FILE_NAME), wrap(context))

    private fun wrap(context: Context): KeyWrap {
        val app = context.applicationContext
        return AesGcmWrap { keystoreAesKey(app, DeviceKeyProvider.WRAP_ALIAS) }
    }
}
