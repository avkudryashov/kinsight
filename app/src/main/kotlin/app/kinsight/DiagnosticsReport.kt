package app.kinsight

import app.kinsight.core.keystore.KeySelection
import app.kinsight.core.keystore.KeyStorageException

/** Plain-text key diagnostics (technical, English only; replaced by the settings screen in stage 4). */
object DiagnosticsReport {
    fun format(selection: Result<KeySelection>, device: String): String = buildString {
        appendLine("Kinsight diagnostics")
        appendLine("Device: $device")
        selection.fold(
            onSuccess = { s ->
                appendLine("Key backend: ${s.kind}")
                appendLine("First run: ${if (s.firstRun) "yes" else "no"}")
                s.fallbackReason?.let { appendLine("Fallback reason: $it") }
                appendLine("Self-test: ${if (s.selfTest.passed) "passed" else "FAILED"} (${s.selfTest.detail})")
                appendLine("Device ID: ${s.keys.deviceId.hex}")
            },
            onFailure = { e ->
                if (e is KeyStorageException) {
                    appendLine("Key storage error: ${e::class.simpleName}: ${e.message}")
                    appendLine("Keys were not regenerated; a new identity needs clearing app data and re-pairing.")
                } else {
                    appendLine("Key selection failed: ${e::class.simpleName}: ${e.message}")
                }
            },
        )
    }
}
