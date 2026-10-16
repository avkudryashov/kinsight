package app.kinsight.feature.onboarding

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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
        rule.onNodeWithTag("onb_consent_more").performScrollTo().performClick()
        rule.onNodeWithText("every parent in your family group (currently Mom) and parents who join later", substring = true).assertExists()
        rule.onNodeWithText("Your parents will see", substring = true).assertExists()
        rule.onNodeWithText("last 7 days of screen time", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("through that Disk", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("If you hide it, your family sees", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("only a parent can change it", substring = true).performScrollTo().assertIsDisplayed()
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
        rule.onNodeWithTag("onb_consent_more").performScrollTo().performClick()
        rule.onNodeWithText("всех родителей вашей семейной группы (сейчас это Мама) и тех, кто вступит позже", substring = true).assertExists()
        rule.onNodeWithText("Ваши родители увидят, сколько времени вы проводите", substring = true).assertExists()
        rule.onNodeWithText("не более чем за 7 последних дней", substring = true).assertExists()
        rule.onNodeWithText("Принимаю").assertExists()
        rule.onNodeWithText("Не сейчас").assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianConsentNamesTheDiskAndTheLocation() {
        rule.setContent { KinsightTheme(dynamicColor = false) { ConsentScreen("Мама", onAgree = {}, onNotNow = {}) } }
        rule.onNodeWithText("Где:", substring = true).assertExists()
        rule.onNodeWithText("Кто меняет: только родитель", substring = true).assertExists()
        rule.onNodeWithTag("onb_consent_more").performScrollTo().performClick()
        rule.onNodeWithText("в зашифрованном виде через Яндекс Диск", substring = true).performScrollTo().assertIsDisplayed()
    }

    private fun showChildNotificationsStep() = rule.setContent {
        KinsightTheme(dynamicColor = false) {
            DisclosureScreen(
                stringResource(R.string.onb_notifications_title),
                stringResource(R.string.onb_notifications_body_child),
                stringResource(R.string.onb_agree),
                onAgree = {},
                onNotNow = {},
            )
        }
    }

    @Test
    fun theChildNotificationsStepSaysTheFamilySeesThemOff() {
        showChildNotificationsStep()
        rule.onNodeWithText("your family will see that the notification is off on this phone", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianChildNotificationsStepSaysTheFamilySeesThemOff() {
        showChildNotificationsStep()
        rule.onNodeWithText("семья увидит, что на этом телефоне уведомление выключено", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianConsentPointLimitsTheBacklogLikeEnglish() {
        rule.setContent { KinsightTheme(dynamicColor = false) { Text(stringResource(R.string.onb_consent_point_what)) } }
        rule.onNodeWithText("не более чем за последние 7 дней", substring = true).assertExists()
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
    fun scanShowsTheCameraWhenAllowedAndTheCameraAppWayOtherwise() {
        var granted by mutableStateOf(false)
        var asked = false
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                ScanScreen(message = null, cameraGranted = granted, onAllowCamera = { asked = true }, onBack = {}) { Text("camera stand-in") }
            }
        }
        rule.onNodeWithText("may not use the camera", substring = true).assertExists()
        rule.onNodeWithTag("onb_camera").assertDoesNotExist()
        rule.onNodeWithTag("onb_camera_allow").performClick()
        assertTrue(asked)
        granted = true
        rule.waitForIdle()
        rule.onNodeWithTag("onb_camera").assertExists()
        rule.onNodeWithText("camera stand-in").assertExists()
        rule.onNodeWithTag("onb_camera_allow").assertDoesNotExist()
    }

    @Test
    fun theRealScannerComposesWithoutACamera() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                ScanScreen(message = null, cameraGranted = true, onAllowCamera = {}, onBack = {}) {
                    QrScanner(onText = {}, onUnavailable = {}) // Robolectric has no camera: onUnavailable may fire, nothing throws
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("onb_camera").assertExists()
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

    @Test
    fun consentLinksThePrivacyPolicy() {
        var opened = false
        rule.setContent { KinsightTheme(dynamicColor = false) { ConsentScreen("Mom", onAgree = {}, onNotNow = {}, onPrivacyPolicy = { opened = true }) } }
        rule.onNodeWithTag("onb_privacy").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun consentLeadsWithFourPointsAndHidesTheDetailsUntilAsked() {
        rule.setContent { KinsightTheme(dynamicColor = false) { ConsentScreen("Mom", onAgree = {}, onNotNow = {}) } }
        rule.onNodeWithText("What is seen:", substring = true).assertExists()
        rule.onNodeWithText("Who sees it: every parent of your family group (now Mom)", substring = true).assertExists()
        rule.onNodeWithText("Where:", substring = true).assertExists()
        rule.onNodeWithText("Who changes it:", substring = true).assertExists()
        rule.onNodeWithText("Play Protect", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("onb_consent_more").performScrollTo().performClick()
        rule.onNodeWithText("Play Protect", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("If you choose Not now", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aPageSaysWhichStepItIs() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalStepProgress provides StepProgress(3, 8)) { ParentInfoScreen(onNext = {}) }
            }
        }
        rule.onNodeWithTag("onb_step").assertTextEquals("Step 3 of 8")
    }

    @Test
    @Config(qualifiers = "w900dp-h800dp")
    fun aWidePageIsCentredWithTheStepAboveTheTitle() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalStepProgress provides StepProgress(3, 8)) { ParentInfoScreen(onNext = {}) }
            }
        }
        // A 640 dp column centred in 900 dp, plus the page's own 24 dp gutter.
        rule.onNodeWithTag("onb_step").assertLeftPositionInRootIsEqualTo(154.dp)
        val step = rule.onNodeWithTag("onb_step").getUnclippedBoundsInRoot()
        val title = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).getUnclippedBoundsInRoot()
        assertTrue(step.bottom <= title.top)
    }

    @Test
    @Config(qualifiers = "ru")
    fun theStepInRussian() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalStepProgress provides StepProgress(3, 8)) { ParentInfoScreen(onNext = {}) }
            }
        }
        rule.onNodeWithTag("onb_step").assertTextEquals("Шаг 3 из 8")
    }

    @Test
    fun aPageOutsideTheFirstRunHasNoStep() {
        rule.setContent { KinsightTheme(dynamicColor = false) { ConsentScreen("Mom", onAgree = {}, onNotNow = {}) } }
        rule.onNodeWithTag("onb_step").assertDoesNotExist()
    }

    @Test
    fun theDetailsToggleSaysWhetherItIsOpen() {
        rule.setContent { KinsightTheme(dynamicColor = false) { ConsentScreen("Mom", onAgree = {}, onNotNow = {}) } }
        val more = rule.onNodeWithTag("onb_consent_more")
        more.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        more.performScrollTo().performClick()
        more.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun theDetailsToggleStateInRussian() {
        rule.setContent { KinsightTheme(dynamicColor = false) { ConsentScreen("Мама", onAgree = {}, onNotNow = {}) } }
        val more = rule.onNodeWithTag("onb_consent_more")
        more.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свёрнуто"))
        more.performScrollTo().performClick()
        more.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Развёрнуто"))
    }

    @Test
    fun theVisiblePointsMentionTheBacklog() {
        rule.setContent { KinsightTheme(dynamicColor = false) { ConsentScreen("Mom", onAgree = {}, onNotNow = {}) } }
        rule.onNodeWithText("What is seen:", substring = true).assert(hasText("last 7 days", substring = true))
    }

    @Test
    fun theProgressBarIsSilentForTalkBack() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalStepProgress provides StepProgress(3, 8)) { ParentInfoScreen(onNext = {}) }
            }
        }
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }
}
