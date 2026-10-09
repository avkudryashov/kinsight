package app.kinsight.core.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.cancellation.CancellationException

/** The AES-256-GCM key that seals software keys: StrongBox when the device has it, TEE otherwise. */
internal fun keystoreAesKey(context: Context, alias: String): SecretKey {
    (KeystoreKeys.keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
    val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    return generateWithStrongBoxFallback(strongBox) { generateAes(alias, it) }
}

/**
 * StrongBox keygen can fail with more than StrongBoxUnavailableException (ProviderException, a keymaster error
 * wrapped in InvalidAlgorithmParameterException, ...), so any failure of a StrongBox attempt retries on the TEE.
 * A failing TEE attempt propagates; cancellation is never swallowed.
 */
internal fun generateWithStrongBoxFallback(strongBox: Boolean, generate: (strongBox: Boolean) -> SecretKey): SecretKey {
    if (!strongBox) return generate(false)
    return try {
        generate(true)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        generate(false)
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
