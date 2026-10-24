package app.kinsight.core.blocking

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidSystemAllowListTest {
    @Test
    fun defaultDialerAndSmsAreAllowed() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val list = AndroidSystemAllowList(
            app, defaults = { setOf("com.vendor.dialer", "com.vendor.sms") }, homes = { emptySet() }, imes = { setOf("com.vendor.keyboard") },
        )
        assertTrue(list.contains("com.vendor.dialer"))
        assertTrue(list.contains("com.vendor.sms"))
        assertTrue(list.contains("com.android.systemui"))
        assertTrue(list.contains(app.packageName))
        assertFalse(list.contains("com.example.game"))
        // Overlays keep the app under them; a full app (the dialer) does not.
        assertTrue(list.contains("com.vendor.keyboard"))
        assertTrue(list.isOverlay("com.vendor.keyboard"))
        assertTrue(list.isOverlay("com.android.systemui"))
        assertFalse(list.isOverlay("com.vendor.dialer"))
        assertFalse(list.isOverlay(app.packageName))
    }

    @Test
    fun everyHomeAppIsAllowedNotOnlyTheDefault() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        for (pkg in listOf("com.vendor.home", "com.other.home")) {
            shadowOf(app.packageManager).addResolveInfoForIntent(home, ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Home" } })
        }
        val list = AndroidSystemAllowList(app, defaults = { emptySet() })
        assertTrue(list.contains("com.vendor.home"))
        assertTrue(list.contains("com.other.home"))
        assertTrue(list.isHome("com.other.home"))
        assertFalse(list.isHome("com.android.systemui"))
        assertFalse(list.contains("com.example.game"))
    }
}
