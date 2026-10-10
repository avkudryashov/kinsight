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
        rule.onNodeWithText("every parent in your family group (currently Mom) and parents who join later", substring = true).assertExists()
        rule.onNodeWithText("Your parents will see", substring = true).assertExists()
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
        rule.onNodeWithText("including parents who join later", substring = true).assertExists()
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
    fun russianConsentUsesPrinimayuAndNeSeichas() {
        rule.setContent { KinsightTheme(dynamicColor = false) { ConsentScreen("Мама", onAgree = {}, onNotNow = {}) } }
        rule.onNodeWithText("всех родителей вашей семейной группы (сейчас это Мама) и тех, кто вступит позже", substring = true).assertExists()
        rule.onNodeWithText("Ваши родители увидят, сколько времени вы проводите", substring = true).assertExists()
        rule.onNodeWithText("не более чем за 7 последних дней", substring = true).assertExists()
        rule.onNodeWithText("Принимаю").assertExists()
        rule.onNodeWithText("Не сейчас").assertExists()
    }

    @Test
    fun genericBatteryTextNamesAppInfoPath() {
        rule.setContent { KinsightTheme(dynamicColor = false) { BatteryScreen(BatteryVendor.OTHER, false, {}, {}) } }
        rule.onNodeWithText("App info → App battery usage → Unrestricted", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianGenericBatteryTextNamesAppInfoPath() {
        rule.setContent { KinsightTheme(dynamicColor = false) { BatteryScreen(BatteryVendor.OTHER, false, {}, {}) } }
        rule.onNodeWithText("Сведения о приложении → Использование батареи → Без ограничений", substring = true).assertExists()
    }

    @Test
    fun consentShowsAnError() {
        rule.setContent { KinsightTheme(dynamicColor = false) { ConsentScreen("Mom", onAgree = {}, onNotNow = {}, error = "Could not turn on sharing") } }
        rule.onNodeWithText("Could not turn on sharing").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aJoiningParentIsNotToldToInvite() {
        rule.setContent { KinsightTheme(dynamicColor = false) { DoneScreen(child = false, admin = false, message = null, onDone = {}) } }
        rule.onNodeWithText("invite", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun anAdminIsToldToInvite() {
        rule.setContent { KinsightTheme(dynamicColor = false) { DoneScreen(child = false, admin = true, message = null, onDone = {}) } }
        rule.onNodeWithText("invite", substring = true, ignoreCase = true).assertExists()
    }

    @Test
    fun blockedNearbyOffersAppSettings() {
        var opened = false
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                DisclosureScreen("Nearby devices", "Body", "Agree", onAgree = {}, onNotNow = {}, note = "Blocked", onOpenSettings = { opened = true })
            }
        }
        rule.onNodeWithTag("onb_open_settings").performClick()
        rule.waitForIdle()
        assertTrue(opened)
    }
}
