package app.kinsight.core.blocking

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Bundle
import android.telecom.InCallService
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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

    /** Review I1: Samsung and MIUI show a call in their own package; it must never be closed. */
    @Test
    fun inCallScreensAreAllowedFixedAndResolved() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val call = Intent(InCallService.SERVICE_INTERFACE)
        fun service(pkg: String, permission: String?, ui: Boolean) = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = pkg
                name = "$pkg.InCall"
                this.permission = permission
                metaData = Bundle().apply { putBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_UI, ui) }
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(call, service("com.vendor.incallui", Manifest.permission.BIND_INCALL_SERVICE, ui = true))
        shadowOf(app.packageManager).addResolveInfoForIntent(call, service("com.example.carlink", Manifest.permission.BIND_INCALL_SERVICE, ui = false))
        shadowOf(app.packageManager).addResolveInfoForIntent(call, service("com.example.fake", null, ui = true))
        assertEquals(setOf("com.vendor.incallui"), AndroidSystemAllowList.inCallUiPackages(app.packageManager))

        val list = AndroidSystemAllowList(app, defaults = { setOf("com.google.android.dialer") }, homes = { emptySet() }, imes = { emptySet() })
        assertTrue("resolved", list.contains("com.vendor.incallui"))
        assertTrue("Samsung", list.contains("com.samsung.android.incallui"))
        assertTrue("AOSP, MIUI", list.contains("com.android.incallui"))
        assertFalse("not a call screen", list.contains("com.example.carlink"))
        assertFalse("not bound by Telecom", list.contains("com.example.fake"))
        assertFalse("a full screen: the app behind it is not re-checked", list.isOverlay("com.samsung.android.incallui"))
    }

    @Test
    fun aPhoneCallIsSeenByTheAudioMode() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val audio = app.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
        assertFalse(AndroidSystemAllowList(app).inCall())
        audio.mode = AudioManager.MODE_IN_CALL
        assertTrue(AndroidSystemAllowList(app).inCall())
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        assertFalse("a VoIP call does not pause blocking", AndroidSystemAllowList(app).inCall())
    }
}
