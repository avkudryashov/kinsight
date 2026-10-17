package app.kinsight.core.location

import app.kinsight.core.model.proto.Location
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The family side (core/data FamilyLocation): who may track, the zones, signing, the upload. Ruling L6: called under the
 * engine lock, so no method takes the cloud runLock or calls back into the engine; [upload] only enqueues work.
 */
interface LocationHost {
    /**
     * The zones to watch, or null when this phone must not track: not an active child that consented and shares, or a
     * group without a cloud storage (spec §8, §10).
     */
    suspend fun zones(): List<Zone>?

    /** Signs [events] in order, then [report], in one append; false without a group. */
    suspend fun record(events: List<ZoneEvent>, report: Report?): Boolean

    /** Spec §8.1, §9: what was just signed goes to the Disk now (a cloud run after any in flight). */
    fun upload()
}

interface FixSource {
    /** Precise and background location are granted (spec §12): the only state in which this phone tracks. */
    fun ready(): Boolean

    /** One fix (spec §8), or null within [timeoutMs]. */
    suspend fun fix(timeoutMs: Long): Fix?
}

interface ProximityAlerts {
    fun register(zone: Zone)

    fun unregister(zoneId: String)
}

/** The outside cadence of spec §8.1 and the in-zone check. */
interface Cadence {
    /** Starts the FOREGROUND_SERVICE_LOCATION service; false when Android refused (spec §14). */
    fun startService(): Boolean

    fun stopService()

    fun armTick(delayMs: Long)

    fun cancelTick()

    /** The in-zone check every 30 minutes. */
    fun schedulePeriodic()

    fun cancelPeriodic()
}

/** [PERIODIC]: the 30-minute check; [OUTSIDE]: the 5-minute tick. */
enum class Tick { PERIODIC, OUTSIDE }

/**
 * The child's tracking (spec §8.1) behind one lock: every entry point first asks [host] whether this phone tracks at
 * all (and [fixes] whether the permissions are there); when not, everything is turned off and the state cleared (only
 * the 30-minute check stays while the permissions alone are missing). Signing
 * comes before saving the state, so a crash repeats an event rather than losing it. Every fix passes [FixCheck] first: a
 * fix out of range is dropped, one without accuracy is vague. Lock order: the cloud runLock (a
 * wipe calling [forget]), then this lock, then the pipeline lock (inside [LocationHost.record]); the engine never takes
 * the runLock. Nothing here logs a coordinate.
 */
