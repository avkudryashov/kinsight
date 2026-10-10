package app.kinsight.core.designsystem

import app.kinsight.core.designsystem.component.chartLabels
import app.kinsight.core.designsystem.component.labelStep
import app.kinsight.core.designsystem.component.qrLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutMathTest {
    @Test
    fun labelsArePaddedOrTrimmedToTheBars() {
        assertEquals(listOf("M", "", ""), chartLabels(listOf("M"), 3))
        assertEquals(listOf("M", "T"), chartLabels(listOf("M", "T", "W"), 2))
        assertEquals(emptyList(), chartLabels(listOf("M"), 0))
    }

    @Test
    fun labelsThinOutWhenTheyDoNotFit() {
        assertEquals(1, labelStep(widestPx = 20f, slotPx = 30f))
        assertEquals(1, labelStep(widestPx = 30f, slotPx = 30f))
        assertEquals(2, labelStep(widestPx = 50f, slotPx = 30f))
        assertEquals(3, labelStep(widestPx = 61f, slotPx = 30f))
        assertEquals(1, labelStep(widestPx = 10f, slotPx = 0f))
    }

    @Test
    fun qrModulesSnapToWholePixelsWithAQuietZone() {
        for (side in listOf(100, 333, 512, 1080)) for (modules in listOf(21, 33, 57)) {
            val minQuiet = 63
            val l = qrLayout(side, modules, minQuiet)
            assertTrue(l.cellPx >= 1, "cell $l")
            val quiet = (side - modules * l.cellPx) / 2
            assertEquals(quiet, l.offsetPx, "centred $l")
            if (side >= modules + 2 * maxOf(minQuiet, 4)) {
                assertTrue(l.offsetPx >= maxOf(minQuiet, 4 * l.cellPx), "quiet zone $side/$modules $l")
            }
        }
    }
}
