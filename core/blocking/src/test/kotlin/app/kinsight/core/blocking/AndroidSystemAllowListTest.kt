package app.kinsight.core.blocking

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidSystemAllowListTest {
    @Test
    fun defaultDialerAndSmsAreAllowed() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val list = AndroidSystemAllowList(app, defaults = { setOf("com.vendor.dialer", "com.vendor.sms") })
        assertTrue(list.contains("com.vendor.dialer"))
        assertTrue(list.contains("com.vendor.sms"))
        assertTrue(list.contains("com.android.systemui"))
        assertTrue(list.contains(app.packageName))
        assertFalse(list.contains("com.example.game"))
    }
}