class LocationEngine(
    private val host: LocationHost,
    private val store: TrackerStore,
    private val fixes: FixSource,
    private val alerts: ProximityAlerts,
    private val cadence: Cadence,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /**
     * Zones changed, a start, a sync, a permission granted: registers the zones whose geometry is new (all of them with
     * [rearm]: boot, update, process start), removes the others, schedules the in-zone check, takes the first fix when
     * none decided yet, and lines the service and the tick up with the mode. With [rearm] or an overdue tick the outside
     * tick is armed for the saved time (never pushed back by a restart), or at once when that time has passed.
     */
    suspend fun refresh(rearm: Boolean = false): Unit = lock.withLock {
        val zones = active() ?: return@withLock
        lineUp(zones, rearm, armTick = true)
    }

    /**
     * True when it took a fix (the first decision). Without [armTick] the outside tick is left alone: the caller's own
     * fix re-arms it (a zero-delay tick armed here would bring a second fix seconds later).
     */
    private suspend fun lineUp(zones: List<Zone>, rearm: Boolean, armTick: Boolean): Boolean {
        val s = store.load()
        val wanted = zones.associateBy { it.signature }
        // Saved before the platform hears of them (Y4 parked minor): a process killed in between leaves every alert it
        // may hold on the list, so off() can still take them all back.
        if (!s.registered.containsAll(wanted.keys)) store.save(s.copy(registered = s.registered + wanted.keys))
        for (sig in s.registered - wanted.keys) alerts.unregister(ZoneTracker.idOf(sig))
        for ((sig, z) in wanted) if (rearm || sig !in s.registered) alerts.register(z)
        val registered = s.copy(registered = wanted.keys.toSet())
        store.save(registered)
        cadence.schedulePeriodic()
        if (registered.mode == Mode.UNKNOWN) {
            apply(ZoneTracker.fix(registered, zones, fix(), clock()))
            return true
        }
        if (armTick && registered.mode == Mode.OUTSIDE && (rearm || overdue(registered))) {
            // Emulator finding: a process start used to push the tick 5 minutes on each time. The saved time stays when it
            // is still ahead (capped at TICK_MS should the clock have gone back); a time already past fires at once.
            val now = clock()
            val next = registered.nextTickMs
            val due = if (next != null && next > now) minOf(next, now + TICK_MS) else now
            val armed = registered.copy(nextTickMs = due)
            if (armed != registered) store.save(armed)
            follow(armed, tickInMs = due - now)
        } else {
            follow(registered, tickInMs = null)
        }
        return false
    }

    /** A proximity alert (spec §8.1). An exit that leaves every zone takes its point first. */
    suspend fun onProximity(zoneId: String, entering: Boolean): Unit = lock.withLock {
        val zones = active() ?: return@withLock
        var s = store.load()
        if (zones.none { it.id == zoneId }) {
            // A stale alert of a deleted zone (Y4 parked minor): taken back here, the mode recomputed by the tracker.
            alerts.unregister(zoneId)
            s = s.copy(registered = s.registered.filterTo(LinkedHashSet()) { ZoneTracker.idOf(it) != zoneId })
        }
        val leavesAll = !entering && s.inside.none { id -> id != zoneId && zones.any { it.id == id } }
        val fix = if (leavesAll) fix() else null
        apply(ZoneTracker.proximity(s, zones, zoneId, entering, clock(), fix))
    }

    /**
     * The cadence: one fix and its report. An outside tick that finds the phone in a zone only stops the outside cadence.
     * The 30-minute check first lines the alerts up like [refresh] (emulator finding: access given back in the system
     * settings, with the app closed, is picked up here); when that took the first fix, the check is done.
     */
    suspend fun onTick(tick: Tick): Unit = lock.withLock {
        val zones = active() ?: return@withLock
        if (tick == Tick.PERIODIC && lineUp(zones, rearm = false, armTick = false)) return@withLock
        val s = store.load()
        if (tick == Tick.OUTSIDE && s.mode == Mode.IN_ZONE) return@withLock follow(s, tickInMs = null)
        apply(ZoneTracker.fix(s, zones, fix(), clock()))
    }

    /**
     * The saved tracker state, read without the lock (a fix may hold it for [FIX_TIMEOUT_MS]); the debug build's
     * LOCATION_STATUS (LocationDebug) reports its mode, tick and zone count, never [TrackerState.inside] ids or a point.
     */
    internal suspend fun savedState(): TrackerState = store.load()

    /** LocalReset and leaving (under the cloud runLock): every alert, the service, the tick, the check and the state go. */
    suspend fun forget(): Unit = lock.withLock { off(keepCheck = false) }

    /**
     * The zones when this phone tracks; otherwise everything is turned off first and null returned. A phone that should
     * track but lacks the permissions keeps the 30-minute check, so access given back outside the app is found by it.
     */
    private suspend fun active(): List<Zone>? {
        val zones = host.zones()
        if (zones == null) {
            off(keepCheck = false)
            return null
        }
        if (!fixes.ready()) {
            off(keepCheck = true)
            return null
        }
        return zones
    }

    /** Rulings L2, L4: whatever the source returned, only a checked fix reaches the tracker and a signature. */
    private suspend fun fix(): Fix? = fixes.fix(FIX_TIMEOUT_MS)?.let(FixCheck::checked)

    private suspend fun apply(step: Step) {
        // Not signed (no group any more): the state does not move on, so nothing is lost; the next run tries again.
        if ((step.events.isNotEmpty() || step.report != null) && !host.record(step.events, step.report)) return
        val outside = step.state.mode == Mode.OUTSIDE
        val state = step.state.copy(nextTickMs = if (outside) clock() + TICK_MS else null)
        store.save(state)
        follow(state, tickInMs = TICK_MS)
        // Spec §8.1: exits and entries go «сразу», outside points every 5 minutes; an in-zone report waits for §9's cadence.
        // An outside tick without a fix signed nothing, so it uploads nothing.
        if (step.events.isNotEmpty() || step.report is Report.Point) host.upload()
    }

    /** [tickInMs]: the outside tick to arm (replacing a pending one), or null to leave it. */
    private fun follow(s: TrackerState, tickInMs: Long?) {
        when (s.mode) {
            Mode.OUTSIDE -> {
                // A refused service (spec §14) leaves the tick: the points go on, without the notification.
                cadence.startService()
                if (tickInMs != null) cadence.armTick(tickInMs)
            }
            Mode.IN_ZONE -> {
                cadence.stopService()
                cadence.cancelTick()
            }
            Mode.UNKNOWN -> Unit
        }
    }

    private fun overdue(s: TrackerState): Boolean {
        val next = s.nextTickMs ?: return s.mode == Mode.OUTSIDE
        return s.mode == Mode.OUTSIDE && clock() > next + TICK_MS
    }

    private suspend fun off(keepCheck: Boolean) {
        val s = store.load()
        s.registered.forEach { alerts.unregister(ZoneTracker.idOf(it)) }
        // Unconditional: the in-zone check is scheduled before the first decision, while the state may still be empty.
        cadence.stopService()
        cadence.cancelTick()
        if (keepCheck) cadence.schedulePeriodic() else cadence.cancelPeriodic()
        if (s != TrackerState()) store.clear()
    }

    companion object {
        /** Spec §8.1: outside the zones a point every 5 minutes. */
        const val TICK_MS = 5 * 60_000L

        /** One fix waits at most this long (a receiver's goAsync window must hold it). */
        const val FIX_TIMEOUT_MS = 20_000L

        /** Ruling L4: the accuracy a fix without one carries, beyond [ZoneTracker.MAX_DECISIVE_ACCURACY_M] (decides nothing). */
        const val VAGUE_ACCURACY_M = 1_000
    }
}

