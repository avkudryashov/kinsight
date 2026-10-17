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

    private fun streets(blocks: Map<Cell, String> = emptyMap(), catalog: Set<Cell>? = null, noRoom: Set<Cell> = emptySet(), downloading: Boolean = false) =
        StreetMaps(blocks, catalog, noRoom, downloading)

    @Test
    fun noPointNoStreets() = assertEquals(StreetPreview.None, StreetPreview.of(null, streets(), cloudOn = true))

    @Test
    fun theBlockOfThePointShowsTheStreetsAndOnlyBlocksNearby() = assertEquals(
        StreetPreview.Ready(mapOf(madrid to path)),
        StreetPreview.of(p, streets(mapOf(madrid to path, Cell(400, 300) to "/far"), setOf(madrid)), cloudOn = true),
    )

    @Test
    fun aRunningDownloadSaysLoading() = assertEquals(StreetPreview.Loading, StreetPreview.of(p, streets(downloading = true), cloudOn = true))

    @Test
    fun otherwiseItWaitsForWifi() = assertEquals(StreetPreview.WaitingWifi, StreetPreview.of(p, streets(catalog = setOf(madrid)), cloudOn = true))

    @Test
    fun aPlaceTheCatalogLacksHasNoCaption() =
        assertEquals(StreetPreview.None, StreetPreview.of(p, streets(catalog = setOf(Cell(510, 389))), cloudOn = true))

    @Test
    fun aFullPhoneHasNoCaption() =
        assertEquals(StreetPreview.None, StreetPreview.of(p, streets(catalog = setOf(madrid), noRoom = setOf(madrid)), cloudOn = true))

    @Test
    fun withTheCloudOffNothingWillCome() = assertEquals(StreetPreview.None, StreetPreview.of(p, streets(), cloudOn = false))

    @Test
    fun aBlockOnThePhoneShowsEvenWithTheCloudOff() =
        assertEquals(StreetPreview.Ready(mapOf(madrid to path)), StreetPreview.of(p, streets(mapOf(madrid to path)), cloudOn = false))
}
