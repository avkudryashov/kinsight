package app.kinsight.core.transport.ble

import android.app.ApplicationExitInfo
import app.kinsight.core.model.proto.StateEvent
import kotlin.math.abs

/** Queues one integrity event (UsagePipeline.queueEvent); false when it was dropped (the own-usage gate refused). */
typealias EventRecorder = suspend (kind: StateEvent.Kind, fromMs: Long, toMs: Long) -> Boolean

/**
 * The own-usage gate as the trackers see it. [Open]: an active CHILD that consented at [Open.sinceMs]. [Closed]: for
 * certain no reporting (no group, a parent, no consent); the trackers record nothing and keep no «since», so an
 * interval starts no earlier than the consent and a state that still holds after it is reported fresh. [Unknown]: the
 * group file or the identity could not be read just now; the trackers change nothing and wait for the next check.
 */
sealed interface GateState {
    data class Open(val sinceMs: Long) : GateState

    data object Closed : GateState

    data object Unknown : GateState
}

typealias GateOpenSince = suspend () -> GateState

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
        // Unknown keeps the time too: a start under a closed gate drops it, an open one reports it.
        state.markStoppedByUser(atMs = if (gate() != GateState.Closed) clock() else null)
    }

    suspend fun onUserStart() {
        val stopped = state.stoppedByUser()
        val since = state.stoppedAtMs()
        when (val g = gate()) {
            GateState.Closed -> state.clearStop()
            GateState.Unknown -> state.setStoppedByUser(false) // the interval waits for retryPending
            is GateState.Open -> {
                // Stopped before the gate opened (no since kept then): the interval the family sees starts at consent.
                val from = since ?: g.sinceMs.takeIf { stopped } ?: return state.clearStop()
                state.setStoppedByUser(false)
                report(from)
            }
        }
    }

    /**
     * A stop interval whose SYNC_STOPPED was refused or failed while sync runs again: retried at every
     * session preparation, not only at the next start from the app. Never while the user's Stop holds.
     */
    suspend fun retryPending() {
        if (state.stoppedByUser()) return
        val since = state.stoppedAtMs() ?: return
        when (gate()) {
            GateState.Closed -> state.setStoppedAtMs(null)
            GateState.Unknown -> Unit
            is GateState.Open -> report(since)
        }
    }

    private suspend fun report(from: Long) {
        if (record(StateEvent.Kind.SYNC_STOPPED, from, maxOf(from, clock()))) state.setStoppedAtMs(null)
    }
}

/**
 * Whether the sync notification can be seen (research §3 and §8 п. 5): NOTIFICATION_HIDDEN once when it becomes
 * invisible (permission or channel off), NOTIFICATION_SHOWN(from = hidden since, to = now) when it is back. The
 * «since» is kept only once HIDDEN was accepted, so a refused HIDDEN is tried again on the next check.
 * Until [onboardingDone] the notification being invisible is not hiding (emulator run 2026-10-10): the service starts at
 * the join, the notification permission is asked a few screens later, so nothing is recorded then; a refusal that
 * stands once onboarding is finished is reported at the next check.
 */
