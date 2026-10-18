package app.kinsight.feature.onboarding

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.SetupItem
import app.kinsight.core.designsystem.theme.KinsightTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChildSetupScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(item: SetupItem, calls: MutableList<String>) = rule.setContent {
        KinsightTheme { ChildSetupStep(item, onAllow = { calls += "allow" }, onLater = { calls += "later" }) }
    }

    @Test
    fun thePreciseLocationStepSaysWhyAndOffersLater() {
        val calls = mutableListOf<String>()
        show(SetupItem.LOCATION, calls)
        rule.onNodeWithText("Where the phone is").assertExists()
        rule.onNodeWithTag("onb_not_now").assertTextEquals("Later").performClick()
        rule.onNodeWithTag("onb_agree").assertTextEquals("Allow").performClick()
        assertEquals(listOf("later", "allow"), calls)
    }

    @Test
    fun theBackgroundStepOpensTheSettings() {
        val calls = mutableListOf<String>()
        show(SetupItem.LOCATION_BACKGROUND, calls)
        rule.onNodeWithTag("onb_agree").assertTextEquals("Open app settings").performClick()
        assertEquals(listOf("allow"), calls)
    }

    @Test
    fun theBatteryStepSaysTheAppIsNotStoppedInTheBackground() {
        val calls = mutableListOf<String>()
        show(SetupItem.BATTERY, calls)
        rule.onNodeWithText("so that the app is not stopped in the background", substring = true).assertExists()
        rule.onNodeWithTag("onb_later").performClick()
        assertEquals(listOf("later"), calls)
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianLater() {
        show(SetupItem.USAGE_ACCESS, mutableListOf())
        rule.onNodeWithTag("onb_not_now").assertTextEquals("Позже")
    }
}
