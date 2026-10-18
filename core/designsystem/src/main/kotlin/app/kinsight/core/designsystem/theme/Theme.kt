package app.kinsight.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable

/**
 * One theme for the whole app (UI refresh §1): the fixed family palette, light or dark by the system setting, Nunito,
 * rounded cards and expressive motion (Compose follows the system animation scale). Dynamic colour is gone on purpose:
 * the child card, the limit bar and the category colours are designed against these surfaces.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KinsightTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        motionScheme = MotionScheme.expressive(),
        shapes = KinsightShapes,
        typography = KinsightTypography,
        content = content,
    )
}
