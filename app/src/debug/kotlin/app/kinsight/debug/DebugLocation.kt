package app.kinsight.debug

import app.kinsight.core.data.ZoneDraft
import app.kinsight.core.location.LocationDebugStatus
import app.kinsight.core.location.LocationPermissions
import app.kinsight.core.transport.ble.GateState
import kotlin.math.roundToLong

/** Debug builds only: ZONE_ADD's draft and LOCATION_STATUS's line. Neither ever prints a coordinate. */
object DebugLocation {
    /**
     * A new zone from adb extras: [lat] and [lon] in degrees as strings (`--es`, a float extra loses precision), [radius]
     * in metres. Range and name checks are the repository's (INVALID).
     */
    fun draft(lat: String?, lon: String?, radius: Int, name: String?): ZoneDraft {
        val latDeg = lat?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("lat extra missing or not a number")
        val lonDeg = lon?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("lon extra missing or not a number")
        require(radius > 0) { "radius extra missing" }
        return ZoneDraft(null, name ?: error("name extra missing"), e7(latDeg), e7(lonDeg), radius)
    }

    private fun e7(deg: Double): Int = (deg * 1e7).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    /** Open tamper problems: the integrity trackers' «since» marks still set (their names only). */
    fun problems(btOff: Long?, notificationHidden: Long?, nearbyOff: Long?, locationAccessOff: Long?, locationDisabled: Long?): List<String> =
        listOfNotNull(
            "BLUETOOTH_OFF".takeIf { btOff != null },
            "NOTIFICATION_HIDDEN".takeIf { notificationHidden != null },
            "NEARBY_PERMISSION_OFF".takeIf { nearbyOff != null },
            "LOCATION_PERMISSION_OFF".takeIf { locationAccessOff != null },
            "LOCATION_DISABLED".takeIf { locationDisabled != null },
        )

    fun statusLine(
        tracker: LocationDebugStatus?,
        gate: GateState,
        zones: Int?,
        access: LocationPermissions.State,
        systemEnabled: Boolean?,
        problems: List<String>,
        nowMs: Long,
    ): String {
        val gateText = when (gate) {
            is GateState.Open -> "open"
            GateState.Closed -> "closed"
            GateState.Unknown -> "unknown"
        }
        val trackerText = if (tracker == null) {
            "mode=none service=none nextTick=none registered=0"
        } else {
            val next = tracker.nextTickMs?.let { "$it(in ${(it - nowMs) / 1000}s)" } ?: "none"
            "mode=${tracker.mode} service=${if (tracker.serviceForeground) "running" else "stopped"} " +
                "serviceStartsPending=${tracker.serviceStartsPending} nextTick=$next registered=${tracker.registeredZones}"
        }
        return "gate=$gateText zones=${zones ?: "none"} $trackerText access=$access systemLocation=${systemEnabled ?: "unknown"} " +
            "problems=${problems.joinToString(",").ifEmpty { "none" }}"
    }
}
