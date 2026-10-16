package app.kinsight.feature.devicedetail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoordinatesTest {
    private val madrid = 404_168_000 to -37_038_000

    @Test
    fun parsesWhatMapAppsCopy() {
        assertEquals(madrid, Coordinates.parse("40.4168, -3.7038"))
        assertEquals(madrid, Coordinates.parse("  40.4168 -3.7038 "))
        assertEquals(madrid, Coordinates.parse("40.4168;-3.7038"))
    }

    @Test
    fun refusesAnythingElse() {
        assertNull(Coordinates.parse("40,4168 -3,7038"))
        assertNull(Coordinates.parse("91, 0"))
        assertNull(Coordinates.parse("0, 181"))
        assertNull(Coordinates.parse("home"))
        assertNull(Coordinates.parse("NaN, 1"))
    }

    @Test
    fun formatsFiveDecimals() = assertEquals("40.41680, -3.70380", Coordinates.format(404_168_000, -37_038_000))
}
