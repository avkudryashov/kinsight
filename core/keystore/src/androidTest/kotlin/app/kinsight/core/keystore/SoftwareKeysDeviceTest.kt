package app.kinsight.core.keystore

import android.content.pm.PackageManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import javax.crypto.SecretKeyFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SoftwareKeysDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.noBackupFilesDir, "kinsight-test-keys.bin")
    private val wrapAlias = "kinsight.test.wrap"
    private val keys = SoftwareKeys(file, AesGcmWrap { keystoreAesKey(context, wrapAlias) })

    @After
    fun tearDown() {
        file.delete()
        KeystoreKeys.keyStore().deleteEntry(wrapAlias)
    }

    @Test
    fun roundTripWithKeystoreWrap(): Unit = runBlocking {
        val created = keys.create()
        val loaded = keys.load()
        assertEquals(created.deviceId, loaded.deviceId)
        assertTrue(KeySelfTest.run(loaded).passed)
    }

    @Test
    fun wrapKeyUsesStrongBoxWhenPresent(): Unit = runBlocking {
        val key = keystoreAesKey(context, wrapAlias)
        val info = SecretKeyFactory.getInstance(key.algorithm, KeystoreKeys.ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val expected = if (strongBox) KeyProperties.SECURITY_LEVEL_STRONGBOX else KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
        assertEquals(expected, info.securityLevel)
    }

    @Test
    fun tamperedFileFailsLoudly(): Unit = runBlocking {
        keys.create()
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<KeyStorageException> { keys.load() }
    }

    @Test
    fun keystoreDeleteKeepsWrapKey(): Unit = runBlocking {
        val created = keys.create()
        KeystoreKeys(signAlias = "kinsight.test.sign", dhAlias = "kinsight.test.dh").delete()
        assertTrue(KeystoreKeys.keyStore().containsAlias(wrapAlias))
        assertEquals(created.deviceId, keys.load().deviceId)
    }

    @Test
    fun directoryChannelOpensForFsync(): Unit = runBlocking {
        // writeAtomically skips the directory fsync only when this open fails; on Android it must not.
        FileChannel.open(context.noBackupFilesDir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
