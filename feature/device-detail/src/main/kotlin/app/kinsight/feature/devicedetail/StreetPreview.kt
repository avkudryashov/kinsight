package app.kinsight.feature.devicedetail

import app.kinsight.core.data.GeoPoint
import app.kinsight.core.maps.Cell
import app.kinsight.core.maps.MapPlanner
import app.kinsight.core.maps.StreetMaps
import app.kinsight.core.maps.TileGrid

/** What the Карта tab draws for the selected child (offline map spec §4). */
internal sealed interface StreetPreview {
    /** No street map for this place (no point, a skipped cell: a full phone or over the size cap, the cloud off): the plain map. */
    data object None : StreetPreview

    /** The block downloads now: the plain map with «Карта улиц загружается». */
    data object Loading : StreetPreview

    /** The block is wanted and waits (for Wi-Fi, or for its check): «Карта улиц будет загружена по Wi-Fi». */
    data object WaitingWifi : StreetPreview

    /** The point's block is on the phone; [blocks] the block files within 3 km of the point. */
    data class Ready(val blocks: Map<Cell, String>) : StreetPreview

    companion object {
        fun of(last: GeoPoint?, streets: StreetMaps, cloudOn: Boolean): StreetPreview {
            last ?: return None
            val own = TileGrid.cellOf(last.latE7, last.lonE7)
            if (own in streets.blocks) {
                val near = TileGrid.cellsNear(last.latE7, last.lonE7, MapPlanner.NEAR_M)
                return Ready(streets.blocks.filterKeys { it in near })
            }
            if (!cloudOn) return None
            if (own in streets.skipped) return None
            return if (streets.downloading) Loading else WaitingWifi
        }
    }
}
