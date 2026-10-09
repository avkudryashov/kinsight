package app.kinsight.core.keystore

import android.content.Context
import java.io.File

/** Wires the selector to Android: files in noBackupFilesDir, keys under the kinsight.*.v1 aliases. */
object DeviceKeyProvider {
    const val WRAP_ALIAS = "kinsight.wrap.v1"
    const val CHOICE_FILE = "kinsight-key-backend.v1"
    const val SOFTWARE_FILE = "kinsight-keys.v1.bin"

    /** Blocking (Keystore and file I/O): call off the main thread. */
    fun select(context: Context): KeySelection =
        selector(context.applicationContext, context.noBackupFilesDir, aliasPrefix = "").select()

    /** [aliasPrefix] and [dir] let instrumented tests run without touching the app's real identity. */
    internal fun selector(context: Context, dir: File, aliasPrefix: String) = KeyBackendSelector(
        choice = FileChoiceStore(File(dir, CHOICE_FILE)),
        keystore = KeystoreKeys(aliasPrefix + KeystoreKeys.SIGN_ALIAS, aliasPrefix + KeystoreKeys.DH_ALIAS),
        software = SoftwareKeys(File(dir, SOFTWARE_FILE), AesGcmWrap { keystoreAesKey(context, aliasPrefix + WRAP_ALIAS) }),
    )
}
