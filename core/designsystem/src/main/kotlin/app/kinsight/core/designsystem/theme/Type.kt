package app.kinsight.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.kinsight.core.designsystem.R

/** Nunito (OFL, Cyrillic), bundled: 400, 700 and 800 (UI refresh §1); no download, no network. */
val Nunito = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
)

private val base = Typography()

private fun TextStyle.nunito(weight: FontWeight) = copy(fontFamily = Nunito, fontWeight = weight)

/** Body text regular, titles and labels bold, the big figures extra bold (30 to 38 sp). */
internal val KinsightTypography = Typography(
    displayLarge = base.displayLarge.nunito(FontWeight.ExtraBold),
    displayMedium = base.displayMedium.nunito(FontWeight.ExtraBold),
    displaySmall = base.displaySmall.nunito(FontWeight.ExtraBold).copy(fontSize = 34.sp, lineHeight = 40.sp),
    headlineLarge = base.headlineLarge.nunito(FontWeight.Bold),
    headlineMedium = base.headlineMedium.nunito(FontWeight.Bold),
    headlineSmall = base.headlineSmall.nunito(FontWeight.Bold),
    titleLarge = base.titleLarge.nunito(FontWeight.Bold),
    titleMedium = base.titleMedium.nunito(FontWeight.Bold),
    titleSmall = base.titleSmall.nunito(FontWeight.Bold),
    bodyLarge = base.bodyLarge.nunito(FontWeight.Normal),
    bodyMedium = base.bodyMedium.nunito(FontWeight.Normal),
    bodySmall = base.bodySmall.nunito(FontWeight.Normal),
    labelLarge = base.labelLarge.nunito(FontWeight.Bold),
    labelMedium = base.labelMedium.nunito(FontWeight.Bold),
    labelSmall = base.labelSmall.nunito(FontWeight.Bold),
)
