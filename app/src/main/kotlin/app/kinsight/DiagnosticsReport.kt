package app.kinsight

import app.kinsight.core.keystore.KeySelection
import app.kinsight.core.keystore.KeyStorageException
import app.kinsight.core.transport.ble.ServiceSnapshot
import app.kinsight.core.transport.ble.StatusText
import java.time.Instant

/** Plain-text key and transport diagnostics (technical, English only), shown by DiagnosticsActivity from Settings. */
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

    /**
     * The sync service as the screen sees it. [snapshot] should come from SyncGraph.snapshotNow, so the notification
     * fields are read now (Play policy: a hidden notification is the user's open choice, and this screen shows it).
     */
    fun transport(snapshot: ServiceSnapshot, group: String?, missingPermissions: List<String>): String = buildString {
        appendLine("Sync service: ${snapshot.state}${snapshot.detail?.let { " ($it)" } ?: ""}")
        appendLine("Group: ${group ?: "none"}")
        if (missingPermissions.isNotEmpty()) {
            appendLine("Missing permissions: ${missingPermissions.joinToString { it.substringAfterLast('.') }}")
        }
        appendLine("Notifications: ${notifications(snapshot)}")
        appendLine("Peers: ${snapshot.peers.takeIf { it.isNotEmpty() }?.joinToString() ?: "none"}")
        val last = snapshot.sync.lastSyncWallMs ?: snapshot.lastSyncWallMs
        appendLine("Last sync: ${last?.let { Instant.ofEpochMilli(it).toString() } ?: "never"}")
        snapshot.sync.lastProblem?.let { appendLine("Last problem: $it") }
    }

    /** Visible or not as the service and the integrity events decide (StatusText, preflight C18), with the detail. */
    private fun notifications(s: ServiceSnapshot): String = when (StatusText.notificationVisible(s)) {
        null -> "unknown"
        false -> if (s.notificationsEnabled == false) "DISABLED for the app" else "enabled, channel BLOCKED"
        true -> s.channelImportance?.let { "enabled, channel importance $it" } ?: "enabled, channel not created yet"
    }
}
