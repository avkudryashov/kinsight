package app.kinsight

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.FeatureInfo
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
import org.robolectric.Robolectric
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
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.USE_EXACT_ALARM",
        )
        assertEquals(emptyList<String>(), forbidden.filter { it in requested })
        assertEquals(true, "android.permission.BLUETOOTH_SCAN" in requested) // the check reads the merged manifest
        // Spec §2: the cloud is an option of the same app, off by default; the permissions are in the manifest.
        assertEquals(true, "android.permission.INTERNET" in requested)
        assertEquals(true, "android.permission.ACCESS_NETWORK_STATE" in requested)
    }

    /** Spec §3, §14: the Android platform's yx<client_id> redirect opens the running app. */
    @Test
    fun theYandexRedirectOpensMainActivity() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.YANDEX_SCHEME}://authorize?code=1234567&state=s"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val main = ComponentName(app, MainActivity::class.java)
        assertEquals(main, app.packageManager.resolveActivity(link, 0)?.activityInfo?.let { ComponentName(it.packageName, it.name) })
    }

    /** A deep link must land in the running instance (onNewIntent), never in a second copy of the app. */
    @Test
    fun mainActivityIsSingleTaskAndNoGoogleScannerIsDeclared() {
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        val meta = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
        assertEquals(null, meta?.getString("com.google.mlkit.vision.DEPENDENCIES"))
    }

    /** CAMERA would imply a required rear camera: Play would hide Kinsight from phones without one. */
    @Test
    fun noCameraFeatureIsRequired() {
        val features = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_CONFIGURATIONS).reqFeatures.orEmpty()
        val camera = features.filter { it.name?.startsWith("android.hardware.camera") == true }
        assertEquals(setOf("android.hardware.camera", "android.hardware.camera.any"), camera.map { it.name }.toSet())
        assertEquals(emptyList<String>(), camera.filter { it.flags and FeatureInfo.FLAG_REQUIRED != 0 }.map { it.name })
    }

    @Test
    fun aPairLinkToTheRunningAppReachesTheFirstRun() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            rule.waitUntil(LAUNCH_TIMEOUT_MS) { rule.onAllNodes(hasTestTag("onb_create")).fetchSemanticsNodes().isNotEmpty() }
            controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("kinsight://pair/1/not-an-invite")))
            val invalid = app.getString(OnbR.string.onb_scan_invalid)
            rule.waitUntil(LAUNCH_TIMEOUT_MS) { rule.onAllNodes(hasText(invalid)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun aDeviceExtraIsReadOnlyFromAHexId() {
        val hex = "cc".repeat(16)
        assertEquals(hex, MainActivity.deviceOf(MainActivity.openDevice(app, hex)))
        assertEquals(null, MainActivity.deviceOf(Intent().putExtra(MainActivity.EXTRA_DEVICE, "not hex")))
        assertEquals(null, MainActivity.deviceOf(null))
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

    /** Final fix item 3: a rotation is not «leaving the app»; only a real stop redraws the widget. */
    @Test
    fun onlyARealStopRefreshesTheWidget() {
        var refreshes = 0
        val previous = MainActivity.refreshWidget
        MainActivity.refreshWidget = { refreshes++ }
        try {
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                controller.recreate()
                assertEquals("a configuration change", 0, refreshes)
                controller.pause().stop()
                assertEquals(1, refreshes)
            }
        } finally {
            MainActivity.refreshWidget = previous
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
