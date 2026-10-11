package app.kinsight.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import app.kinsight.core.designsystem.component.ChartScale
import app.kinsight.core.designsystem.component.HEAT_LEGEND
import app.kinsight.core.designsystem.component.HEAT_MIN_INTENSITY
import app.kinsight.core.designsystem.component.barLayout
import app.kinsight.core.designsystem.component.fitTicks
import app.kinsight.core.designsystem.component.gridTicks
import app.kinsight.core.designsystem.component.slotRange
import app.kinsight.core.designsystem.component.limitChipOffset
import app.kinsight.core.designsystem.component.tickLabelTop
import app.kinsight.core.designsystem.component.CATEGORY_COLOR_COUNT
import app.kinsight.core.designsystem.component.barAlpha
import app.kinsight.core.designsystem.component.barMarked
import app.kinsight.core.designsystem.component.categoryPalette
import app.kinsight.core.designsystem.component.contrastRatio
import app.kinsight.core.designsystem.component.heatCells
import app.kinsight.core.designsystem.component.heatHourLabels
import app.kinsight.core.designsystem.component.heatIntensity
import app.kinsight.core.designsystem.component.labelShown
import app.kinsight.core.designsystem.component.limitFraction
import app.kinsight.core.designsystem.component.limitSummary
import app.kinsight.core.designsystem.component.scaleMax
import app.kinsight.core.designsystem.component.labelSpacing
import app.kinsight.core.designsystem.component.labelStep
import app.kinsight.core.designsystem.component.spacedStep
import app.kinsight.core.designsystem.component.paletteIndex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChartMathTest {
    private val lightSurface = Color(0xFFF4FBF8)
    private val darkSurface = Color(0xFF0E1513)

    @Test
    fun categoryPaletteIsStableAndDistinct() {
        for (dark in listOf(false, true)) {
            val p = categoryPalette(dark)
            assertEquals(CATEGORY_COLOR_COUNT, p.size)
            assertEquals(p.size, p.toSet().size, "distinct, dark=$dark")
            assertEquals(p, categoryPalette(dark), "same list on every call")
        }
        assertEquals(Color(0xFF6750A4), categoryPalette(dark = false).first())
        assertEquals(Color(0xFFD0BCFF), categoryPalette(dark = true).first())
        assertNotEquals(categoryPalette(false), categoryPalette(true))
    }

    @Test
    fun categoryColoursKeepNonTextContrastOnTheSurface() {
        // WCAG 2.2 SC 1.4.11: graphical objects need 3:1 against adjacent colours.
        categoryPalette(dark = false).forEach { assertTrue(contrastRatio(it, lightSurface) >= 3.0, "light $it") }
        categoryPalette(dark = true).forEach { assertTrue(contrastRatio(it, darkSurface) >= 3.0, "dark $it") }
    }

    @Test
    fun contrastRatioMatchesWcag() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.Red, Color.Red), 0.0001)
        assertEquals(contrastRatio(Color.White, Color.Gray), contrastRatio(Color.Gray, Color.White), 0.0001)
    }

    @Test
    fun paletteIndexWrapsAround() {
        assertEquals(0, paletteIndex(0))
        assertEquals(CATEGORY_COLOR_COUNT - 1, paletteIndex(CATEGORY_COLOR_COUNT - 1))
        assertEquals(1, paletteIndex(CATEGORY_COLOR_COUNT + 1))
        assertEquals(CATEGORY_COLOR_COUNT - 1, paletteIndex(-1))
    }

    @Test
    fun heatIntensityMapsValueToZeroOne() {
        assertEquals(0f, heatIntensity(0L, 100L))
        assertEquals(0f, heatIntensity(-5L, 100L))
        assertEquals(0f, heatIntensity(50L, 0L))
        assertEquals(0.575f, heatIntensity(50L, 100L), 0.0001f)
        assertEquals(1f, heatIntensity(100L, 100L))
        assertEquals(1f, heatIntensity(250L, 100L))
    }

    @Test
    fun heatCellsAreSevenByTwentyFourWithoutNegatives() {
        val ragged = arrayOf(longArrayOf(1L, -2L, 3L), LongArray(30) { 7L })
        val c = heatCells(ragged)
        assertEquals(7, c.size)
        c.forEach { assertEquals(24, it.size) }
        assertContentEquals(longArrayOf(1L, 0L, 3L) + LongArray(21), c[0])
        assertContentEquals(LongArray(24) { 7L }, c[1])
        assertContentEquals(LongArray(24), c[6])
        assertEquals(7, heatCells(emptyArray()).size)
    }

    @Test
    fun aHighlightedBarDoesNotDimTheOthers() {
        // Phones, 0.5.0: today without data was highlighted and every other bar of the week looked dimmed.
        for (marked in listOf(null, 0, 6)) {
            for (i in 0..6) assertEquals(1f, barAlpha(i, marked), "bar $i, marked $marked")
        }
    }

    @Test
    fun onlyTheHighlightedBarGetsTheMark() {
        assertTrue(barMarked(6, marked = 6))
        assertFalse(barMarked(5, marked = 6))
        assertFalse(barMarked(0, marked = null))
    }

    @Test
    fun labelsAreCountedFromTheAnchor() {
        assertTrue(labelShown(i = 6, anchor = 6, step = 3))
        assertTrue(labelShown(i = 3, anchor = 6, step = 3))
        assertFalse(labelShown(i = 5, anchor = 6, step = 3))
        // Bars after the anchor (future days in a week) follow the same rhythm.
        assertTrue(labelShown(i = 9, anchor = 6, step = 3))
        assertFalse(labelShown(i = 7, anchor = 6, step = 3))
        assertTrue(labelShown(i = 4, anchor = 2, step = 1))
    }

    @Test
    fun heatmapHourAxisTakesTheCallersLabels() {
        assertEquals(listOf("0", "6", "12", "18"), heatHourLabels(null))
        assertEquals(listOf("12 AM", "6 AM", "12 PM", "6 PM"), heatHourLabels(listOf("12 AM", "6 AM", "12 PM", "6 PM")))
        // Too few or too many: padded with blanks or trimmed to the four ticks.
        assertEquals(listOf("a", "", "", ""), heatHourLabels(listOf("a")))
        assertEquals(listOf("a", "b", "c", "d"), heatHourLabels(listOf("a", "b", "c", "d", "e")))
    }

    @Test
    fun hourAxisKeepsItsSixHourTicksAtNarrowWidth() {
        // 24 bars on a 360 dp phone: ~13 dp slots, "12 AM" ~34 dp wide needs every 3rd slot.
        val names = (0..23).map { if (it % 6 == 0) "12 AM" else "" }
        assertEquals(6, labelSpacing(names))
        val raw = labelStep(widestPx = 34f, slotPx = 13f)
        assertEquals(3, raw)
        // Counted from the last bar (the old anchor), not one tick survives.
        assertFalse(listOf(0, 6, 12, 18).any { labelShown(it, anchor = 23, step = raw) })
        // Counted from hour 0 with the step rounded up to the labels' own spacing, all four stay.
        for (r in 1..6) {
            val step = spacedStep(r, labelSpacing(names))
            assertEquals(6, step)
            assertTrue(listOf(0, 6, 12, 18).all { labelShown(it, anchor = 0, step = step) }, "raw step $r")
        }
        // A month labelled every 5th day from day 1.
        val month = (0 until 31).map { if (it % 5 == 0) "${it + 1}" else "" }
        assertEquals(5, labelSpacing(month))
        assertEquals(5, spacedStep(2, 5))
        assertEquals(10, spacedStep(7, 5))
        // Every bar labelled (a week): unchanged.
        assertEquals(1, labelSpacing(listOf("M", "T", "W")))
        assertEquals(1, labelSpacing(listOf("M")))
        assertEquals(3, spacedStep(3, 1))
    }

    @Test
    fun aLimitRaisesTheScaleSoItsLineIsAlwaysOnTheChart() {
        assertEquals(100L, scaleMax(100L, null))
        assertEquals(100L, scaleMax(100L, 60L))
        assertEquals(180L, scaleMax(100L, 180L), "a limit above every bar sets the top")
        assertEquals(1L, scaleMax(0L, null))
        assertEquals(1L, scaleMax(0L, 0L))
        assertEquals(50L, scaleMax(0L, 50L))
    }

    @Test
    fun limitLineSitsAtItsShareOfTheScale() {
        assertEquals(null, limitFraction(null, 100L))
        assertEquals(null, limitFraction(0L, 100L))
        assertEquals(null, limitFraction(-5L, 100L))
        assertEquals(0.5f, limitFraction(50L, 100L))
        assertEquals(1f, limitFraction(100L, 100L))
        assertEquals(1f, limitFraction(150L, 100L), "never above the chart")
    }

    @Test
    fun theSummaryNamesTheLimit() {
        assertEquals("Average 3 h", limitSummary("Average 3 h", null))
        assertEquals("Average 3 h. Limit 2 h", limitSummary("Average 3 h", "Limit 2 h"))
        assertEquals("Average 3 h", limitSummary("Average 3 h", ""))
    }

    @Test
    fun durationTicksAreTwoOrThreeNiceSteps() {
        val m = 60_000L
        assertEquals(listOf(15L, 30, 45).map { it * m }, gridTicks(50 * m, ChartScale.DURATION))
        assertEquals(listOf(60L, 120, 180).map { it * m }, gridTicks(190 * m, ChartScale.DURATION))
        assertEquals(listOf(60L, 120).map { it * m }, gridTicks(120 * m, ChartScale.DURATION))
        assertEquals(listOf(240L, 480, 720).map { it * m }, gridTicks(720 * m, ChartScale.DURATION))
        assertEquals(listOf(2L, 4, 6).map { it * m }, gridTicks(7 * m, ChartScale.DURATION))
        assertEquals(listOf(1L, 2, 3).map { it * m }, gridTicks(3 * m, ChartScale.DURATION))
        assertEquals(listOf(m), gridTicks(m, ChartScale.DURATION), "one minute: one line rather than none")
        assertEquals(emptyList(), gridTicks(30_000L, ChartScale.DURATION))
        assertEquals(emptyList(), gridTicks(0, ChartScale.DURATION))
    }

    @Test
    fun durationTicksAtTheEdgesOfAnHourAndADay() {
        val m = 60_000L
        assertEquals(listOf(15L, 30, 45).map { it * m }, gridTicks(59 * m, ChartScale.DURATION))
        assertEquals(listOf(30L, 60).map { it * m }, gridTicks(60 * m, ChartScale.DURATION))
        assertEquals(listOf(6L, 12, 18).map { it * 60 * m }, gridTicks(23 * 60 * m + 59 * m, ChartScale.DURATION))
        assertEquals(listOf(8L, 16, 24).map { it * 60 * m }, gridTicks(24 * 60 * m, ChartScale.DURATION))
    }

    @Test
    fun everyMinuteMaxHasOneToThreeTicksAndNoHalfHourAboveAnHour() {
        val m = 60_000L
        (1L..24 * 60).forEach { min ->
            val ticks = gridTicks(min * m, ChartScale.DURATION)
            assertTrue(ticks.size in 1..3, "$min min: $ticks")
            assertTrue(ticks.all { it <= 60 * m || it % (60 * m) == 0L }, "$min min: $ticks")
        }
        assertEquals(listOf(60 * m), gridTicks(90 * m, ChartScale.DURATION), "90 min: one line at 1 h, not «1 h 30 min»")
        assertEquals(listOf(60 * m), gridTicks(119 * m, ChartScale.DURATION))
    }

    @Test
    fun ticksThinOutWhenTheirLabelsWouldTouch() {
        val m = 60_000L
        val ticks = listOf(15L, 30, 45).map { it * m }
        assertEquals(ticks, fitTicks(ticks, 50 * m, heightPx = 240f, labelHeightPx = 20f))
        assertEquals(listOf(30 * m), fitTicks(ticks, 50 * m, heightPx = 240f, labelHeightPx = 64f), "200 % font: every second")
        assertEquals(listOf(4L), fitTicks(listOf(2L, 4, 6), 7, heightPx = 120f, labelHeightPx = 32f), "a 120 dp chart at 200 % font")
        assertEquals(emptyList(), fitTicks(emptyList(), 1, 100f, 20f))
    }

    @Test
    fun countTicksAreTwoOrThreeNiceSteps() {
        assertEquals(listOf(2L, 4), gridTicks(4, ChartScale.COUNT))
        assertEquals(listOf(2L, 4, 6), gridTicks(7, ChartScale.COUNT))
        assertEquals(listOf(1L, 2, 3), gridTicks(3, ChartScale.COUNT))
        assertEquals(listOf(1L), gridTicks(1, ChartScale.COUNT))
        assertEquals(emptyList(), gridTicks(0, ChartScale.COUNT))
    }

    @Test
    fun barsLeaveTheGutterToTheScaleLabels() {
        val l = barLayout(widthPx = 300f, gutterPx = 40f, count = 7)
        assertEquals(260f, l.barsWidth)
        assertTrue(l.left(6) + l.bar <= l.barsWidth, "the last bar ends before the gutter")
        assertTrue(l.left(0) >= 0f)
        assertEquals(300f, barLayout(300f, 0f, 7).barsWidth)
        assertEquals(0f, barLayout(30f, 40f, 0).barsWidth, "a gutter wider than the chart leaves no bars, never a negative width")
    }

    @Test
    fun tickLabelsAreCentredOnTheirLineAndStayInside() {
        assertEquals(40f, tickLabelTop(y = 50f, labelHeight = 20f, height = 100f))
        assertEquals(0f, tickLabelTop(y = 5f, labelHeight = 20f, height = 100f))
        assertEquals(80f, tickLabelTop(y = 98f, labelHeight = 20f, height = 100f))
    }

    @Test
    fun theLimitChipSitsAtTheLeftAboveItsLine() {
        assertEquals(Offset(2f, 78f), limitChipOffset(y = 100f, chipWidth = 50f, chipHeight = 20f, gap = 2f, barsWidth = 300f))
        assertEquals(Offset(2f, 12f), limitChipOffset(y = 10f, chipWidth = 50f, chipHeight = 20f, gap = 2f, barsWidth = 300f), "no room above: below the line")
    }

    @Test
    fun theLimitChipNeverCoversTodaysBar() {
        val l = barLayout(widthPx = 300f, gutterPx = 0f, count = 7)
        fun chip(today: Int) = limitChipOffset(y = 100f, chipWidth = 50f, chipHeight = 20f, gap = 2f, barsWidth = l.barsWidth, avoid = l.slotRange(today))
        assertEquals(Offset(248f, 78f), chip(0), "today first: the chip goes to the right end")
        assertEquals(Offset(248f, 78f), chip(1), "today second: the left chip would reach into its slot")
        assertEquals(Offset(2f, 78f), chip(6), "today last: the left edge")
        assertEquals(Offset(2f, 78f), chip(3), "today in the middle: the left edge")
        val one = barLayout(widthPx = 60f, gutterPx = 0f, count = 1)
        assertEquals(Offset(2f, 102f), limitChipOffset(100f, 50f, 20f, 2f, one.barsWidth, one.slotRange(0)), "both ends on today: below the line")
        assertEquals(null, l.slotRange(null))
    }

    @Test
    fun anyTimeInAHeatCellIsVisible() {
        assertEquals(HEAT_MIN_INTENSITY, heatIntensity(1L, 1_000_000L), 0.001f)
        assertEquals(listOf(HEAT_MIN_INTENSITY, (HEAT_MIN_INTENSITY + 1f) / 2, 1f), HEAT_LEGEND)
    }
}
