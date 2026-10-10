package app.kinsight.core.designsystem

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
            KinsightTheme(dynamicColor = false) {
                BarChart(listOf(1L, 5L, 3L), listOf("M", "T", "W"), summary = "Average 3 min, most on Tuesday")
            }
        }
        rule.onNodeWithContentDescription("Average 3 min, most on Tuesday").assertExists()
    }

    @Test
    fun qrCodeHasItsDescription() {
        rule.setContent { KinsightTheme(dynamicColor = false) { QrCode("kinsight://pair/1/AAAA", "Invitation QR code") } }
        rule.onNodeWithContentDescription("Invitation QR code").assertExists()
    }
}
