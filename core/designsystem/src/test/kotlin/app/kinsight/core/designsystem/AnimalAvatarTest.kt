package app.kinsight.core.designsystem

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import app.kinsight.core.designsystem.component.AnimalAvatar
import app.kinsight.core.designsystem.component.VectorAnimalAvatar
import app.kinsight.core.designsystem.component.backgroundOf
import app.kinsight.core.designsystem.component.mascotDrawable
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.Animal
import app.kinsight.core.model.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AnimalAvatarTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyAnimalAndMoodLoadsItsOwnRasterAndUnassignedKeepsThePlaceholder() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val ids = mutableSetOf<Int>()
        for (animal in Animal.entries) {
            for (mood in Mood.entries) {
                val id = requireNotNull(mascotDrawable(animal, mood))
                assertEquals("mascot_${animal.name.lowercase()}_${mood.name.lowercase()}", resources.getResourceEntryName(id))
                assertTrue(ids.add(id))
                assertTrue(resources.getDrawable(id, null).intrinsicWidth > 0)
            }
        }
        assertNull(mascotDrawable(null, Mood.CONTENT))
    }

    @Test
    fun everyAnimalInEveryMoodAndThePlaceholderDrawAtBothSizes() {
        rule.setContent {
            Column {
                for (dark in listOf(false, true)) KinsightTheme(darkTheme = dark) {
                    Column {
                        for (size in listOf(32.dp, 56.dp)) {
                            for (animal in Animal.entries) {
                                Row { for (mood in Mood.entries) AnimalAvatar(animal, mood, size = size) }
                            }
                            AnimalAvatar(null, size = size)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun vectorFallbackStillDrawsEveryAnimalAndMood() {
        rule.setContent {
            KinsightTheme {
                Column {
                    for (animal in Animal.entries) {
                        Row { for (mood in Mood.entries) VectorAnimalAvatar(animal, mood, size = 32.dp) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theAvatarIsDecorative() {
        rule.setContent { KinsightTheme { AnimalAvatar(Animal.CAT, Mood.UPSET, Modifier.testTag("a")) } }
        val config = rule.onNodeWithTag("a").fetchSemanticsNode().config
        assertTrue(config.toString(), config.none { it.key != SemanticsProperties.TestTag })
    }

    @Test
    fun whiteFeaturesKeepThreeToOneOnEveryBackground() {
        fun contrast(a: Color, b: Color): Double {
            val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
            return (hi + 0.05) / (lo + 0.05)
        }
        for (animal in Animal.entries) {
            val c = contrast(Color.White, backgroundOf(animal))
            assertTrue("$animal: $c", c >= 3.0)
        }
    }
}
