package app.kinsight.core.transport.ble

import app.kinsight.core.model.proto.StateEvent

/**
 * «Stop» (notification or app) and the next start from the app: one SYNC_STOPPED(from = stop, to = start), recorded
 * at the start because nothing is delivered while stopped (research §2.4: a user-stoppable FGS, openly visible to the
 * group). [record] runs before the interval is forgotten: a crash repeats it instead of losing it.
 */
class StopTracker(
    private val state: TransportStateStore,
    private val record: suspend (StateEvent.Kind, Long, Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun onUserStop() {
        if (state.stoppedAtMs() == null) state.setStoppedAtMs(clock())
        state.setStoppedByUser(true)
    }

    suspend fun onUserStart() {
        val since = state.stoppedAtMs()
        if (since != null) record(StateEvent.Kind.SYNC_STOPPED, since, maxOf(since, clock()))
        state.setStoppedAtMs(null)
        state.setStoppedByUser(false)
    }
}

/**
 * Whether the sync notification can be seen (research §3 and §8 п. 5): NOTIFICATION_HIDDEN once when it becomes
 * invisible (permission or channel off), NOTIFICATION_SHOWN(from = hidden since, to = now) when it is back.
 */
class NotificationWatch(
    private val state: TransportStateStore,
    private val record: suspend (StateEvent.Kind, Long, Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun onVisibility(visible: Boolean) {
        val since = state.notificationHiddenSinceMs()
        if (!visible && since == null) {
            val now = clock()
            record(StateEvent.Kind.NOTIFICATION_HIDDEN, now, now)
            state.setNotificationHiddenSinceMs(now)
        } else if (visible && since != null) {
            record(StateEvent.Kind.NOTIFICATION_SHOWN, since, maxOf(since, clock()))
            state.setNotificationHiddenSinceMs(null)
        }
    }
}

/**
 * «Nearby devices» revoked (stage 3c §15 row): Android kills the app and nothing syncs, so one
 * NEARBY_PERMISSION_OFF(from = noticed, to = granted again) is recorded when the permission is back.
 */
class NearbyWatch(
    private val state: TransportStateStore,
    private val record: suspend (StateEvent.Kind, Long, Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun onPermission(granted: Boolean) {
        val since = state.nearbyOffSinceMs()
        if (!granted && since == null) {
            state.setNearbyOffSinceMs(clock())
        } else if (granted && since != null) {
            record(StateEvent.Kind.NEARBY_PERMISSION_OFF, since, maxOf(since, clock()))
            state.setNearbyOffSinceMs(null)
        }
    }
}

/**
 * Force stops (stage 3c §15 row): the system keeps them in ApplicationExitInfo (REASON_USER_REQUESTED). At process
 * start every recorded stop newer than the last check becomes APP_FORCE_STOPPED(from = stop, to = now). The first
 * check only sets the baseline: stops before joining the group are not the family's business.
 */
class ForceStopCheck(
    private val state: TransportStateStore,
    private val record: suspend (StateEvent.Kind, Long, Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun onStart(stops: List<Long>) {
        val last = state.forceStopCheckedMs()
        val now = clock()
        if (last != null) stops.filter { it > last }.sorted().forEach { record(StateEvent.Kind.APP_FORCE_STOPPED, it, maxOf(it, now)) }
        state.setForceStopCheckedMs(now)
    }
}
