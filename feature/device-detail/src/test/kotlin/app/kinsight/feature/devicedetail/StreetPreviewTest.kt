package app.kinsight.feature.devicedetail

import app.kinsight.core.data.GeoPoint
import app.kinsight.core.maps.Cell
import app.kinsight.core.maps.StreetMaps
import kotlin.test.Test
import kotlin.test.assertEquals

/** Offline map spec §4: the preview with the block, else the plain map and what the street map waits for. */
class StreetPreviewTest {
    private val p = GeoPoint(404_120_000, -37_080_000, 15, 0L)
    private val madrid = Cell(501, 386)
    private val path = "/data/maps/z10-501-386.pmtiles"

    private fun streets(blocks: Map<Cell, String> = emptyMap(), skipped: Set<Cell> = emptySet(), downloading: Boolean = false) =
        StreetMaps(blocks, skipped, downloading)

    @Test
    fun noPointNoStreets() = assertEquals(StreetPreview.None, StreetPreview.of(null, streets(), cloudOn = true))

    @Test
    fun theBlockOfThePointShowsTheStreetsAndOnlyBlocksNearby() = assertEquals(
        StreetPreview.Ready(mapOf(madrid to path)),
        StreetPreview.of(p, streets(mapOf(madrid to path, Cell(400, 300) to "/far")), cloudOn = true),
    )

    @Test
    fun aRunningDownloadSaysLoading() = assertEquals(StreetPreview.Loading, StreetPreview.of(p, streets(downloading = true), cloudOn = true))

    @Test
    fun otherwiseItWaitsForTheNetwork() = assertEquals(StreetPreview.Waiting, StreetPreview.of(p, streets(), cloudOn = true))

    /** No room on the phone, or the cell passed the size cap: nothing will come for now, so no caption. */
    @Test
    fun aSkippedCellHasNoCaption() =
        assertEquals(StreetPreview.None, StreetPreview.of(p, streets(skipped = setOf(madrid)), cloudOn = true))

    @Test
    fun withTheCloudOffNothingWillCome() = assertEquals(StreetPreview.None, StreetPreview.of(p, streets(), cloudOn = false))

    @Test
    fun aBlockOnThePhoneShowsEvenWithTheCloudOff() =
        assertEquals(StreetPreview.Ready(mapOf(madrid to path)), StreetPreview.of(p, streets(mapOf(madrid to path)), cloudOn = false))
}