/**
 * The gate every fix passes before the tracker sees it (rulings L2, L4). A fix with a non-finite or out-of-range
 * coordinate or accuracy, or without a fix time, is dropped (receivers would skip it anyway, LocationRules); a fix
 * without accuracy (none, zero or negative) is vague: it carries [LocationEngine.VAGUE_ACCURACY_M], never a precise 0.
 */
object FixCheck {
    const val MAX_LAT_E7 = 900_000_000
    const val MAX_LON_E7 = 1_800_000_000

    /** From the platform's degrees and metres ([accuracyM] null when the fix has none); null when unusable. */
    fun of(latDeg: Double, lonDeg: Double, accuracyM: Float?, timeMs: Long, provider: Location.Provider): Fix? {
        if (!latDeg.isFinite() || !lonDeg.isFinite()) return null
        if (accuracyM != null && !accuracyM.isFinite()) return null
        val latE7 = (latDeg * 1e7).roundToLong()
        val lonE7 = (lonDeg * 1e7).roundToLong()
        if (latE7 !in -MAX_LAT_E7..MAX_LAT_E7 || lonE7 !in -MAX_LON_E7..MAX_LON_E7) return null
        val accuracy = accuracyM?.let { ceil(it.toDouble()).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: 0
        return checked(Fix(latE7.toInt(), lonE7.toInt(), accuracy, timeMs, provider))
    }

    fun checked(f: Fix): Fix? = when {
        f.latE7 !in -MAX_LAT_E7..MAX_LAT_E7 || f.lonE7 !in -MAX_LON_E7..MAX_LON_E7 || f.timeMs <= 0 -> null
        f.accuracyM <= 0 -> f.copy(accuracyM = LocationEngine.VAGUE_ACCURACY_M)
        else -> f
    }
}
