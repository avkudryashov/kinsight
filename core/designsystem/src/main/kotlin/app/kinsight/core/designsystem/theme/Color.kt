package app.kinsight.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey

private val White = Color(0xFFFFFFFF)

/**
 * The family palette (UI refresh §1): fixed, never from the wallpaper. Screens on lavender, cards white, violet accent,
 * ink text. Every text colour keeps 4.5:1 on the surfaces it is used on (ThemePaletteTest); the spec's secondary text
 * #7D7AA0 is darkened to #625F86 for that.
 */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF6A4FD8), onPrimary = White,
    primaryContainer = Color(0xFFE9E3FF), onPrimaryContainer = Color(0xFF22125E),
    secondary = Color(0xFF8A6CF0), onSecondary = White,
    secondaryContainer = Color(0xFFECE8F6), onSecondaryContainer = Color(0xFF2F3256),
    tertiary = Color(0xFFA15C00), onTertiary = White,
    tertiaryContainer = Color(0xFFFFB547), onTertiaryContainer = Color(0xFF2F3256),
    error = Color(0xFFBA1A1A), onError = White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF3F0F9), onBackground = Color(0xFF2F3256),
    surface = Color(0xFFF3F0F9), onSurface = Color(0xFF2F3256),
    surfaceVariant = Color(0xFFECE8F6), onSurfaceVariant = Color(0xFF625F86),
    surfaceTint = Color(0xFF6A4FD8),
    outline = Color(0xFF8F8BB0), outlineVariant = Color(0xFFDCD7EC),
    surfaceBright = White, surfaceDim = Color(0xFFE4E0EE),
    surfaceContainerLowest = White, surfaceContainerLow = White, surfaceContainer = White,
    surfaceContainerHigh = White, surfaceContainerHighest = White,
    inverseSurface = Color(0xFF2F3256), inverseOnSurface = Color(0xFFF3F0F9), inversePrimary = Color(0xFFA992F6),
    scrim = Color.Black,
)

/** The same palette on a dark background (UI refresh §1): cards one step lighter, a lighter accent. */
internal val DarkColors = darkColorScheme(
    primary = Color(0xFFA28BF5), onPrimary = Color(0xFF1E1240),
    primaryContainer = Color(0xFF3B2F73), onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFF9D8CE8), onSecondary = Color(0xFF1E1240),
    secondaryContainer = Color(0xFF2E2A3D), onSecondaryContainer = Color(0xFFECE9F7),
    tertiary = Color(0xFFFFB547), onTertiary = Color(0xFF2F1C00),
    tertiaryContainer = Color(0xFFFFB547), onTertiaryContainer = Color(0xFF2F1C00),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF17151F), onBackground = Color(0xFFECE9F7),
    surface = Color(0xFF17151F), onSurface = Color(0xFFECE9F7),
    surfaceVariant = Color(0xFF2E2A3D), onSurfaceVariant = Color(0xFFA9A5C4),
    surfaceTint = Color(0xFFA28BF5),
    outline = Color(0xFF8C88A8), outlineVariant = Color(0xFF3A3550),
    surfaceBright = Color(0xFF2E2A3D), surfaceDim = Color(0xFF17151F),
    surfaceContainerLowest = Color(0xFF1C1A26), surfaceContainerLow = Color(0xFF221F2E), surfaceContainer = Color(0xFF221F2E),
    surfaceContainerHigh = Color(0xFF221F2E), surfaceContainerHighest = Color(0xFF221F2E),
    inverseSurface = Color(0xFFE9E6F5), inverseOnSurface = Color(0xFF17151F), inversePrimary = Color(0xFF6A4FD8),
    scrim = Color.Black,
)

/**
 * The child card's gradient (UI refresh §3), the same in both themes. The spec's end #8A6CF0 gives white text 3.83:1;
 * #7B5EE6 is the nearest tone with 4.5:1.
 */
val ChildCardGradient: List<Color> = listOf(Color(0xFF6A4FD8), Color(0xFF7B5EE6))

/**
 * One child's colour (Task 12): [base] starts the card's gradient and [light], a slightly lighter tone of it, ends it;
 * white text keeps 4.5:1 on both. [onDark] marks the child on the dark theme's map, where [base] is too dim.
 */
data class ChildColor(val base: Color, val light: Color, val onDark: Color) {
    val gradient: List<Color> get() = listOf(base, light)
}

/** A child's mark carries its colour in the semantics, so a test can tell which colour a card or a status got. */
val ChildColorKey: SemanticsPropertyKey<ChildColor> = SemanticsPropertyKey("ChildColor")

/** The children's colours: violet (the card as it was), teal, coral, blue (ChildColorsTest pins the contrasts). */
val ChildPalette: List<ChildColor> = listOf(
    ChildColor(Color(0xFF6A4FD8), Color(0xFF7B5EE6), Color(0xFF9E8DE6)),
    ChildColor(Color(0xFF0F7C78), Color(0xFF1B837F), Color(0xFF63AAA7)),
    ChildColor(Color(0xFFC2453D), Color(0xFFC54E47), Color(0xFFD78681)),
    ChildColor(Color(0xFF2D5DB8), Color(0xFF4A74C2), Color(0xFF7696D1)),
)

/**
 * The palette index of the child [childHex] among the group's children [allChildHexes]: its position in the
 * ascending, case-insensitive, de-duplicated hex list, modulo the palette size. Every phone of the family sees the same
 * children, so it computes the same index, and up to four children never share a colour. A child that is not in the
 * list (a child's own phone knows no siblings) falls back to the hash of its hex (String.hashCode is fixed by the Java
 * spec, so that is stable too).
 */
fun childColorIndex(childHex: String, allChildHexes: Collection<String>): Int {
    val me = childHex.lowercase()
    val position = allChildHexes.map { it.lowercase() }.distinct().sorted().indexOf(me)
    return Math.floorMod(if (position >= 0) position else me.hashCode(), ChildPalette.size)
}

/** The stable colour of the child [childHex] within its group (see [childColorIndex]). */
fun childColor(childHex: String, allChildHexes: Collection<String>): ChildColor = ChildPalette[childColorIndex(childHex, allChildHexes)]

/** Text and icons on [ChildCardGradient]. */
val OnChildCard: Color = White

/** The day limit's bar (UI refresh §1). Never the only cue: the bar always has its text. */
val LimitBarColor: Color = Color(0xFFFFB547)
