package app.kinsight.core.designsystem.preview

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.kinsight.core.designsystem.theme.KinsightTheme

/** Light and dark (U16): put it on every screen preview. */
@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
annotation class ThemePreviews

/** The fallback palette (dynamic colour needs a wallpaper), on a surface. */
@Composable
fun PreviewTheme(content: @Composable () -> Unit) = KinsightTheme(dynamicColor = false) { Surface { content() } }
