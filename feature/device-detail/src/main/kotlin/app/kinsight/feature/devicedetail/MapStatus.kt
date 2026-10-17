package app.kinsight.feature.devicedetail

import app.kinsight.core.data.ChildLocation
import app.kinsight.core.data.GeoPoint
import app.kinsight.core.data.LocationReadModel
import app.kinsight.core.data.PlaceStatus
import app.kinsight.core.data.SetupWarning
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.ZoneView
import app.kinsight.core.location.Geo
import app.kinsight.core.model.proto.StateEvent
import kotlin.math.roundToInt

/** Why a child has no place yet (UI refresh «Ошибки и пустые состояния»), the first that applies. */
enum class NoPlaceReason { CLOUD_OFF, NOT_SHARING, PERMISSION_OFF, LOCATION_DISABLED, WAITING }

/** The big status line of one child on the Карта tab (UI refresh §5). Computed on this phone, nothing is sent. */
sealed interface MapStatus {
    /** In zone [zoneName] (null: a zone this phone does not know) since [sinceMs], today's entry when known. */
    data class InZone(val zoneName: String?, val sinceMs: Long?) : MapStatus

    /** Outside every zone; [nearestZone] and [distanceM] to its edge (0: at the edge or inside its circle) when known. */
    data class Outside(val nearestZone: String?, val distanceM: Int?) : MapStatus

    /** The newest word is older than LocationReadModel.PLACE_STALE_MS: «Последняя точка N назад». */
    data class Stale(val atMs: Long) : MapStatus

    data class NoPlace(val reason: NoPlaceReason) : MapStatus

    /** Task 12: the group has no zone at all, so «Вне зон» would say nothing; [atMs] the newest word's time. */
    data class NoZones(val atMs: Long) : MapStatus

    companion object {
        /** [cloudOn]: the family's sync through the Disk is on; locations travel only through it. */
        fun of(c: ChildLocation, cloudOn: Boolean, nowMs: Long): MapStatus {
            val s = c.status ?: return NoPlace(reason(c, cloudOn))
            if (LocationReadModel.isStale(s, nowMs)) return Stale(s.atMs)
            return when (s) {
                is PlaceStatus.InZone -> {
                    // Today's zone events, newest first: «с HH:MM» only when this zone's newest event is its entry.
                    val newest = c.zoneEvents.firstOrNull { it.zoneId == s.zoneId }
                    InZone(s.zoneName, newest?.takeIf { it.kind == StateEvent.Kind.ZONE_ENTER }?.atMs)
                }
                is PlaceStatus.Outside -> if (c.zones.isEmpty()) NoZones(s.atMs) else nearest(s.point ?: c.last, c.zones)
            }
        }

        private fun reason(c: ChildLocation, cloudOn: Boolean): NoPlaceReason = when {
            !cloudOn -> NoPlaceReason.CLOUD_OFF
            c.sharing != Sharing.On -> NoPlaceReason.NOT_SHARING
            SetupWarning.LOCATION_PERMISSION_OFF in c.warnings -> NoPlaceReason.PERMISSION_OFF
            SetupWarning.LOCATION_DISABLED in c.warnings -> NoPlaceReason.LOCATION_DISABLED
            else -> NoPlaceReason.WAITING
        }

        private fun nearest(point: GeoPoint?, zones: List<ZoneView>): Outside {
            if (point == null || zones.isEmpty()) return Outside(null, null)
            val (zone, edge) = zones
                .map { z -> z to (Geo.distanceM(z.latE7, z.lonE7, point.latE7, point.lonE7) - z.radiusM).coerceAtLeast(0.0) }
                .minBy { it.second }
            return Outside(zone.name, roundDistance(edge))
        }

        /** 10 m steps under a kilometre, 100 m above: the map is no ruler. */
        internal fun roundDistance(m: Double): Int = if (m < 1_000) (m / 10).roundToInt() * 10 else (m / 100).roundToInt() * 100
    }
}
