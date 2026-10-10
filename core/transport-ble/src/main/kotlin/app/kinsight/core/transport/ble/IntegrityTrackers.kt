package app.kinsight.core.transport.ble

import android.app.ApplicationExitInfo
import app.kinsight.core.model.proto.StateEvent

/** Queues one integrity event (UsagePipeline.queueEvent); false when it was dropped (the own-usage gate refused). */
typealias EventRecorder = suspend (kind: StateEvent.Kind, fromMs: Long, toMs: Long) -> Boolean

/**
 * When the own-usage gate opened (the consent time of an active CHILD); null while it is closed (no group, a parent,
 * no consent). While it is closed the trackers record nothing and keep no «since»: an interval starts no earlier than
 * the consent, and a state that still holds after consent is reported fresh (fix round 1).
 */
typealias GateOpenSince = suspend () -> Long?

/**
 * «Stop» (notification or app) and the next start from the app: one SYNC_STOPPED(from = stop, to = start), recorded
 * at the start because nothing is delivered while stopped (research §2.4: a user-stoppable FGS, openly visible to the
 * group). The interval is forgotten only once [record] accepted it: a crash or a refusal repeats it instead of losing
 * it. The Stop flag itself is cleared first, so a failing event never keeps sync stopped.
 */
class StopTracker(
    private val state: TransportStateStore,
    private val record: EventRecorder,
    private val gate: GateOpenSince,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun onUserStop() {
        state.markStoppedByUser(atMs = if (gate() != null) clock() else null)
    }

    suspend fun onUserStart() {
        val stopped = state.stoppedByUser()
        val since = state.stoppedAtMs()
        val open = gate()
        // Stopped before the gate opened (no since kept then): the interval the family sees starts at the consent.
        val from = if (open == null) null else since ?: open.takeIf { stopped }
        if (from == null) return state.clearStop()
        state.setStoppedByUser(false)
        if (record(StateEvent.Kind.SYNC_STOPPED, from, maxOf(from, clock()))) state.setStoppedAtMs(null)
    }
}

/**
 * Whether the sync notification can be seen (research §3 and §8 п. 5): NOTIFICATION_HIDDEN once when it becomes
 * invisible (permission or channel off), NOTIFICATION_SHOWN(from = hidden since, to = now) when it is back. The
 * «since» is kept only once HIDDEN was accepted, so a refused HIDDEN is tried again on the next check.
 */
class NotificationWatch(
    private val state: TransportStateStore,
    private val record: EventRecorder,
    private val gate: GateOpenSince,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun onVisibility(visible: Boolean) {
        val since = state.notificationHiddenSinceMs()
        if (gate() == null) {
            if (since != null) state.setNotificationHiddenSinceMs(null)
            return
        }
        if (!visible && since == null) {
            val now = clock()
            if (record(StateEvent.Kind.NOTIFICATION_HIDDEN, now, now)) state.setNotificationHiddenSinceMs(now)
        } else if (visible && since != null) {
            if (record(StateEvent.Kind.NOTIFICATION_SHOWN, since, maxOf(since, clock()))) state.setNotificationHiddenSinceMs(null)
        }
    }
}

/**
 * «Nearby devices» revoked (stage 3c §15 row): Android kills the app and nothing syncs, so one
 * NEARBY_PERMISSION_OFF(from = noticed, to = granted again) is recorded when the permission is back.
 */
class NearbyWatch(
    private val state: TransportStateStore,
    private val record: EventRecorder,
    private val gate: GateOpenSince,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun onPermission(granted: Boolean) {
        val since = state.nearbyOffSinceMs()
        if (gate() == null) {
            if (since != null) state.setNearbyOffSinceMs(null)
            return
        }
        if (!granted && since == null) {
            state.setNearbyOffSinceMs(clock())
        } else if (granted && since != null) {
            if (record(StateEvent.Kind.NEARBY_PERMISSION_OFF, since, maxOf(since, clock()))) state.setNearbyOffSinceMs(null)
        }
    }
}

/** One ApplicationExitInfo record; (timestamp, pid) identifies it whatever the wall clock does later. */
data class AppExit(val timestampMs: Long, val pid: Int)

/**
 * Force stops (stage 3c §15 row): the system keeps them in ApplicationExitInfo (REASON_USER_REQUESTED). At every
 * process start, with or without a group, each exit not seen before and newer than the first baseline becomes
 * APP_FORCE_STOPPED(from = stop, to = now) while the gate is open; with the gate closed it is only marked as seen
 * (stops before joining or consenting are not the family's business). The first check ever only sets the baseline.
 * Identity, never the wall clock, decides what is new, so setting the clock forward or back hides nothing. An exit
 * whose event was refused stays unseen and is tried again at the next start.
 */
class ForceStopCheck(
    private val state: TransportStateStore,
    private val record: EventRecorder,
    private val gate: GateOpenSince,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun onStart(stops: List<AppExit>) {
        val kept = stops.sortedByDescending { it.timestampMs }.take(MAX_SEEN).toSet()
        val marks = state.forceStopMarks()
        if (marks == null) {
            state.setForceStopMarks(ForceStopMarks(stops.maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE, kept))
            return
        }
        val fresh = stops.filter { it !in marks.seen && it.timestampMs > marks.baselineMs }.sortedBy { it.timestampMs }
        val refused = mutableSetOf<AppExit>()
        if (fresh.isNotEmpty() && gate() != null) {
            val now = clock()
            fresh.forEach { if (!record(StateEvent.Kind.APP_FORCE_STOPPED, it.timestampMs, maxOf(it.timestampMs, now))) refused += it }
        }
        // The system's own list only loses its oldest records, so what it no longer lists can never come back.
        state.setForceStopMarks(marks.copy(seen = kept - refused))
    }

    companion object {
        /** More user-requested exits than the system usually keeps for one app. */
        const val MAX_SEEN = 32

        /** API 31–33 report an app update as REASON_USER_REQUESTED too; an exit this close to the update is one. */
        const val UPDATE_WINDOW_MS = 60_000L

        /** Pure: whether an exit record is the user's force stop (API 34+ has REASON_PACKAGE_UPDATED for updates). */
        fun isUserForceStop(reason: Int, timestampMs: Long, sdkInt: Int, lastUpdateMs: Long): Boolean =
            reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
                !(sdkInt <= 33 && lastUpdateMs >= timestampMs - UPDATE_WINDOW_MS)
    }
}
