package app.kinsight.core.location

import android.content.Context

/**
 * Debug builds only (LOCATION_STATUS): the tracker as this module sees it. Counts, a mode and times only: never a
 * coordinate, a zone id or which zone the phone is in. [serviceForeground]: the outside service is in the foreground now;
 * [serviceStartsPending]: starts sent that have not reached it yet. Release builds do not have this class.
 */
data class LocationDebugStatus(
    val mode: Mode,
    val serviceForeground: Boolean,
    val serviceStartsPending: Int,
    val nextTickMs: Long?,
    val registeredZones: Int,
)

object LocationDebug {
    /** Null without an engine (the app has not installed its host yet). */
    suspend fun status(context: Context): LocationDebugStatus? = LocationGraph.engine(context)?.let { status(it) }

    /**
     * Task-6 step 2's refused branch: [start] makes startForegroundService throw (the caller's refusal), [foreground]
     * makes the service's startForeground throw (the service stops itself by its start id). Both off: the real path.
     */
    fun refuse(start: Boolean, foreground: Boolean): String {
        LocationSeams.refuseStart = start
        LocationSeams.refuseForeground = foreground
        return seams()
    }

    /** Task-6 step 4: each outside tick waits [ms] (0: none) before its work, so the broadcast is released before armTick. */
    fun slowTick(ms: Long): String {
        require(ms in 0..MAX_TICK_DELAY_MS) { "tick delay out of range" }
        LocationSeams.tickDelayMs = ms
        return seams()
    }

    fun seams(): String =
        "refuseStart=${LocationSeams.refuseStart} refuseForeground=${LocationSeams.refuseForeground} tickDelayMs=${LocationSeams.tickDelayMs}"

    /** Ten minutes: longer than any step needs, short enough that a forgotten switch does not hold the ticks for good. */
    const val MAX_TICK_DELAY_MS = 600_000L

    internal suspend fun status(engine: LocationEngine): LocationDebugStatus {
        val s = engine.savedState()
        return LocationDebugStatus(s.mode, ServiceGate.isForeground, ServiceGate.pendingStarts, s.nextTickMs, s.registered.size)
    }
}
