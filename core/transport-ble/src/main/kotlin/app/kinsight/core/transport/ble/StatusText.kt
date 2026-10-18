package app.kinsight.core.transport.ble

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.Member
import java.time.Instant
import java.time.ZoneId

enum class ServiceState { STOPPED, STARTING, NO_GROUP, STORAGE_ERROR, NO_PERMISSION, BLUETOOTH_OFF, RADIO_ERROR, RUNNING }

/**
 * What the service shows: its own state, the engine's status and the persisted last sync time. [peers] are the display
 * names of the members this device exchanges data with (spec §9.6, short form). [notificationsEnabled] and
 * [channelImportance] tell the diagnostics screen whether the notification can be seen at all (Play policy: the user
 * may refuse it, but openly); null until the service has posted it once.
 * [peersAreParents]: the peers are the parents who see this child's data (the notification says so); null while this
 * phone's role is unknown (not read yet, no group, storage error), which every switch decision treats as a child's.
 */
data class ServiceSnapshot(
    val state: ServiceState = ServiceState.STOPPED,
    val detail: String? = null,
    val sync: SyncStatus = SyncStatus(),
    val lastSyncWallMs: Long? = null,
    val peers: List<String> = emptyList(),
    val peersAreParents: Boolean? = null,
    val notificationsEnabled: Boolean? = null,
    val channelImportance: Int? = null,
    /** This device consented and its sharing switch is off (only a child's notification tells it). */
    val sharingPaused: Boolean = false,
    /** The family's Disk carries this phone's data too (SyncGraph.cloudSync): a Bluetooth problem alone is no problem then. */
    val cloudOn: Boolean = false,
    /** When this phone's last successful cloud run finished (wall clock), or null. */
    val cloudLastSyncMs: Long? = null,
)

/** The cloud as the service sees it, from SyncGraph.cloudSync (set by the app: core/data sits above this module). */
data class CloudSync(val on: Boolean = false, val lastSyncMs: Long? = null)

/** One sync status for both paths: the newest successful sync, by Bluetooth or through the Disk. */
object LastSync {
    fun of(bluetoothMs: Long?, cloudMs: Long?): Long? = listOfNotNull(bluetoothMs, cloudMs).maxOrNull()
}

/**
 * The two notification channels of the service. A child's keeps the old id, so the user's settings of it stay (spec §10:
 * permanent). A parent's is new and of the lowest importance (Android keeps an existing channel's importance, so a quiet
 * one needs a new id); the other one is deleted once the role is known.
 */
object ServiceChannels {
    const val CHILD = "kinsight-service"
    const val PARENT = "kinsight-sync-quiet"

    /** Unknown role (first post): the channel this phone already uses, the child's one on a fresh install. */
    fun forRole(peersAreParents: Boolean?, parentChannelExists: Boolean): String = when (peersAreParents) {
        false -> PARENT
        true -> CHILD
        null -> if (parentChannelExists) PARENT else CHILD
    }

    /** The channel this role no longer uses, to delete; null while the role is unknown. */
    fun retired(peersAreParents: Boolean?): String? = when (peersAreParents) {
        false -> CHILD
        true -> PARENT
        null -> null
    }
}

/** What the notification says, before it becomes text: a title and the lines under it. */
data class NotificationContent(val title: NotificationPart, val lines: List<NotificationPart>, val stop: Boolean)

sealed interface NotificationPart {
    data class Status(val line: StatusLine) : NotificationPart

    data object ChildTitle : NotificationPart

    data object Paused : NotificationPart

    data class Peers(val line: StatusText.PeerLine, val names: List<String>) : NotificationPart
}

/** The notification's peer line: names, and whether they are the parents who see this child's data (null: role unknown). */
data class NotificationPeers(val names: List<String>, val parentsOnly: Boolean?)

/** [lastSyncWallMs]: the newest sync by either path ([LastSync]). */
data class StatusLine(val kind: Kind, val lastSyncWallMs: Long?) {
    enum class Kind { STARTING, STORAGE_ERROR, NO_PERMISSION, BLUETOOTH_OFF, RADIO_ERROR, PEER_NEEDS_UPDATE, SYNCED, NEVER_SYNCED }
}

/**
 * The notification line (spec §8, §10): a real problem first, otherwise when this phone last synced, by Bluetooth or
 * through the Disk. With the cloud on, Bluetooth off (or its permission missing, or its radio failing) stops nothing.
 */
