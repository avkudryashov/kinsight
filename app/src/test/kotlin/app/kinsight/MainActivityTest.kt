package app.kinsight

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import app.kinsight.feature.onboarding.R as OnbR

/** The installed app as a fresh install meets it: launcher entry, deep link, first screen, both languages. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestKinsightApplication::class)
class MainActivityTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun theLauncherAndPairLinksOpenMainActivity() {
        val pm = app.packageManager
        val main = ComponentName(app, MainActivity::class.java)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        assertEquals(main, pm.resolveActivity(launcher, 0)?.activityInfo?.let { ComponentName(it.packageName, it.name) })
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("kinsight://pair/1/abc")).addCategory(Intent.CATEGORY_BROWSABLE)
        assertEquals(main, pm.resolveActivity(link, 0)?.activityInfo?.let { ComponentName(it.packageName, it.name) })
        val diagnostics = pm.getActivityInfo(ComponentName(app, DiagnosticsActivity::class.java), PackageManager.GET_META_DATA)
        assertEquals(false, diagnostics.exported)
        val meta = pm.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
        assertNotNull(meta)
        assertEquals("child_monitoring", meta.getString("isMonitoringTool"))
    }

    /** Play policy (research §1.4, §2) and the in-app promise «nothing goes to the internet», libraries included. */
    @Test
    fun theMergedManifestAsksForNothingForbidden() {
        @Suppress("DEPRECATION") // GET_PERMISSIONS: the flags overload needs API 33 and minSdk is 31
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.USE_EXACT_ALARM",
            "android.permission.CAMERA",
        )
        assertEquals(emptyList<String>(), forbidden.filter { it in requested })
        assertEquals(true, "android.permission.BLUETOOTH_SCAN" in requested) // the check reads the merged manifest
    }

    @Test
    fun aFreshInstallStartsTheFirstRun() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitUntil(LAUNCH_TIMEOUT_MS) { rule.onAllNodes(hasTestTag("onb_create")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasTestTag("onb_create")).assertExists()
            rule.onNode(hasTestTag("onb_join")).assertExists()
        }
    }

    @Test
    fun aPairLinkReachesTheFirstRun() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("kinsight://pair/1/not-an-invite"), app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(link).use {
            val invalid = app.getString(OnbR.string.onb_scan_invalid)
            rule.waitUntil(LAUNCH_TIMEOUT_MS) { rule.onAllNodes(hasText(invalid)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun tabsReadInEnglish() {
        assertEquals("Overview", app.getString(R.string.nav_overview))
        assertEquals("Kinsight", app.getString(R.string.app_name))
    }

    @Test
    @Config(qualifiers = "ru")
    fun tabsReadInRussian() {
        assertEquals("Обзор", app.getString(R.string.nav_overview))
        assertEquals("Группа", app.getString(R.string.nav_group))
        assertEquals("Настройки", app.getString(R.string.nav_settings))
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 20_000L
    }
}
