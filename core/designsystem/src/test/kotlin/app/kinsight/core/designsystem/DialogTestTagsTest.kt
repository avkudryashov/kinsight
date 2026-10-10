package app.kinsight.core.designsystem

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.designsystem.component.dialogTestTags
import app.kinsight.core.designsystem.theme.KinsightTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * An AlertDialog is its own window and composition: the root's testTagsAsResourceId does not reach it, so uiautomator
 * saw «Add»/«Decline» without ids (emulator run 2026-10-10, finding 1). Every dialog sets it on its own content.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DialogTestTagsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun SemanticsNode.tagsAreIds(): Boolean =
        generateSequence(this) { it.parent }.any { it.config.getOrElse(SemanticsPropertiesAndroid.TestTagsAsResourceId) { false } }

    private fun show(modifier: Modifier) = rule.setContent {
        KinsightTheme(dynamicColor = false) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = { TextButton({}, Modifier.testTag("x_confirm")) { Text("Add") } },
                modifier = modifier,
            )
        }
    }

    @Test
    fun aPlainDialogDoesNotExposeItsTags() {
        show(Modifier)
        assertFalse(rule.onNodeWithTag("x_confirm").fetchSemanticsNode().tagsAreIds())
    }

    @Test
    fun dialogTestTagsExposesTheButtonTagAsAResourceId() {
        show(Modifier.dialogTestTags())
        assertTrue(rule.onNodeWithTag("x_confirm").fetchSemanticsNode().tagsAreIds())
    }
}
