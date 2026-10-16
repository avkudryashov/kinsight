package app.kinsight.core.location

import android.Manifest
import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import app.kinsight.core.model.proto.Location as WireLocation

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidAdaptersTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aLocationBecomesAFixRoundedToE7() {
        val l = android.location.Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 40.4168
            longitude = -3.7038
            accuracy = 12.4f
            time = 1_000L
        }
        assertEquals(Fix(404_168_000, -37_038_000, 13, 1_000L, WireLocation.Provider.GPS), Fixes.of(l))
        val fused = android.location.Location(LocationManager.FUSED_PROVIDER).apply {
            latitude = 1.0
            longitude = 2.0
            time = 5L
        }
        // Rulings L4, L5: a fix without accuracy is vague, never a precise ±0 m.
        assertEquals(LocationEngine.VAGUE_ACCURACY_M, Fixes.of(fused)?.accuracyM)
        assertEquals(WireLocation.Provider.FUSED, Fixes.of(fused)?.provider)
    }

    @Test
    fun anUnusableLocationIsDropped() {
        fun at(lat: Double, lon: Double) = android.location.Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            accuracy = 10f
            time = 1_000L
        }
        assertNull(Fixes.of(at(Double.NaN, 2.0)))
        assertNull(Fixes.of(at(1.0, Double.POSITIVE_INFINITY)))
        assertNull(Fixes.of(at(91.0, 2.0)))
        assertNull(Fixes.of(at(1.0, 2.0).apply { time = 0L }))
    }

    @Test
    fun providersAreFusedThenGpsThenNetwork() {
        assertEquals(LocationManager.FUSED_PROVIDER, Providers.pick(fused = true, gps = true, network = true))
        assertEquals(LocationManager.GPS_PROVIDER, Providers.pick(fused = false, gps = true, network = true))
        assertEquals(LocationManager.NETWORK_PROVIDER, Providers.pick(fused = false, gps = false, network = true))
        assertNull(Providers.pick(fused = false, gps = false, network = false))
    }

    @Test
    fun aZoneAlertNamesItsZoneAndNothingElse() {
        val id = "ab".repeat(16)
        assertEquals(id, ZoneAlertReceiver.zoneOf(Intent().setData(ZoneAlertReceiver.uri(id))))
        assertNull(ZoneAlertReceiver.zoneOf(Intent().setData(Uri.parse("https://example.org/$id"))))
        assertNull(ZoneAlertReceiver.zoneOf(Intent().setData(ZoneAlertReceiver.uri("xyz"))))
        assertNull(ZoneAlertReceiver.zoneOf(Intent()))
    }

    @Test
    fun eachZoneHasItsOwnAlertIntent() {
        val alerts = AndroidProximityAlerts(app)
        assertEquals(alerts.intent("aa".repeat(16)), alerts.intent("aa".repeat(16)))
        assertNotEquals(alerts.intent("aa".repeat(16)), alerts.intent("bb".repeat(16)))
    }

    @Test
    fun alwaysNeedsTheBackgroundPermission() {
        assertEquals(LocationPermissions.State.NONE, LocationPermissions.state(app))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(LocationPermissions.State.WHILE_IN_USE, LocationPermissions.state(app))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(LocationPermissions.State.ALWAYS, LocationPermissions.state(app))
    }
}
