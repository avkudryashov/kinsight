package app.kinsight.core.designsystem

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.designsystem.component.BarChart
import app.kinsight.core.designsystem.component.QrCode
import app.kinsight.core.designsystem.theme.KinsightTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChartsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun barChartIsReadAsItsSummary() {
        rule.setContent {
            KinsightTheme() {
                BarChart(listOf(1L, 5L, 3L), listOf("M", "T", "W"), summary = "Average 3 min, most on Tuesday")
            }
        }
        rule.onNodeWithContentDescription("Average 3 min, most on Tuesday").assertExists()
    }

    @Test
    fun barChartSurvivesEmptyZeroNegativeAndMismatchedData() {
        rule.setContent {
            KinsightTheme() {
                BarChart(emptyList(), emptyList(), summary = "Empty")
                BarChart(listOf(0L, 0L), listOf("M", "T"), summary = "Zeros")
                BarChart(listOf(-5L, 3L, 0L), listOf("M"), summary = "Odd")
            }
        }
        rule.onNodeWithContentDescription("Empty").assertExists()
        rule.onNodeWithContentDescription("Zeros").assertExists()
        rule.onNodeWithContentDescription("Odd").assertExists()
    }

    @Test
    fun qrCodeIsAnImageWithItsDescription() {
        rule.setContent { KinsightTheme() { QrCode("kinsight://pair/1/AAAA", "Invitation QR code") } }
        rule.onNode(
            hasContentDescription("Invitation QR code") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
        ).assertExists()
    }

    @Test
    fun qrCodeWithEmptyTextShowsTheErrorState() {
        rule.setContent { KinsightTheme() { QrCode("", "Invitation QR code") } }
        rule.onNodeWithText("Can't show the QR code").assertExists()
    }

    @Test
    fun qrCodeWithTooLongTextShowsTheErrorState() {
        rule.setContent { KinsightTheme() { QrCode("A".repeat(5000), "Invitation QR code") } }
        rule.onNodeWithText("Can't show the QR code").assertExists()
    }
}
