package app.kinsight.core.designsystem

import androidx.compose.ui.graphics.Color
import app.kinsight.core.designsystem.component.CATEGORY_COLOR_COUNT
import app.kinsight.core.designsystem.component.categoryPalette
import app.kinsight.core.designsystem.component.contrastRatio
import app.kinsight.core.designsystem.component.heatCells
import app.kinsight.core.designsystem.component.heatIntensity
import app.kinsight.core.designsystem.component.labelShown
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
        assertEquals(0.5f, heatIntensity(50L, 100L))
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
    fun labelsAreCountedFromTheAnchor() {
        assertTrue(labelShown(i = 6, anchor = 6, step = 3))
        assertTrue(labelShown(i = 3, anchor = 6, step = 3))
        assertFalse(labelShown(i = 5, anchor = 6, step = 3))
        // Bars after the anchor (future days in a week) follow the same rhythm.
        assertTrue(labelShown(i = 9, anchor = 6, step = 3))
        assertFalse(labelShown(i = 7, anchor = 6, step = 3))
        assertTrue(labelShown(i = 4, anchor = 2, step = 1))
    }
}
