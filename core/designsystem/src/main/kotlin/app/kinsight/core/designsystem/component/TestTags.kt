package app.kinsight.core.designsystem.component

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Test tags as resource ids (uiautomator, adb scripts) for content that lives in its own window: an AlertDialog is
 * its own composition, so the flag on the app's root Surface does not reach it. Pass it as every dialog's modifier.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.dialogTestTags(): Modifier = semantics { testTagsAsResourceId = true }
