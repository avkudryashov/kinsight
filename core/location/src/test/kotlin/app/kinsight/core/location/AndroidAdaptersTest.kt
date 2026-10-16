package app.kinsight.core.location

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import app.kinsight.core.model.proto.Location as WireLocation

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidAdaptersTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    @Before
    fun workManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

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

    @Test
    fun stopAndCancelAreSafeWhenNothingRuns() {
        // Ruling L6: the engine's off() calls these on every refresh of a phone that never tracks.
        val cadence = AndroidCadence(app)
        cadence.stopService()
        cadence.cancelTick()
        cadence.cancelPeriodic()
        AndroidProximityAlerts(app).unregister("aa".repeat(16))
        assertEquals(0, alarms.scheduledAlarms.size)
    }

    @Test
    fun armingTheTickAgainReplacesThePendingOne() {
        val cadence = AndroidCadence(app)
        cadence.armTick(LocationEngine.TICK_MS)
        cadence.armTick(LocationEngine.TICK_MS)
        assertEquals(1, alarms.scheduledAlarms.size)
        cadence.cancelTick()
        assertEquals(0, alarms.scheduledAlarms.size)
    }

    @Test
    fun theInZoneCheckIsOneUniqueWork() {
        val cadence = AndroidCadence(app)
        cadence.schedulePeriodic()
        cadence.schedulePeriodic()
        val wm = WorkManager.getInstance(app)
        assertEquals(1, wm.getWorkInfosForUniqueWork(LocationWork.PERIODIC).get().size)
        cadence.cancelPeriodic()
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(LocationWork.PERIODIC).get().single().state)
    }

    @Test
    fun aFixComesFromTheFirstEnabledProvider() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val asked = mutableListOf<String>()
        val source = AndroidFixSource(app) { provider, _, done ->
            asked += provider
            done(android.location.Location(provider).apply { latitude = 1.0; longitude = 2.0; accuracy = 5f; time = 9L })
        }
        assertEquals(Fix(10_000_000, 20_000_000, 5, 9L, WireLocation.Provider.GPS), runBlocking { source.fix(1_000L) })
        assertEquals(listOf(LocationManager.GPS_PROVIDER), asked)
    }

    @Test
    fun aPermissionRevokedBeforeTheRequestIsNoFix() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        // The check passed a moment earlier, the platform refuses the request: no fix, no crash (ruling L6).
        val source = AndroidFixSource(app) { _, _, _ -> throw SecurityException("revoked") }
        assertNull(runBlocking { source.fix(1_000L) })
    }

    @Test
    fun noPermissionAsksNothing() {
        var asked = false
        assertNull(runBlocking { AndroidFixSource(app) { _, _, _ -> asked = true }.fix(1_000L) })
        assertEquals(false, asked)
    }
}
