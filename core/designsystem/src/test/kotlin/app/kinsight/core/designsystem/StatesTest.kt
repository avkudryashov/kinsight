package app.kinsight.core.designsystem

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.designsystem.component.EmptyState
import app.kinsight.core.designsystem.component.ErrorState
import app.kinsight.core.designsystem.theme.KinsightTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StatesTest {
    @get:Rule
    val rule = createComposeRule()

    private val politeLiveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun emptyStateTitleIsAHeadingAndNotLive() {
        rule.setContent { KinsightTheme(dynamicColor = false) { EmptyState("Nothing yet", "Pair a phone first") } }
        rule.onNode(isHeading()).assertExists()
        rule.onNodeWithText("Nothing yet").assertExists()
        rule.onNode(politeLiveRegion).assertDoesNotExist()
    }

    @Test
    fun errorStateIsAnnouncedPolitely() {
        rule.setContent { KinsightTheme(dynamicColor = false) { ErrorState("Failed", "Try again") } }
        rule.onNode(isHeading()).assertExists()
        rule.onNode(politeLiveRegion).assertExists()
    }
}
