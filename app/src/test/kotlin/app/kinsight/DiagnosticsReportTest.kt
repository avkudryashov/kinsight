package app.kinsight

import app.kinsight.core.keystore.KeyBackendKind
import app.kinsight.core.keystore.KeySelection
import app.kinsight.core.keystore.KeyStorageException
import app.kinsight.core.keystore.SelfTestResult
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.transport.ble.ServiceSnapshot
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.transport.ble.SyncProblem
import app.kinsight.core.transport.ble.SyncStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsReportTest {
    private val keys = DeviceKeys.generate()

    @Test
    fun showsKeystoreSelection() {
        val s = KeySelection(KeyBackendKind.KEYSTORE, keys, SelfTestResult(true, "ok"), firstRun = true, fallbackReason = null)
        val text = DiagnosticsReport.format(Result.success(s), "Google Pixel 8a, API 37")
        assertTrue("Key backend: KEYSTORE" in text, text)
        assertTrue("Self-test: passed (ok)" in text, text)
        assertTrue("Device ID: ${keys.deviceId.hex}" in text, text)
        assertTrue("First run: yes" in text, text)
        assertFalse("Fallback reason" in text, text)
    }

    @Test
    fun showsSoftwareFallbackReasonAndFailedSelfTest() {
        val s = KeySelection(KeyBackendKind.SOFTWARE, keys, SelfTestResult(false, "boom"), firstRun = false, fallbackReason = "keystore unavailable: x")
        val text = DiagnosticsReport.format(Result.success(s), "dev")
        assertTrue("Key backend: SOFTWARE" in text, text)
        assertTrue("Fallback reason: keystore unavailable: x" in text, text)
        assertTrue("Self-test: FAILED (boom)" in text, text)
        assertTrue("First run: no" in text, text)
    }

    @Test
    fun storageErrorSaysKeysWereNotReplaced() {
        val text = DiagnosticsReport.format(Result.failure(KeyStorageException("Keystore key kinsight.sign.v1 is missing")), "dev")
        assertTrue("Key storage error: KeyStorageException: Keystore key kinsight.sign.v1 is missing" in text, text)
        assertTrue("not regenerated" in text, text)
        assertFalse("Device ID" in text, text)
    }

    @Test
    fun otherFailureIsNeutral() {
        val text = DiagnosticsReport.format(Result.failure(IllegalStateException("oops")), "dev")
        assertTrue("Key selection failed: IllegalStateException: oops" in text, text)
        assertFalse("not regenerated" in text, text)
    }

    @Test
    fun transportWithoutAGroup() {
        val text = DiagnosticsReport.transport(ServiceSnapshot(ServiceState.STOPPED), group = null, missingPermissions = emptyList())
        assertTrue("Sync service: STOPPED" in text, text)
        assertTrue("Group: none" in text, text)
        assertTrue("Last sync: never" in text, text)
        assertFalse("Missing permissions" in text, text)
        assertTrue("Notifications: unknown" in text, text)
        assertTrue("Peers: none" in text, text)
    }

    @Test
    fun transportShowsStateProblemAndPermissions() {
        val snapshot = ServiceSnapshot(
            ServiceState.STORAGE_ERROR,
            detail = "KeyStorageException: lost choice",
            sync = SyncStatus(lastSyncWallMs = 0, lastProblem = SyncProblem.LINK_FAILED),
        )
        val text = DiagnosticsReport.transport(snapshot, group = "ab".repeat(16), missingPermissions = listOf("android.permission.BLUETOOTH_SCAN"))
        assertTrue("Sync service: STORAGE_ERROR (KeyStorageException: lost choice)" in text, text)
        assertTrue("Group: ${"ab".repeat(16)}" in text, text)
        assertTrue("Missing permissions: BLUETOOTH_SCAN" in text, text)
        assertTrue("Last sync: 1970-01-01T00:00:00Z" in text, text)
        assertTrue("Last problem: LINK_FAILED" in text, text)
    }

    @Test
    fun transportShowsNotificationVisibilityAndPeers() {
        val shown = ServiceSnapshot(ServiceState.RUNNING, peers = listOf("galaxy s23", "pixel"), notificationsEnabled = true, channelImportance = 2)
        val text = DiagnosticsReport.transport(shown, group = "ab".repeat(16), missingPermissions = emptyList())
        assertTrue("Notifications: enabled, channel importance 2" in text, text)
        assertTrue("Peers: galaxy s23, pixel" in text, text)

        val appOff = DiagnosticsReport.transport(shown.copy(notificationsEnabled = false), "g", emptyList())
        assertTrue("Notifications: DISABLED for the app" in appOff, appOff)

        val channelOff = DiagnosticsReport.transport(shown.copy(channelImportance = 0), "g", emptyList())
        assertTrue("Notifications: enabled, channel BLOCKED" in channelOff, channelOff)

        val noChannel = DiagnosticsReport.transport(shown.copy(channelImportance = null), "g", emptyList())
        assertTrue("Notifications: enabled, channel not created yet" in noChannel, noChannel)
    }
}
