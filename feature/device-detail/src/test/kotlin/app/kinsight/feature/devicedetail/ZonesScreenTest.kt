package app.kinsight.feature.devicedetail

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.GeoPoint
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.data.ZoneDraft
import app.kinsight.core.data.ZoneSave
import app.kinsight.core.data.ZoneView
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ZonesScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val home = ZoneView("aa".repeat(16), "Home", 404_168_000, -37_038_000, 150, DeviceId("aa".repeat(16)))
    private val kidPoint = GeoPoint(404_200_000, -37_000_000, 20, 1_000)
    private val saved = mutableListOf<ZoneDraft>()
    private val deleted = mutableListOf<String>()

    private fun show(state: ZonesState, failed: ZoneFailure? = null, here: Pair<Int, Int>? = 404_100_000 to -37_100_000) = rule.setContent {
        KinsightTheme() {
            ZonesScreen(
                state, failed,
                onSave = { d, done -> saved += d; done() },
                onDelete = { deleted += it },
                onHere = { it(here) },
                onDismissError = {},
                onBack = {},
            )
        }
    }

    @Test
    fun aParentSeesTheZonesAndCanAddOne() {
        show(ZonesState(listOf(home), canEdit = true, children = listOf(kid to kidPoint)))
        rule.onNodeWithText("Home").assertExists()
        rule.onNodeWithText("Radius 150 m").assertExists()
        rule.onNodeWithTag("zones_add").assertExists()
    }

    @Test
    fun aChildSeesTheZonesButCannotChangeThem() {
        show(ZonesState(listOf(home), canEdit = false, children = emptyList()))
        rule.onNodeWithTag("zones_add").assertDoesNotExist()
        rule.onNodeWithText("Only the group's admins change the safe zones.").assertExists()
        rule.onNodeWithTag("zone_${home.id}").assertHasNoClickAction()
    }

    @Test
    fun aNewZoneTakesTheChildsLastPointAndTheName() {
        show(ZonesState(emptyList(), canEdit = true, children = listOf(kid to kidPoint)))
        rule.onNodeWithTag("zones_add").performClick()
        rule.onNodeWithTag("zone_save").assertIsNotEnabled()
        rule.onNodeWithTag("zone_name").performTextInput("School")
        rule.onNodeWithTag("zone_from_${kid.id.hex}").performClick()
        rule.onNodeWithTag("zone_save").performScrollTo().performClick()
        assertEquals(listOf(ZoneDraft(null, "School", 404_200_000, -37_000_000, DEFAULT_RADIUS_M)), saved)
    }

    @Test
    fun typedCoordinatesAndMyLocationSetTheCentre() {
        show(ZonesState(emptyList(), canEdit = true, children = emptyList()))
        rule.onNodeWithTag("zones_add").performClick()
        rule.onNodeWithTag("zone_name").performTextInput("Club")
        rule.onNodeWithTag("zone_coords").performTextInput("40.41680, -3.70380")
        rule.onNodeWithTag("zone_save").performScrollTo().performClick()
        assertEquals(ZoneDraft(null, "Club", 404_168_000, -37_038_000, DEFAULT_RADIUS_M), saved.last())
        rule.onNodeWithTag("zones_add").performClick()
        rule.onNodeWithTag("zone_name").performTextInput("Park")
        rule.onNodeWithTag("zone_here").performClick()
        rule.onNodeWithTag("zone_save").performScrollTo().performClick()
        assertEquals(ZoneDraft(null, "Park", 404_100_000, -37_100_000, DEFAULT_RADIUS_M), saved.last())
    }

    @Test
    fun aRefusedSaveIsShownInTheOpenEditor() {
        show(ZonesState(listOf(home), canEdit = true, children = emptyList()), failed = ZoneFailure(ZoneSave.NOT_ALLOWED))
        rule.onNodeWithTag("zone_${home.id}").performClick()
        rule.onNodeWithTag("zone_failed").assertExists()
    }

    /** Y4 Task 10 review Minor 3: a failed delete (the editor is closed by then) says delete, not save. */
    @Test
    fun aFailedDeleteSaysItWasTheDelete() {
        show(ZonesState(listOf(home), canEdit = true, children = emptyList()), failed = ZoneFailure(ZoneSave.NOT_ALLOWED, delete = true))
        rule.onNodeWithTag("zones_failed").assertTextContains("Could not delete the zone", substring = true)
        rule.onNodeWithText("Could not save the zone", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("zone_${home.id}").performClick()
        rule.onNodeWithTag("zone_failed").assertDoesNotExist() // not this editor's failure
    }

    /** Y4 Task 10 review Minor 6: the slider moves the radius by its stops, and the save takes it. */
    @Test
    fun theSliderSetsTheRadius() {
        show(ZonesState(listOf(home), canEdit = true, children = emptyList()))
        rule.onNodeWithTag("zone_${home.id}").performClick()
        rule.onNodeWithTag("zone_radius").performSemanticsAction(SemanticsActions.SetProgress) { it(ZONE_RADII.indexOf(500).toFloat()) }
        rule.onNodeWithText("Radius: 500 m").assertExists()
        rule.onNodeWithTag("zone_save").performScrollTo().performClick()
        assertEquals(500, saved.single().radiusM)
        assertEquals(home.id, saved.single().id)
    }

    /** Y4 Task 10 review Minor 6: «My location» without a fix (refused or none) says so and leaves the centre unset. */
    @Test
    fun myLocationWithoutAFixSaysSo() {
        show(ZonesState(emptyList(), canEdit = true, children = emptyList()), here = null)
        rule.onNodeWithTag("zones_add").performClick()
        rule.onNodeWithTag("zone_here").performClick()
        rule.onNodeWithText("Could not find this phone's location.").assertExists()
        rule.onNodeWithTag("zone_name").performTextInput("Park")
        rule.onNodeWithTag("zone_save").assertIsNotEnabled()
    }

    /** Y4 Task 10 review Minor 6: rights gone while editing close the editor; nothing is saved. */
    @Test
    fun losingTheRightToEditClosesTheEditor() {
        var state by mutableStateOf(ZonesState(listOf(home), canEdit = true, children = emptyList()))
        rule.setContent {
            KinsightTheme() {
                ZonesScreen(state, null, onSave = { d, done -> saved += d; done() }, onDelete = {}, onHere = {}, onDismissError = {}, onBack = {})
            }
        }
        rule.onNodeWithTag("zone_${home.id}").performClick()
        rule.onNodeWithTag("zone_save").assertExists()
        state = state.copy(canEdit = false)
        rule.waitForIdle()
        rule.onNodeWithTag("zone_save").assertDoesNotExist()
        rule.onNodeWithText("Only the group's admins change the safe zones.").assertExists()
        state = state.copy(canEdit = true)
        rule.waitForIdle()
        rule.onNodeWithTag("zone_save").assertDoesNotExist() // the draft went with the rights
        assertTrue(saved.isEmpty())
    }

    @Test
    fun anOffStopRadiusSurvivesARename() {
        val odd = home.copy(radiusM = 175)
        show(ZonesState(listOf(odd), canEdit = true, children = emptyList()))
        rule.onNodeWithTag("zone_${odd.id}").performClick()
        rule.onNodeWithText("Radius: 175 m").assertExists()
        rule.onNodeWithTag("zone_name").performTextReplacement("Home 2")
        rule.onNodeWithTag("zone_save").performScrollTo().performClick()
        assertEquals(ZoneDraft(odd.id, "Home 2", odd.latE7, odd.lonE7, 175), saved.last())
    }

    @Test
    fun theDraftSurvivesARecreation() {
        val restore = StateRestorationTester(rule)
        restore.setContent {
            KinsightTheme() {
                ZonesScreen(
                    ZonesState(emptyList(), canEdit = true, children = emptyList()), null,
                    onSave = { d, done -> saved += d; done() },
                    onDelete = {}, onHere = {}, onDismissError = {}, onBack = {},
                )
            }
        }
        rule.onNodeWithTag("zones_add").performClick()
        rule.onNodeWithTag("zone_name").performTextInput("Club")
        rule.onNodeWithTag("zone_coords").performTextInput("40.41680, -3.70380")
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("zone_name").assertTextContains("Club")
        rule.onNodeWithTag("zone_save").performScrollTo().performClick()
        assertEquals(ZoneDraft(null, "Club", 404_168_000, -37_038_000, DEFAULT_RADIUS_M), saved.last())
    }

    @Test
    fun aPendingMyLocationIsAskedAgainAfterARecreation() {
        val restore = StateRestorationTester(rule)
        val asks = mutableListOf<(Pair<Int, Int>?) -> Unit>()
        restore.setContent {
            KinsightTheme() {
                ZonesScreen(
                    ZonesState(emptyList(), canEdit = true, children = emptyList()), null,
                    onSave = { d, done -> saved += d; done() },
                    onDelete = {}, onHere = { asks += it }, onDismissError = {}, onBack = {},
                )
            }
        }
        rule.onNodeWithTag("zones_add").performClick()
        rule.onNodeWithTag("zone_name").performTextInput("Park")
        rule.onNodeWithTag("zone_here").performClick()
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(2, asks.size)
        rule.runOnIdle { asks.last()(404_100_000 to -37_100_000) }
        rule.onNodeWithTag("zone_save").performScrollTo().performClick()
        assertEquals(ZoneDraft(null, "Park", 404_100_000, -37_100_000, DEFAULT_RADIUS_M), saved.last())
    }

    @Test
    fun unreadableCoordinatesAreMarkedAndAChildsPointClearsThem() {
        show(ZonesState(emptyList(), canEdit = true, children = listOf(kid to kidPoint)))
        rule.onNodeWithTag("zones_add").performClick()
        rule.onNodeWithTag("zone_coords").performTextInput("40,4168 -3,7038")
        rule.onNodeWithTag("zone_coords").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        rule.onNodeWithTag("zone_from_${kid.id.hex}").performClick()
        rule.onNodeWithTag("zone_coords").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    @Test
    fun theNameStopsAtTheRulesCap() {
        show(ZonesState(emptyList(), canEdit = true, children = emptyList()))
        rule.onNodeWithTag("zones_add").performClick()
        rule.onNodeWithTag("zone_name").performTextInput("a".repeat(64))
        rule.onNodeWithTag("zone_name").performTextInput("b")
        rule.onNodeWithTag("zone_coords").performTextInput("40.41680, -3.70380")
        rule.onNodeWithTag("zone_save").performScrollTo().performClick()
        assertEquals("a".repeat(64), saved.last().name)
    }

    @Test
    fun deletingAsksFirst() {
        show(ZonesState(listOf(home), canEdit = true, children = emptyList()))
        rule.onNodeWithTag("zone_${home.id}").performClick()
        rule.onNodeWithTag("zone_delete").performScrollTo().performClick()
        assertTrue(deleted.isEmpty())
        rule.onNodeWithTag("zone_delete_confirm").performClick()
        assertEquals(listOf(home.id), deleted)
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianTitleAndEmptyList() {
        show(ZonesState(emptyList(), canEdit = true, children = emptyList()))
        rule.onNodeWithText("Безопасные зоны").assertExists()
        rule.onNodeWithTag("zones_empty").assertExists()
    }
}
