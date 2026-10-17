package app.kinsight.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.designsystem.theme.Nunito
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** UI refresh §1: Nunito in three bundled weights on every style, rounded cards. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ThemeFontTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyStyleIsNunitoInTheThreeBundledWeights() {
        lateinit var t: Typography
        rule.setContent {
            KinsightTheme {
                t = MaterialTheme.typography
                Text("Обзор · Overview", fontWeight = FontWeight.ExtraBold)
            }
        }
        rule.onNodeWithText("Обзор · Overview").assertExists()
        val all = listOf(
            t.displayLarge, t.displayMedium, t.displaySmall, t.headlineLarge, t.headlineMedium, t.headlineSmall,
            t.titleLarge, t.titleMedium, t.titleSmall, t.bodyLarge, t.bodyMedium, t.bodySmall,
            t.labelLarge, t.labelMedium, t.labelSmall,
        )
        all.forEach { assertEquals(Nunito, it.fontFamily) }
        assertEquals(setOf(FontWeight.Normal, FontWeight.Bold, FontWeight.ExtraBold), all.map { it.fontWeight }.toSet())
        assertEquals(FontWeight.ExtraBold, t.displaySmall.fontWeight)
        assertTrue(t.displaySmall.fontSize.value in 30f..38f)
    }

    @Test
    fun cardsAreRoundedTwentyToTwentyTwoDp() {
        lateinit var s: Shapes
        rule.setContent { KinsightTheme { s = MaterialTheme.shapes } }
        rule.waitForIdle()
        assertEquals(RoundedCornerShape(20.dp), s.medium)
        assertEquals(RoundedCornerShape(22.dp), s.large)
    }
}
