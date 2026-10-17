package app.kinsight.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapPlannerTest {
    private val day = 24 * 3_600_000L
    private val now = 1_792_200_000_000L // 2026-10-17, six days after the build below
    private val valencia = Cell(510, 389)
    private val build = "20261011_080001_pt"
    private val template = "https://tiles.openfreemap.org/planet/$build/{z}/{x}/{y}.pbf"
    private val here = MapPoint(395_000_000, -5_000_000, now)

    @Test
    fun aPointMarksItsCellWithItsTime() =
        assertEquals(now - 5, MapPlanner.touch(emptyMap(), listOf(MapPoint(395_000_000, -5_000_000, now - 5)), now)[valencia])

    @Test
    fun aMarkNeverGoesBackInTime() =
        assertEquals(now, MapPlanner.touch(mapOf(valencia to now), listOf(MapPoint(395_000_000, -5_000_000, now - 99)), now)[valencia])

    private fun around(c: Cell) = (-1..1).flatMap { dx -> (-1..1).map { dy -> Cell(c.x + dx, c.y + dy) } }.toSet()

    @Test
    fun aPointInACellsCentreWantsTheNineCellsAroundIt() {
        val centre = MapPoint(395_040_000, -5_273_000, now)
        assertEquals(around(valencia), MapPlanner.wanted(MapPlanner.touch(emptyMap(), listOf(centre), now), now))
    }

    @Test
    fun aPointAtAnEdgeWantsExactlyTheSameNine() {
        val nearEast = MapPoint(394_699_000, -3_763_000, now)
        val nearCorner = MapPoint(396_300_000, -3_600_000, now)
        assertEquals(around(valencia), MapPlanner.wanted(MapPlanner.touch(emptyMap(), listOf(nearEast), now), now))
        assertEquals(around(valencia), MapPlanner.wanted(MapPlanner.touch(emptyMap(), listOf(nearCorner), now), now))
    }

    @Test
    fun evictionKeepsTheBlocksWithinOneCellOfARecentPoint() {
        val usage = MapPlanner.touch(emptyMap(), listOf(MapPoint(395_000_000, -5_000_000, now - 2 * day)), now)
        val present = setOf(valencia, Cell(511, 390), Cell(509, 388), Cell(512, 389), Cell(510, 391))
        assertEquals(setOf(Cell(512, 389), Cell(510, 391)), MapPlanner.evictions(present, usage, emptySet(), now))
    }

    @Test
    fun theNeighbourhoodOfTheChildsPointIsNeverEvicted() {
        val there = MapPoint(395_000_000, -5_000_000, now - 30 * day)
        assertEquals(around(valencia), MapPlanner.current(listOf(there)))
        assertEquals(setOf(Cell(512, 389)), MapPlanner.evictions(around(valencia) + Cell(512, 389), emptyMap(), MapPlanner.current(listOf(there)), now))
    }

    @Test
    fun aWeekOldMarkIsDropped() = assertEquals(emptyMap(), MapPlanner.touch(mapOf(valencia to now - MapPlanner.KEEP_MS), emptyList(), now))

    private fun ledger(
        points: List<MapPoint> = listOf(here),
        installed: Map<Cell, String> = emptyMap(),
        capped: Map<Cell, Long> = emptyMap(),
        source: String? = template,
        fetchedMs: Long? = now,
    ) = BlockLedger(MapPlanner.touch(emptyMap(), points, now), installed, MapPlanner.touchCentres(emptyMap(), points, now), capped, source, fetchedMs)

    private fun fetches(l: BlockLedger, present: Set<Cell> = l.installed.keys) = MapPlanner.downloads(MapPlanner.wanted(l.usage, now), l, present, now)

    @Test
    fun aPointMarksItsOwnCellAsTheCentre() =
        assertEquals(mapOf(valencia to now), MapPlanner.touchCentres(emptyMap(), listOf(here, here.copy(timeMs = now - 9)), now))

    /** Spec §7: the centre to z14 first, the eight neighbours to z13, 1 021 tiles. */
    @Test
    fun theCentreComesToZ14FirstAndTheNeighboursToZ13() {
        val f = fetches(ledger())
        assertEquals(BlockFetch(valencia, 14), f.first())
        assertEquals(around(valencia) - valencia, f.drop(1).map { it.cell }.toSet())
        assertTrue(f.drop(1).all { it.maxZoom == 13 })
        assertEquals(1_021, f.sumOf { TileGrid.tileCount(it.maxZoom) })
    }

    @Test
    fun filesOfTheRightDepthAreNotFetchedAgain() {
        val installed = around(valencia).associateWith { "$build/${if (it == valencia) 14 else 13}" }
        assertEquals(emptyList(), fetches(ledger(installed = installed)))
    }

    /** Review Focus 3: a z13 file where the child now is comes again at z14; a z14 neighbour stays. */
    @Test
    fun aShallowCentreComesAgainDeeperAndADeepNeighbourStays() {
        val installed = around(valencia).associateWith { "$build/${if (it == valencia) 13 else 14}" }
        assertEquals(listOf(BlockFetch(valencia, 14)), fetches(ledger(installed = installed)))
    }

    @Test
    fun aFileTheLedgerDoesNotDescribeIsReplaced() {
        val deep = around(valencia).associateWith { "$build/14" }
        assertEquals(listOf(BlockFetch(valencia, 14)), fetches(ledger(installed = deep + (valencia to "ab".repeat(32)))))
        assertEquals(listOf(BlockFetch(valencia, 14)), fetches(ledger(installed = deep - valencia), present = around(valencia)))
    }

    @Test
    fun aCappedCellWaitsAWeek() {
        val installed = (around(valencia) - valencia).associateWith { "$build/13" }
        assertEquals(emptyList(), fetches(ledger(installed = installed, capped = mapOf(valencia to now - 6 * day))))
        assertEquals(listOf(BlockFetch(valencia, 14)), fetches(ledger(installed = installed, capped = mapOf(valencia to now - 7 * day))))
    }

    /** Spec resolution 3: 90 days old and a newer build out, or a day-old TileJSON that may name one. */
    @Test
    fun anOldBuildIsRenewedOnlyWhenANewerOneMayBeOut() {
        val old = "20260601_080001_pt"
        val installed = around(valencia).associateWith { "$old/${if (it == valencia) 14 else 13}" }
        assertEquals(9, fetches(ledger(installed = installed)).size, "the TileJSON names a newer build")
        assertEquals(emptyList(), fetches(ledger(installed = installed, source = template.replace(build, old))), "the same build: kept")
        assertEquals(9, fetches(ledger(installed = installed, source = template.replace(build, old), fetchedMs = now - day)).size, "a day old: ask")
        assertEquals(emptyList(), fetches(ledger(installed = around(valencia).associateWith { "local/14" })), "a debug block never ages")
    }

    /** Review Focus 4: three neighbourhoods are 3 063 tiles; one run takes 2 978, centres first. */
    @Test
    fun oneRunStaysWithinThreeThousandTiles() {
        val madrid = MapPoint(404_168_000, -37_038_000, now)
        val sydney = MapPoint(-338_688_000, 1_512_093_000, now)
        val all = fetches(ledger(points = listOf(here, madrid, sydney)))
        assertEquals(3 * 1_021, all.sumOf { TileGrid.tileCount(it.maxZoom) })
        val run = MapPlanner.batch(all)
        assertEquals(2_978, run.sumOf { TileGrid.tileCount(it.maxZoom) })
        assertEquals(3, run.count { it.maxZoom == 14 })
    }

    @Test
    fun aRunNeedsRoomForAWholeDownloadPlusAHundredMegabytes() {
        assertTrue(MapPlanner.hasRoom(MapPlanner.MAX_BYTES + (100L shl 20)))
        assertFalse(MapPlanner.hasRoom(MapPlanner.MAX_BYTES + (100L shl 20) - 1))
    }

    @Test
    fun aClockThatWentBackAsksForTheTileJsonAgain() {
        assertTrue(MapPlanner.sourceDue(null, now))
        assertFalse(MapPlanner.sourceDue(now - 1, now))
        assertTrue(MapPlanner.sourceDue(now - MapPlanner.SOURCE_EVERY_MS, now))
        assertTrue(MapPlanner.sourceDue(now + 60_000, now))
    }

    @Test
    fun aFetchIsNeverLogged() = assertEquals("BlockFetch", BlockFetch(valencia, 14).toString())
}
