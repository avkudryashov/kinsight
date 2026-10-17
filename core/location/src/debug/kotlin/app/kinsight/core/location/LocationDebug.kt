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

    internal suspend fun status(engine: LocationEngine): LocationDebugStatus {
        val s = engine.savedState()
        return LocationDebugStatus(s.mode, ServiceGate.isForeground, ServiceGate.pendingStarts, s.nextTickMs, s.registered.size)
    }
}
