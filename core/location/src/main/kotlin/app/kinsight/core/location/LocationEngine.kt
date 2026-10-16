package app.kinsight.core.location

import app.kinsight.core.model.proto.Location
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.math.roundToLong

/** The family side (core/data FamilyLocation): who may track, the zones, signing, the upload. */
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
 * all (and [fixes] whether the permissions are there); when not, everything is turned off and the state cleared. Signing
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
     * none decided yet, and lines the service and the tick up with the mode (an overdue tick is armed again).
     */
    suspend fun refresh(rearm: Boolean = false): Unit = lock.withLock {
        val zones = active() ?: return@withLock off()
        val s = store.load()
        val wanted = zones.associateBy { it.signature }
        for (sig in s.registered - wanted.keys) alerts.unregister(ZoneTracker.idOf(sig))
        for ((sig, z) in wanted) if (rearm || sig !in s.registered) alerts.register(z)
        val registered = s.copy(registered = wanted.keys.toSet())
        store.save(registered)
        cadence.schedulePeriodic()
        if (registered.mode == Mode.UNKNOWN) {
            apply(ZoneTracker.fix(registered, zones, fix(), clock()))
        } else if (registered.mode == Mode.OUTSIDE && (rearm || overdue(registered))) {
            // The tick armed now is due in TICK_MS: recorded, so the next refresh does not push it back again.
            val armed = registered.copy(nextTickMs = clock() + TICK_MS)
            store.save(armed)
            follow(armed, armTick = true)
        } else {
            follow(registered, armTick = false)
        }
    }

    /** A proximity alert (spec §8.1). An exit that leaves every zone takes its point first. */
    suspend fun onProximity(zoneId: String, entering: Boolean): Unit = lock.withLock {
        val zones = active() ?: return@withLock off()
        val s = store.load()
        val leavesAll = !entering && s.inside.none { id -> id != zoneId && zones.any { it.id == id } }
        val fix = if (leavesAll) fix() else null
        apply(ZoneTracker.proximity(s, zones, zoneId, entering, clock(), fix))
    }

    /** The cadence: one fix and its report. An outside tick that finds the phone in a zone only stops the outside cadence. */
    suspend fun onTick(tick: Tick): Unit = lock.withLock {
        val zones = active() ?: return@withLock off()
        val s = store.load()
        if (tick == Tick.OUTSIDE && s.mode == Mode.IN_ZONE) return@withLock follow(s, armTick = false)
        apply(ZoneTracker.fix(s, zones, fix(), clock()))
    }

    /** LocalReset and leaving (under the cloud runLock): every alert, the service, the tick, the check and the state go. */
    suspend fun forget(): Unit = lock.withLock { off() }

    private suspend fun active(): List<Zone>? = if (!fixes.ready()) null else host.zones()

    /** Rulings L2, L4: whatever the source returned, only a checked fix reaches the tracker and a signature. */
    private suspend fun fix(): Fix? = fixes.fix(FIX_TIMEOUT_MS)?.let(FixCheck::checked)

    private suspend fun apply(step: Step) {
        if (step.events.isNotEmpty() || step.report != null) host.record(step.events, step.report)
        val outside = step.state.mode == Mode.OUTSIDE
        val state = step.state.copy(nextTickMs = if (outside) clock() + TICK_MS else null)
        store.save(state)
        follow(state, armTick = true)
        // Spec §8.1: exits and entries go «сразу», outside points every 5 minutes; an in-zone report waits for §9's cadence.
        // An outside tick without a fix signed nothing, so it uploads nothing.
        if (step.events.isNotEmpty() || step.report is Report.Point) host.upload()
    }

    private fun follow(s: TrackerState, armTick: Boolean) {
        when (s.mode) {
            Mode.OUTSIDE -> {
                // A refused service (spec §14) leaves the tick: the points go on, without the notification.
                cadence.startService()
                if (armTick) cadence.armTick(TICK_MS)
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

    private suspend fun off() {
        val s = store.load()
        s.registered.forEach { alerts.unregister(ZoneTracker.idOf(it)) }
        // Unconditional: the in-zone check is scheduled before the first decision, while the state may still be empty.
        cadence.stopService()
        cadence.cancelTick()
        cadence.cancelPeriodic()
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
