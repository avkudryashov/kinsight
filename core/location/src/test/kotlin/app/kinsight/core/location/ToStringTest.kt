package app.kinsight.core.location

import app.kinsight.core.model.proto.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Coordinates, radius and accuracy never reach a log line, not even through a holder like Report.Point. */
class ToStringTest {
    private val zone = Zone("ab".repeat(16), 404_168_123, -37_038_456, 173)
    private val fix = Fix(404_168_123, -37_038_456, 47, 1_760_000_000_123L, Location.Provider.GPS)

    @Test
    fun zoneAndFixPrintNoNumberOfThePlace() {
        assertEquals("Zone(abababab)", zone.toString())
        assertEquals("Fix", fix.toString())
        val printed = listOf(zone.toString(), fix.toString(), Report.Point(fix).toString(), Step(TrackerState(), emptyList(), Report.Point(fix)).toString())
        for (s in printed) for (n in listOf("404168123", "37038456", "173", "47", "1760000000123")) assertFalse(n in s, "$n in $s")
    }
}
