package app.kinsight.core.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The AES-256-GCM key that seals software keys: StrongBox when the device has it, TEE otherwise. */
internal fun keystoreAesKey(context: Context, alias: String): SecretKey {
    (KeystoreKeys.keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
    val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    return try {
        generateAes(alias, strongBox)
    } catch (_: StrongBoxUnavailableException) {
        generateAes(alias, strongBox = false)
    }
}

private fun generateAes(alias: String, strongBox: Boolean): SecretKey =
    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystoreKeys.ANDROID_KEYSTORE).apply {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setIsStrongBoxBacked(strongBox)
                .build(),
        )
    }.generateKey()
