package app.kinsight.core.transport.ble

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.Member

enum class ServiceState { STOPPED, STARTING, NO_GROUP, STORAGE_ERROR, NO_PERMISSION, BLUETOOTH_OFF, RADIO_ERROR, RUNNING }

/**
 * What the service shows: its own state, the engine's status and the persisted last sync time. [peers] are the display
 * names of the members this device exchanges data with (spec §9.6, short form). [notificationsEnabled] and
 * [channelImportance] tell the diagnostics screen whether the notification can be seen at all (Play policy: the user
 * may refuse it, but openly); null until the service has posted it once.
 * [peersAreParents]: the peers are the parents who see this child's data (the notification says so).
 */
data class ServiceSnapshot(
    val state: ServiceState = ServiceState.STOPPED,
    val detail: String? = null,
    val sync: SyncStatus = SyncStatus(),
    val lastSyncWallMs: Long? = null,
    val peers: List<String> = emptyList(),
    val peersAreParents: Boolean = false,
    val notificationsEnabled: Boolean? = null,
    val channelImportance: Int? = null,
)

/** The notification's peer line: names, and whether they are the parents who see this child's data. */
data class NotificationPeers(val names: List<String>, val parentsOnly: Boolean)

data class StatusLine(val kind: Kind, val lastSyncWallMs: Long?) {
    enum class Kind { STARTING, STORAGE_ERROR, NO_PERMISSION, BLUETOOTH_OFF, RADIO_ERROR, SYNCING, PEER_NEEDS_UPDATE, WAITING }
}

/** The notification line (spec §8, §10): an environment problem first, then what sync is doing. */
object StatusText {
    fun line(s: ServiceSnapshot): StatusLine {
        val kind = when (s.state) {
            ServiceState.STORAGE_ERROR -> StatusLine.Kind.STORAGE_ERROR
            ServiceState.NO_PERMISSION -> StatusLine.Kind.NO_PERMISSION
            ServiceState.BLUETOOTH_OFF -> StatusLine.Kind.BLUETOOTH_OFF
            ServiceState.RADIO_ERROR -> StatusLine.Kind.RADIO_ERROR
            ServiceState.STOPPED, ServiceState.STARTING, ServiceState.NO_GROUP -> StatusLine.Kind.STARTING
            ServiceState.RUNNING -> when {
                s.sync.syncing -> StatusLine.Kind.SYNCING
                s.sync.lastProblem == SyncProblem.PEER_NEEDS_UPDATE -> StatusLine.Kind.PEER_NEEDS_UPDATE
                else -> StatusLine.Kind.WAITING
            }
        }
        return StatusLine(kind, s.sync.lastSyncWallMs ?: s.lastSyncWallMs)
    }

    /**
     * Names for the notification (spec §9.6): a CHILD lists who sees its data, the parents; everyone else lists the
     * other members it syncs with. Sorted, duplicates kept (two devices may share a name).
     */
    fun peerNames(members: Collection<Member>, self: DeviceId): List<String> {
        val child = members.firstOrNull { it.id == self }?.isChild == true
        return members.filter { it.id != self && (!child || !it.isChild) }.map { it.displayName }.sorted()
    }

    /** Whether the user can see the sync notification; null before the service has posted it once. */
    fun notificationVisible(s: ServiceSnapshot): Boolean? = when (s.notificationsEnabled) {
        null -> null
        false -> false
        true -> s.channelImportance != IMPORTANCE_NONE
    }

    /** NotificationManager.IMPORTANCE_NONE: the user turned the channel off. */
    private const val IMPORTANCE_NONE = 0
}
