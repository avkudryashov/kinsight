import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseManifestRulesTest {
    private fun manifest(application: String = "", permissions: String = "", appAttrs: String = "") = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.kinsight">
            <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
            <uses-feature android:name="android.hardware.camera" android:required="false" />
            <uses-feature android:name="android.hardware.camera.any" android:required="false" />
            <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
            <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
            <uses-permission android:name="android.permission.CAMERA" />
            $permissions
            <application android:name=".KinsightApplication" $appAttrs>
                <meta-data android:name="isMonitoringTool" android:value="child_monitoring" />
                <activity android:name="app.kinsight.MainActivity" android:exported="true" />
                <activity android:name="androidx.glance.appwidget.action.ActionTrampolineActivity" android:exported="false" />
                $application
            </application>
        </manifest>
    """.trimIndent()

    @Test
    fun aCleanReleaseManifestPasses() {
        assertEquals(emptyList(), ReleaseManifestRules.violations(manifest()))
    }

    @Test
    fun debuggableIsRejected() {
        assertTrue(ReleaseManifestRules.violations(manifest(appAttrs = "android:debuggable=\"true\"")).any { "debuggable" in it })
    }

    @Test
    fun debugOnlyComponentsAreRejected() {
        val v = ReleaseManifestRules.violations(
            manifest(
                application = """
                    <activity android:name="androidx.compose.ui.tooling.PreviewActivity" android:exported="true" />
                    <activity android:name="androidx.activity.ComponentActivity" android:exported="true" />
                    <receiver android:name="app.kinsight.debug.DebugGroupReceiver" android:exported="true" />
                """,
            ),
        )
        assertEquals(3, v.size, v.toString())
    }

    @Test
    fun forbiddenPermissionsAreRejected() {
        val v = ReleaseManifestRules.violations(
            manifest(
                permissions = """
                    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
                    <uses-permission android:name="com.google.android.gms.permission.AD_ID" />
                    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
                    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
                    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
                    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
                    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
                    <uses-permission-sdk-23 android:name="android.permission.USE_EXACT_ALARM" />
                """,
            ),
        )
        assertEquals(8, v.size, v.toString())
    }

    @Test
    fun theNetworkIsAllowedForTheCloud() {
        val v = ReleaseManifestRules.violations(manifest(permissions = """<uses-permission android:name="android.permission.INTERNET" />"""))
        assertEquals(emptyList(), v)
    }

    @Test
    fun playServicesAndMlKitEntriesAreRejected() {
        val v = ReleaseManifestRules.violations(
            manifest(
                application = """
                    <activity android:name="com.google.android.gms.common.api.GoogleApiActivity" />
                    <meta-data android:name="com.google.android.gms.version" android:value="1" />
                    <service android:name="com.google.mlkit.common.internal.MlKitComponentDiscoveryService" />
                """,
            ),
        )
        assertEquals(3, v.size, v.toString())
    }

    @Test
    fun aRequiredCameraAndAMissingMonitoringFlagAreRejected() {
        val noFlag = manifest().replace("<meta-data android:name=\"isMonitoringTool\" android:value=\"child_monitoring\" />", "")
        assertTrue(ReleaseManifestRules.violations(noFlag).any { "isMonitoringTool" in it })
        val camera = manifest(permissions = "<uses-feature android:name=\"android.hardware.camera.any\" android:required=\"true\" />")
        assertTrue(ReleaseManifestRules.violations(camera).any { "camera" in it })
    }
}
