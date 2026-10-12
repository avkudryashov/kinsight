package app.kinsight.core.keystore

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class DeviceKeyProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.noBackupFilesDir, "provider-test").apply { deleteRecursively(); mkdirs() }
    private val prefix = "kinsight.test.provider."

    @After
    fun tearDown() {
        dir.deleteRecursively()
        KeystoreKeys(prefix + KeystoreKeys.SIGN_ALIAS, prefix + KeystoreKeys.DH_ALIAS).delete()
        KeystoreKeys.keyStore().deleteEntry(prefix + DeviceKeyProvider.WRAP_ALIAS)
    }

    @Test
    fun selectsOnceAndKeepsChoice(): Unit = runBlocking {
        val first = DeviceKeyProvider.selector(context, dir, prefix).select()
        Log.i(TAG, "${Build.MODEL} API ${Build.VERSION.SDK_INT}: backend=${first.kind} reason=${first.fallbackReason} selfTest=${first.selfTest}")
        assertTrue(first.firstRun)
        assertTrue(first.selfTest.passed, first.selfTest.detail)
        val second = DeviceKeyProvider.selector(context, dir, prefix).select()
        assertFalse(second.firstRun)
        assertEquals(first.kind, second.kind)
        assertEquals(first.keys.deviceId, second.keys.deviceId)
    }

    private companion object {
        const val TAG = "KinsightKeystoreTest"
    }
}