object StatusText {
    fun line(s: ServiceSnapshot): StatusLine {
        val last = lastSync(s)
        val synced = if (last != null) StatusLine.Kind.SYNCED else StatusLine.Kind.NEVER_SYNCED
        fun bluetooth(problem: StatusLine.Kind) = if (s.cloudOn) synced else problem
        val kind = when (s.state) {
            ServiceState.STORAGE_ERROR -> StatusLine.Kind.STORAGE_ERROR
            ServiceState.NO_PERMISSION -> bluetooth(StatusLine.Kind.NO_PERMISSION)
            ServiceState.BLUETOOTH_OFF -> bluetooth(StatusLine.Kind.BLUETOOTH_OFF)
            ServiceState.RADIO_ERROR -> bluetooth(StatusLine.Kind.RADIO_ERROR)
            ServiceState.STOPPED, ServiceState.STARTING, ServiceState.NO_GROUP -> StatusLine.Kind.STARTING
            ServiceState.RUNNING -> if (s.sync.lastProblem == SyncProblem.PEER_NEEDS_UPDATE) StatusLine.Kind.PEER_NEEDS_UPDATE else synced
        }
        return StatusLine(kind, last)
    }

    /** The newest sync by either path: the engine's (or the persisted) Bluetooth time and the last cloud run. */
    fun lastSync(s: ServiceSnapshot): Long? = LastSync.of(s.sync.lastSyncWallMs ?: s.lastSyncWallMs, s.cloudLastSyncMs)

    /**
     * A parent's notification is one quiet line: the status. A child's keeps the spec §10 title and lists the status,
     * the pause and who sees its data.
     */
    fun content(s: ServiceSnapshot): NotificationContent {
        val status = NotificationPart.Status(line(s))
        if (!childPhone(s)) return NotificationContent(status, emptyList(), stop = offersStop(s))
        val peers = peerLine(s)?.let { NotificationPart.Peers(it, s.peers) }
        val paused = NotificationPart.Paused.takeIf { showsPause(s) }
        return NotificationContent(NotificationPart.ChildTitle, listOfNotNull(status, paused, peers), stop = offersStop(s))
    }

    /** A sync of today shows its time only; an older one its date too. */
    fun sameDay(atMs: Long, nowMs: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    /** How long until the next local midnight after [nowMs], when the line is rebuilt (today's time gets its date). */
    fun untilNextMidnightMs(nowMs: Long, zone: ZoneId): Long {
        val next = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone)
        return next.toInstant().toEpochMilli() - nowMs
    }

    /**
     * Names for the notification (spec §9.6): a CHILD lists who sees its data, the parents; everyone else lists the
     * other members it syncs with. Sorted, duplicates kept (two devices may share a name).
     */
    fun peerNames(members: Collection<Member>, self: DeviceId): List<String> {
        val child = members.firstOrNull { it.id == self }?.isChild == true
        return members.filter { it.id != self && (!child || !it.isChild) }.map { it.displayName }.sorted()
    }

    /** How the notification names the peers: a child's parents see its data; anyone else just syncs with them. */
    enum class PeerLine { VISIBLE_TO, SYNCS_WITH }

    fun peerLine(s: ServiceSnapshot): PeerLine? = when {
        s.peers.isEmpty() -> null
        s.peersAreParents == true -> PeerLine.VISIBLE_TO
        else -> PeerLine.SYNCS_WITH
    }

    /** A child whose sharing is paused says so in its notification (nothing new reaches the parents meanwhile). */
    fun showsPause(s: ServiceSnapshot): Boolean = childPhone(s) && s.sharingPaused

    /**
     * Spec §10 «Постоянное уведомление на телефоне ребёнка «Этот телефон в семейной группе Kinsight»»: a child's phone
     * (its peers are the parents who see its data) says so in the title. Fail closed: a phone whose role is not known
     * (first post, Bluetooth off, no permission, storage error) is a child's until a read says otherwise.
     */
    fun childPhone(s: ServiceSnapshot): Boolean = s.peersAreParents != false

    /** This phone's role from the active members: true for a CHILD, false for a parent, null when not an active member. */
    fun selfIsChild(active: Map<DeviceId, Member>?, self: DeviceId): Boolean? = active?.get(self)?.isChild

    /** Spec §10: no switch on a child's phone, so its notification has no Stop (a parent stops sync with the PIN). */
    fun offersStop(s: ServiceSnapshot): Boolean = !childPhone(s)

    /** Whether the user can see the sync notification; null before the service has posted it once. */
    fun notificationVisible(s: ServiceSnapshot): Boolean? = when (s.notificationsEnabled) {
        null -> null
        false -> false
        true -> s.channelImportance != IMPORTANCE_NONE
    }

    /** NotificationManager.IMPORTANCE_NONE: the user turned the channel off. */
    private const val IMPORTANCE_NONE = 0
}
