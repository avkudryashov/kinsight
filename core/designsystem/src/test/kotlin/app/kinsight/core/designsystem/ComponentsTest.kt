package app.kinsight.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.designsystem.component.Avatar
import app.kinsight.core.designsystem.component.CategoryStrip
import app.kinsight.core.designsystem.component.LimitBar
import app.kinsight.core.designsystem.component.Pill
import app.kinsight.core.designsystem.component.StripPart
import app.kinsight.core.designsystem.theme.KinsightTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ComponentsTest {
    @get:Rule
    val rule = createComposeRule()
    private val min = 60_000L

    @Test
    fun theLimitBarSaysWhatIsLeftThatItIsReachedAndByHowMuchItIsPassed() {
        var used by mutableLongStateOf(90 * min)
        rule.setContent { KinsightTheme { LimitBar(used, 120 * min) } }
        rule.onNodeWithTag("limit_bar_text", useUnmergedTree = true).assertTextEquals("30 min left of 2 h")
        used = 120 * min
        rule.onNodeWithTag("limit_bar_text", useUnmergedTree = true).assertTextEquals("Limit reached")
        used = 150 * min
        rule.onNodeWithTag("limit_bar_text", useUnmergedTree = true).assertTextEquals("30 min over the limit")
    }

    @Test
    @Config(qualifiers = "ru")
    fun theLimitBarInRussian() {
        rule.setContent { KinsightTheme { LimitBar(90 * min, 120 * min) } }
        rule.onNodeWithTag("limit_bar_text", useUnmergedTree = true).assertTextEquals("Осталось 30 мин из 2 ч")
    }

    @Test
    fun theAvatarIsDecorative() {
        rule.setContent { KinsightTheme { Avatar("Kid") } }
        rule.onNodeWithText("K").assertDoesNotExist()
    }

    @Test
    fun theStripReadsItsPartsOnceAndNamesThemUnderIt() {
        rule.setContent {
            KinsightTheme {
                CategoryStrip(listOf(StripPart("Games", "1 h", 60, Color.Red), StripPart("Video", "30 min", 30, Color.Blue)))
            }
        }
        rule.onNodeWithContentDescription("Games 1 h, Video 30 min").assertExists()
        rule.onNodeWithText("Games 1 h").assertExists()
        rule.onNodeWithText("Video 30 min").assertExists()
    }

    @Test
    fun aPillShowsItsText() {
        rule.setContent { KinsightTheme { Pill("limit") } }
        rule.onNodeWithText("limit").assertExists()
    }
}
