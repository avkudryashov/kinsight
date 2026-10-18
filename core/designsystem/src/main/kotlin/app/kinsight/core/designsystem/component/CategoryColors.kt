package app.kinsight.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Number of distinct category colours; [categoryColor] wraps around after it. */
internal const val CATEGORY_COLOR_COUNT = 9

// UI refresh §1: nine hues picked against the family palette. Light: at least 3:1 on white cards and on #F3F0F9
// (WCAG 1.4.11); dark: at least 3:1 on #221F2E and #17151F. The last one is the neutral for "Other".
private val LightCategoryColors = listOf(
    Color(0xFF6A4FD8), Color(0xFF2F6FDB), Color(0xFFD9485F), Color(0xFF2E8B57), Color(0xFFC76A00),
    Color(0xFFC2378A), Color(0xFF00838F), Color(0xFF8D6E63), Color(0xFF7A7890),
)
private val DarkCategoryColors = listOf(
    Color(0xFFA28BF5), Color(0xFF7FB0FF), Color(0xFFFF8A9B), Color(0xFF6FD39B), Color(0xFFFFB547),
    Color(0xFFF48FC6), Color(0xFF5FD3DE), Color(0xFFC9A99B), Color(0xFFABA8C0),
)

/** The fixed categorical palette for a light or dark theme, [CATEGORY_COLOR_COUNT] colours in a stable order. */
internal fun categoryPalette(dark: Boolean): List<Color> = if (dark) DarkCategoryColors else LightCategoryColors

/** [index] wrapped into the palette, negative indices included. */
internal fun paletteIndex(index: Int): Int = Math.floorMod(index, CATEGORY_COLOR_COUNT)

/** WCAG contrast ratio of two colours, 1.0 to 21.0. */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * Colour of the category at [index] (callers pass a category's ordinal). Dynamic colour gives only three accent hues,
 * too few and too close for nine categories, so the design system keeps its own tonal palette and picks the light or
 * dark set from the current theme's surface. Colour is never the only cue: pair it with [CategoryLegend] or text.
 */
@Composable
@ReadOnlyComposable
fun categoryColor(index: Int): Color =
    categoryPalette(dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f)[paletteIndex(index)]

/** Category names with their colour swatches; the name carries the meaning, the swatch is decorative. */
@Composable
fun CategoryLegend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (label, color) ->
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).background(color, CircleShape))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
