package app.kinsight.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.designsystem.component.CenteredContent
import app.kinsight.core.designsystem.theme.KinsightTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CenteredContentTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show() = rule.setContent {
        KinsightTheme() { CenteredContent { Box(Modifier.fillMaxSize().testTag("content")) } }
    }

    @Test
    @Config(qualifiers = "w900dp-h800dp")
    fun aWideWindowGetsACentredColumn() {
        show()
        rule.onNodeWithTag("content").assertWidthIsEqualTo(640.dp).assertLeftPositionInRootIsEqualTo(130.dp)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun aPhoneKeepsItsWholeWidth() {
        show()
        rule.onNodeWithTag("content").assertWidthIsEqualTo(360.dp).assertLeftPositionInRootIsEqualTo(0.dp)
    }
}