class NotificationWatch(
    private val state: TransportStateStore,
    private val record: EventRecorder,
    private val gate: GateOpenSince,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onboardingDone: suspend () -> Boolean = { true },
) {
    suspend fun onVisibility(visible: Boolean) {
        val since = state.notificationHiddenSinceMs()
        when (gate()) {
            GateState.Unknown -> return
            GateState.Closed -> {
                if (since != null) state.setNotificationHiddenSinceMs(null)
                return
            }
            is GateState.Open -> Unit
        }
        if (!visible && since == null) {
            if (!onboardingDone()) return
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
        when (gate()) {
            GateState.Unknown -> return
            GateState.Closed -> {
                if (since != null) state.setNearbyOffSinceMs(null)
                return
            }
            is GateState.Open -> Unit
        }
        if (!granted && since == null) {
            state.setNearbyOffSinceMs(clock())
        } else if (granted && since != null) {
            if (record(StateEvent.Kind.NEARBY_PERMISSION_OFF, since, maxOf(since, clock()))) state.setNearbyOffSinceMs(null)
        }
    }
}

/**
 * Spec §10: location access revoked (LOCATION_PERMISSION_OFF) and location turned off in the system (LOCATION_DISABLED).
 * Unlike Nearby, sync goes on while they are off, so the family hears at once: an open event (to = from = noticed), and
 * when it is back the closing one (from = noticed, to = back, always after from). A revoke counts only after the access
 * was once complete (the child granted it); the system switch counts at any time. [gate] is open while this phone should
 * share its location (core/data FamilyLocation.watchGate). A closed gate closes an open problem (the cloud went off, say)
 * and forgets the baseline even when the closing event is refused (unlike an open gate: a refusal there happens only once
 * this phone is no longer a consenting child, whom nobody watches any more); an unknown one changes nothing. A refused
 * event keeps no «since», so the next check tries again. [enabled] null: the system state is unknown (no
 * LocationManager), which opens and closes nothing. Returns true when an event was recorded (the caller signs and
 * uploads it at once).
 */
class LocationWatch(
    private val state: TransportStateStore,
    private val record: EventRecorder,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun onCheck(gate: GateState, access: Boolean, enabled: Boolean?): Boolean = onCheck(gate, { access }, { enabled })

    /**
     * [access] and [enabled] are read here, after the caller's lock was taken (Y5 parked minor): a check that waited for
     * another acts on the state as it is now, never on one read before the other check finished.
     */
    suspend fun onCheck(gate: GateState, access: () -> Boolean, enabled: () -> Boolean?): Boolean {
        val now = clock()
        val accessSince = state.locationAccessOffSinceMs()
        val offSince = state.locationDisabledSinceMs()
        when (gate) {
            GateState.Unknown -> return false
            GateState.Closed -> {
                var any = false
                if (accessSince != null) any = close(StateEvent.Kind.LOCATION_PERMISSION_OFF, accessSince, now) || any
                if (offSince != null) any = close(StateEvent.Kind.LOCATION_DISABLED, offSince, now) || any
                state.clearLocationWatch()
                return any
            }
            is GateState.Open -> Unit
        }
        val granted = access()
        val on = enabled()
        var any = false
        if (granted) {
            if (!state.locationAccessSeen()) state.setLocationAccessSeen(true)
            if (accessSince != null && close(StateEvent.Kind.LOCATION_PERMISSION_OFF, accessSince, now)) {
                state.setLocationAccessOffSinceMs(null)
                any = true
            }
        } else if (accessSince == null && state.locationAccessSeen()) {
            if (record(StateEvent.Kind.LOCATION_PERMISSION_OFF, now, now)) {
                state.setLocationAccessOffSinceMs(now)
                any = true
            }
        }
        if (on == true) {
            if (offSince != null && close(StateEvent.Kind.LOCATION_DISABLED, offSince, now)) {
                state.setLocationDisabledSinceMs(null)
                any = true
            }
        } else if (on == false && offSince == null) {
            if (record(StateEvent.Kind.LOCATION_DISABLED, now, now)) {
                state.setLocationDisabledSinceMs(now)
                any = true
            }
        }
        return any
    }

    /** The closing event: its end is after its start even if the clock went back, so it never reads as open. */
    private suspend fun close(kind: StateEvent.Kind, since: Long, now: Long) = record(kind, since, maxOf(since + 1, now))
}

/** One ApplicationExitInfo record; (timestamp, pid) identifies it whatever the wall clock does later. */
data class AppExit(val timestampMs: Long, val pid: Int)

/**
 * Force stops (stage 3c §15 row): the system keeps them in ApplicationExitInfo (REASON_USER_REQUESTED). At every
 * process start, with or without a group, each exit not seen before becomes APP_FORCE_STOPPED(from = stop, to = now)
 * while the gate is open; with the gate closed it is only marked as seen (stops before joining or consenting are not
 * the family's business). The first check ever only marks what the system lists then. Identity alone, never the wall
 * clock, decides what is new, so setting the clock forward or back hides nothing. An exit whose event was refused, or
 * met an [GateState.Unknown] gate, stays unseen and is tried again at the next start.
 */
class ForceStopCheck(
    private val state: TransportStateStore,
    private val record: EventRecorder,
    private val gate: GateOpenSince,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun onStart(stops: List<AppExit>) {
        val kept = stops.sortedByDescending { it.timestampMs }.take(MAX_SEEN).toSet()
        val marks = state.forceStopMarks() ?: return state.setForceStopMarks(ForceStopMarks(kept))
        val fresh = stops.filter { it !in marks.seen }.sortedBy { it.timestampMs }
        val unreported = mutableSetOf<AppExit>()
        if (fresh.isNotEmpty()) {
            when (gate()) {
                GateState.Closed -> Unit
                GateState.Unknown -> unreported += fresh
                is GateState.Open -> {
                    val now = clock()
                    fresh.forEach {
                        if (!record(StateEvent.Kind.APP_FORCE_STOPPED, it.timestampMs, maxOf(it.timestampMs, now))) unreported += it
                    }
                }
            }
        }
        // The system's own list only loses its oldest records, so what it no longer lists can never come back.
        state.setForceStopMarks(ForceStopMarks(kept - unreported))
    }

    companion object {
        /** More user-requested exits than the system keeps for one app (an older one beyond it would be reported again). */
        const val MAX_SEEN = 32

        /** API 31–33 report an app update as REASON_USER_REQUESTED too; an exit this close to the update (either side) is one. */
        const val UPDATE_WINDOW_MS = 60_000L

        /** Pure: whether an exit record is the user's force stop (API 34+ has REASON_PACKAGE_UPDATED for updates). */
        fun isUserForceStop(reason: Int, timestampMs: Long, sdkInt: Int, lastUpdateMs: Long): Boolean =
            reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
                !(sdkInt <= 33 && abs(timestampMs - lastUpdateMs) <= UPDATE_WINDOW_MS)
    }
}
