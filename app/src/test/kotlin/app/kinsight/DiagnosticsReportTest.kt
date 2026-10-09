package app.kinsight

import app.kinsight.core.keystore.KeyBackendKind
import app.kinsight.core.keystore.KeySelection
import app.kinsight.core.keystore.KeyStorageException
import app.kinsight.core.keystore.SelfTestResult
import app.kinsight.core.model.DeviceKeys
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
}
