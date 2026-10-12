package app.kinsight.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Every preview composes (light and dark), so a preview never rots into a crash nobody sees. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SettingsPreviewsTest {
    @get:Rule
    val rule = createComposeRule()

    private val previews: List<@Composable () -> Unit> = listOf(
        { SettingsChildPreview() },
        { SettingsParentPreview() },
        { TransparencyPreview() },
        { KeyErrorPreview() },
        { RemovedPreview() },
    )

    private fun renderAll() {
        var current by mutableIntStateOf(0)
        rule.setContent { previews[current]() }
        for (i in previews.indices) {
            current = i
            rule.waitForIdle()
        }
    }

    @Test
    fun everyPreviewRenders() = renderAll()

    @Test
    @Config(qualifiers = "night")
    fun everyPreviewRendersDark() = renderAll()
}
