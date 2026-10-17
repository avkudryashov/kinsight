package app.kinsight.core.designsystem

import androidx.compose.ui.graphics.Color
import app.kinsight.core.designsystem.component.contrastRatio
import app.kinsight.core.designsystem.theme.ChildCardGradient
import app.kinsight.core.designsystem.theme.ChildPalette
import app.kinsight.core.designsystem.theme.DarkColors
import app.kinsight.core.designsystem.theme.LightColors
import app.kinsight.core.designsystem.theme.OnChildCard
import app.kinsight.core.designsystem.theme.childColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 12: one stable colour per child, the same on every phone, white text readable on all of it. */
class ChildColorsTest {
    private fun hex(i: Int) = "%032x".format(i * 2_654_435_761L)

    @Test
    fun thePaletteHasTheFourGivenColoursVioletFirst() {
        assertEquals(
            listOf(Color(0xFF6A4FD8), Color(0xFF0F7C78), Color(0xFFC2453D), Color(0xFF2D5DB8)),
            ChildPalette.map { it.base },
        )
        assertEquals(ChildCardGradient, ChildPalette.first().gradient, "the violet card stays as it was")
    }

    @Test
    fun theSameChildAlwaysGetsTheSameColour() {
        val id = "cc".repeat(16)
        val first = childColor(id)
        repeat(5) { assertEquals(first, childColor(id)) }
        assertEquals(first, childColor(id.uppercase()), "the hex case does not matter")
        // Pinned values: a change of the hash would recolour every child on every phone.
        assertEquals(ChildPalette[Math.floorMod(id.hashCode(), ChildPalette.size)], first)
    }

    @Test
    fun childrenSpreadOverTheWholePalette() {
        val used = (1..64).map { childColor(hex(it)) }.toSet()
        assertEquals(ChildPalette.toSet(), used)
    }

    @Test
    fun whiteTextReadsOnEveryCardColour() {
        for (c in ChildPalette) {
            for (tone in c.gradient) assertTrue(contrastRatio(OnChildCard, tone) >= 4.5, "white on $tone: ${contrastRatio(OnChildCard, tone)}")
        }
    }

    @Test
    fun theEndOfEachGradientIsLighterThanItsStart() {
        for (c in ChildPalette) assertTrue(contrastRatio(Color.White, c.light) < contrastRatio(Color.White, c.base), "${c.base}")
    }

    @Test
    fun aChildsMarkStandsOutOnTheMapInBothThemes() {
        for (c in ChildPalette) {
            assertTrue(contrastRatio(c.base, LightColors.surfaceContainerHighest) >= 3.0, "light ${c.base}")
            assertTrue(contrastRatio(c.onDark, DarkColors.surfaceContainerHighest) >= 3.0, "dark ${c.onDark}")
            assertTrue(contrastRatio(c.onDark, DarkColors.surface) >= 3.0, "dark background ${c.onDark}")
        }
    }
}
