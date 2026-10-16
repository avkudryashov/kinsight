package app.kinsight.feature.devicedetail

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.ChildLocation
import app.kinsight.core.data.EventView
import app.kinsight.core.data.GeoPoint
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.PlaceStatus
import app.kinsight.core.data.Role
import app.kinsight.core.data.ZoneView
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.StateEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LocationScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = 1_760_000_000_000L
    private val min = 60_000L
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val home = ZoneView("aa".repeat(16), "Home", 404_168_000, -37_038_000, 150, DeviceId("aa".repeat(16)))
    private val p1 = GeoPoint(404_100_000, -37_100_000, 20, now - 20 * min)
    private val p2 = GeoPoint(404_120_000, -37_080_000, 15, now - 5 * min)
    private val exit = EventView(kid.id, 7, StateEvent.Kind.ZONE_EXIT, now - 25 * min, 0, now - 25 * min, 0, zoneId = home.id, zoneName = "Home")
    private var zonesOpened = 0

    private fun show(c: ChildLocation) = rule.setContent {
        KinsightTheme(dynamicColor = false) { LocationScreen(c, now, onZones = { zonesOpened++ }, onBack = {}) }
    }

    @Test
    fun outsideShowsTheLastPointTheRouteAndTheExit() {
        show(ChildLocation(kid, PlaceStatus.Outside(p2, p2.timeMs), p2, listOf(p1, p2), listOf(home), listOf(exit)))
        rule.onNodeWithTag("loc_status").assertTextContains("Outside the safe zones", substring = true)
        rule.onNodeWithTag("loc_last").assertTextContains("40.41200, -3.70800", substring = true)
        rule.onNodeWithText("Today's route: 2 points").assertExists()
        rule.onNodeWithContentDescription("Map of the safe zones and today's route").assertExists()
        rule.onNodeWithTag("loc_list").performScrollToNode(hasTestTag("loc_event_7"))
        rule.onNodeWithText("Left the zone “Home”").assertExists()
        rule.onNodeWithTag("loc_list").performScrollToNode(hasTestTag("loc_zones"))
        rule.onNodeWithTag("loc_zones").performClick()
        assertEquals(1, zonesOpened)
    }

    @Test
    fun inAZoneSaysWhichOne() {
        show(ChildLocation(kid, PlaceStatus.InZone(home.id, "Home", now - 10 * min), null, emptyList(), listOf(home), emptyList()))
        rule.onNodeWithTag("loc_status").assertTextContains("In the zone “Home”", substring = true)
        rule.onNodeWithText("No zone events today").assertExists()
    }

    @Test
    fun withoutDataItSaysSo() {
        show(ChildLocation(kid, null, null, emptyList(), emptyList(), emptyList()))
        rule.onNodeWithTag("loc_status").assertTextContains("No location yet", substring = true)
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianStatus() {
        show(ChildLocation(kid, PlaceStatus.Outside(p2, p2.timeMs), p2, listOf(p2), emptyList(), emptyList()))
        rule.onNodeWithTag("loc_status").assertTextContains("Вне безопасных зон", substring = true)
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val geo = "geo:40.4120000,-3.7080000?q=40.4120000,-3.7080000"

    private fun installMapApp() {
        val info = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.example.maps"; name = "Maps" } }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, Uri.parse(geo)), info)
    }

    @Test
    fun openOnMapHandsTheLastPointToAMapsApp() {
        installMapApp()
        show(ChildLocation(kid, PlaceStatus.Outside(p2, p2.timeMs), p2, listOf(p1, p2), listOf(home), emptyList()))
        rule.onNodeWithTag("loc_list").performScrollToNode(hasTestTag("loc_open_map"))
        rule.onNodeWithTag("loc_open_map").performClick()
        val sent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, sent.action)
        assertEquals(geo, sent.dataString)
    }

    @Test
    fun withoutAMapsAppTheActionIsHidden() {
        show(ChildLocation(kid, PlaceStatus.Outside(p2, p2.timeMs), p2, listOf(p2), emptyList(), emptyList()))
        rule.onNodeWithTag("loc_last").assertExists()
        rule.onNodeWithTag("loc_open_map").assertDoesNotExist()
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun withoutAPointThereIsNothingToOpen() {
        installMapApp()
        show(ChildLocation(kid, PlaceStatus.InZone(home.id, "Home", now), null, emptyList(), listOf(home), emptyList()))
        rule.onNodeWithTag("loc_open_map").assertDoesNotExist()
    }

    @Test
    fun theGeoLinkKeepsFullPrecisionWithDots() =
        assertEquals("geo:40.4168123,-3.7038001?q=40.4168123,-3.7038001", geoUri(404_168_123, -37_038_001))

    /** Y4 review Minor 4: a last point from an earlier day carries its date, today's only its time. */
    @Test
    fun aPointFromAnEarlierDayShowsItsDate() {
        val zone = java.time.ZoneId.systemDefault()
        val yesterday = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val old = GeoPoint(404_120_000, -37_080_000, 15, yesterday)
        show(ChildLocation(kid, PlaceStatus.Outside(old, old.timeMs), old, listOf(old), listOf(home), emptyList()))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val date = android.text.format.DateUtils.formatDateTime(app, yesterday, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
        rule.onNodeWithTag("loc_last").assertTextContains(date, substring = true)
        assertEquals(android.text.format.DateUtils.FORMAT_SHOW_TIME, timeFlags(now - 5 * min, now, zone))
    }
}
