package app.kinsight.feature.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.designsystem.theme.KinsightTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OnboardingScreensTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun consentNamesTheRecipientAndTheBacklog() {
        var agreed = false
        rule.setContent { KinsightTheme(dynamicColor = false) { ConsentScreen("Mom", onAgree = { agreed = true }, onNotNow = {}) } }
        rule.onNodeWithText("Mom", substring = true).assertExists()
        rule.onNodeWithText("last 7 days", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("a notification is always shown", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Your family will see the pause", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Play Protect", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Not now").assertExists()
        rule.onNodeWithTag("onb_agree").performClick()
        rule.waitForIdle()
        assertTrue(agreed)
    }

    @Test
    fun consentWithoutKnownParentsStillSaysWhoSees() {
        rule.setContent { KinsightTheme(dynamicColor = false) { ConsentScreen("", onAgree = {}, onNotNow = {}) } }
        rule.onNodeWithText("every parent in your family group", substring = true).assertExists()
    }

    @Test
    fun welcomeOffersBothPaths() {
        var joined = false
        rule.setContent { KinsightTheme(dynamicColor = false) { WelcomeScreen(onCreate = {}, onJoin = { joined = true }) } }
        rule.onNodeWithTag("onb_create").assertExists()
        rule.onNodeWithTag("onb_join").performClick()
        rule.waitForIdle()
        assertTrue(joined)
    }

    @Test
    fun disclosureUsesAgreeAndNotNow() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) { DisclosureScreen("Nearby devices", "Kinsight finds your family's phones", "Agree", onAgree = {}, onNotNow = {}) }
        }
        rule.onNodeWithText("Agree").assertExists()
        rule.onNodeWithText("Not now").assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianConsentUsesSoglasenAndNeSeichas() {
        rule.setContent { KinsightTheme(dynamicColor = false) { ConsentScreen("Мама", onAgree = {}, onNotNow = {}) } }
        rule.onNodeWithText("Мама", substring = true).assertExists()
        rule.onNodeWithText("последние 7 дней", substring = true).assertExists()
        rule.onNodeWithText("Согласен").assertExists()
        rule.onNodeWithText("Не сейчас").assertExists()
    }
}
