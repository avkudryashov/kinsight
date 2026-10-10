package app.kinsight.core.transport.ble

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.Member

enum class ServiceState { STOPPED, STARTING, NO_GROUP, STORAGE_ERROR, NO_PERMISSION, BLUETOOTH_OFF, RUNNING }

/**
 * What the service shows: its own state, the engine's status and the persisted last sync time. [peers] are the display
 * names of the members this device exchanges data with (spec §9.6, short form). [notificationsEnabled] and
 * [channelImportance] tell the diagnostics screen whether the notification can be seen at all (Play policy: the user
 * may refuse it, but openly); null until the service has posted it once.
 */
data class ServiceSnapshot(
    val state: ServiceState = ServiceState.STOPPED,
    val detail: String? = null,
    val sync: SyncStatus = SyncStatus(),
    val lastSyncWallMs: Long? = null,
    val peers: List<String> = emptyList(),
    val notificationsEnabled: Boolean? = null,
    val channelImportance: Int? = null,
)

data class StatusLine(val kind: Kind, val lastSyncWallMs: Long?) {
    enum class Kind { STARTING, STORAGE_ERROR, NO_PERMISSION, BLUETOOTH_OFF, SYNCING, PEER_NEEDS_UPDATE, WAITING }
}

/** The notification line (spec §8, §10): an environment problem first, then what sync is doing. */
object StatusText {
    fun line(s: ServiceSnapshot): StatusLine {
        val kind = when (s.state) {
            ServiceState.STORAGE_ERROR -> StatusLine.Kind.STORAGE_ERROR
            ServiceState.NO_PERMISSION -> StatusLine.Kind.NO_PERMISSION
            ServiceState.BLUETOOTH_OFF -> StatusLine.Kind.BLUETOOTH_OFF
            ServiceState.STOPPED, ServiceState.STARTING, ServiceState.NO_GROUP -> StatusLine.Kind.STARTING
            ServiceState.RUNNING -> when {
                s.sync.syncing -> StatusLine.Kind.SYNCING
                s.sync.lastProblem == SyncProblem.PEER_NEEDS_UPDATE -> StatusLine.Kind.PEER_NEEDS_UPDATE
                else -> StatusLine.Kind.WAITING
            }
        }
        return StatusLine(kind, s.sync.lastSyncWallMs ?: s.lastSyncWallMs)
    }

    /** The other active members' display names, sorted (duplicates kept: two devices may share a name). */
    fun peerNames(members: Collection<Member>, self: DeviceId): List<String> =
        members.filter { it.id != self }.map { it.displayName }.sorted()
}
