package app.kinsight.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import app.kinsight.core.designsystem.component.contrastRatio
import app.kinsight.core.designsystem.theme.ChildCardGradient
import app.kinsight.core.designsystem.theme.DarkColors
import app.kinsight.core.designsystem.theme.LightColors
import app.kinsight.core.designsystem.theme.LimitBarColor
import app.kinsight.core.designsystem.theme.OnChildCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UI refresh §1: the fixed family palette, its spec values and 4.5:1 text contrast in both themes. */
class ThemePaletteTest {
    private val white = Color(0xFFFFFFFF)

    @Test
    fun theLightPaletteHasTheSpecValues() {
        assertEquals(Color(0xFFF3F0F9), LightColors.background)
        assertEquals(Color(0xFFF3F0F9), LightColors.surface)
        assertEquals(white, LightColors.surfaceContainerHighest, "cards are white")
        assertEquals(white, LightColors.surfaceContainerLow)
        assertEquals(white, LightColors.surfaceContainer)
        assertEquals(Color(0xFF6A4FD8), LightColors.primary)
        assertEquals(Color(0xFF2F3256), LightColors.onSurface)
        assertEquals(Color(0xFF625F86), LightColors.onSurfaceVariant, "#7D7AA0 darkened to pass 4.5:1")
        assertEquals(Color(0xFFFFB547), LimitBarColor)
    }

    @Test
    fun theDarkPaletteHasTheSpecValues() {
        assertEquals(Color(0xFF17151F), DarkColors.background)
        assertEquals(Color(0xFF17151F), DarkColors.surface)
        assertEquals(Color(0xFF221F2E), DarkColors.surfaceContainerHighest)
        assertEquals(Color(0xFFA28BF5), DarkColors.primary)
    }

    private fun ColorScheme.textPairs() = listOf(
        "onSurface/surface" to (onSurface to surface),
        "onSurface/card" to (onSurface to surfaceContainerHighest),
        "onSurfaceVariant/surface" to (onSurfaceVariant to surface),
        "onSurfaceVariant/card" to (onSurfaceVariant to surfaceContainerHighest),
        "onSurfaceVariant/surfaceVariant" to (onSurfaceVariant to surfaceVariant),
        "primary/surface" to (primary to surface),
        "primary/card" to (primary to surfaceContainerHighest),
        "onPrimary/primary" to (onPrimary to primary),
        "tertiary/surface" to (tertiary to surface),
        "tertiary/card" to (tertiary to surfaceContainerHighest),
        "error/surface" to (error to surface),
        "error/card" to (error to surfaceContainerHighest),
        "onPrimaryContainer/primaryContainer" to (onPrimaryContainer to primaryContainer),
        "onSecondaryContainer/secondaryContainer" to (onSecondaryContainer to secondaryContainer),
        "onTertiaryContainer/tertiaryContainer" to (onTertiaryContainer to tertiaryContainer),
        "onErrorContainer/errorContainer" to (onErrorContainer to errorContainer),
    )

    @Test
    fun textKeepsFourAndAHalfToOneInBothThemes() {
        for ((theme, scheme) in listOf("light" to LightColors, "dark" to DarkColors)) {
            for ((name, pair) in scheme.textPairs()) {
                val ratio = contrastRatio(pair.first, pair.second)
                assertTrue(ratio >= 4.5, "$theme $name: $ratio")
            }
        }
    }

    @Test
    fun chartBarsKeepThreeToOneOnCards() {
        for (scheme in listOf(LightColors, DarkColors)) {
            assertTrue(contrastRatio(scheme.secondary, scheme.surfaceContainerHighest) >= 3.0)
            assertTrue(contrastRatio(scheme.primary, scheme.surfaceContainerHighest) >= 3.0)
        }
    }

    @Test
    fun whiteTextReadsOnTheWholeChildCardGradient() {
        assertEquals(Color(0xFF6A4FD8), ChildCardGradient.first())
        assertEquals(Color(0xFF7B5EE6), ChildCardGradient.last(), "#8A6CF0 lightened white text to 3.83:1")
        ChildCardGradient.forEach { assertTrue(contrastRatio(OnChildCard, it) >= 4.5, "white on $it") }
    }
}
