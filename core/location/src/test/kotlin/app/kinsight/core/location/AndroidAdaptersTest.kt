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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import app.kinsight.core.model.proto.Location as WireLocation

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidAdaptersTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        ServiceGate.reset()
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

    @Test
    fun aStopBeforeTheServiceStartsLetsItGoForegroundAndThenStopItself() {
        // An exit at once followed by an entry (a flap at a zone border): never stopped before startForeground.
        val cadence = AndroidCadence(app)
        assertTrue(cadence.startService())
        cadence.stopService()
        assertEquals(LocationService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        assertNull(shadowOf(app).nextStoppedService)
        val service = runService(1)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(ServiceGate.isForeground)
    }

    @Test
    fun aWantedServiceIsStartedOnceAndStoppedFromOutsideOnceForeground() {
        val cadence = AndroidCadence(app)
        cadence.startService()
        assertNotNull(shadowOf(app).nextStartedService)
        val service = runService(1)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(ServiceGate.isForeground)
        // The next 5-minute tick finds it running: no second start.
        assertTrue(cadence.startService())
        assertNull(shadowOf(app).nextStartedService)
        cadence.stopService()
        assertEquals(LocationService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        assertFalse(ServiceGate.isForeground)
        // Started again after the stop: a new start, not skipped.
        cadence.startService()
        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun aBroadcastIsReleasedWhenItsWorkEndsOrItsBudgetRunsOut() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val quick = AtomicInteger()
        val work = Job()
        Broadcasts.finishWithin(scope, work, budgetMs = 60_000L) { quick.incrementAndGet() }
        work.complete()
        assertEquals(1, quick.get())

        val slow = CompletableDeferred<Unit>()
        val finished = AtomicInteger()
        val stuck = Job()
        Broadcasts.finishWithin(scope, stuck, budgetMs = 50L) {
            finished.incrementAndGet()
            slow.complete(Unit)
        }
        withTimeout(5_000L) { slow.await() }
        assertFalse(stuck.isCancelled) // the work itself goes on
        stuck.complete()
        delay(20L)
        assertEquals(1, finished.get())
        assertTrue(Broadcasts.RECEIVER_BUDGET_MS < 60_000L - LocationEngine.FIX_TIMEOUT_MS)
    }

    @Test
    fun anExitStopAndStartBeforeTheServiceRunsKeepItOnWithOneStart() {
        // Exit, entry, exit again before the first onStartCommand: one start, no stop, the service stays foreground.
        val cadence = AndroidCadence(app)
        cadence.startService()
        cadence.stopService()
        cadence.startService()
        assertNotNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStoppedService)
        val service = runService(1)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(ServiceGate.isForeground)
        assertEquals(0, ServiceGate.pendingStarts)
    }

    @Test
    fun aServiceThatStopsItselfDoesSoByItsStartIdAndAStartAfterwardsIsSent() {
        val cadence = AndroidCadence(app)
        cadence.startService()
        cadence.stopService()
        val service = runService(7)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(7, shadowOf(service).stopSelfId)
        // The restart that follows is not lost: a new start goes out.
        shadowOf(app).nextStartedService
        cadence.startService()
        assertNotNull(shadowOf(app).nextStartedService)
        assertEquals(1, ServiceGate.pendingStarts)
    }

    @Test
    fun aRefusedForegroundStopsByStartIdAndTheNextTickTriesAgain() {
        val cadence = AndroidCadence(app)
        cadence.startService()
        val controller = Robolectric.buildService(LocationService::class.java).create()
        shadowOf(controller.get()).setThrowInStartForeground(SecurityException("type"))
        val service = controller.startCommand(0, 3).get()
        assertEquals(3, shadowOf(service).stopSelfId)
        assertFalse(ServiceGate.isForeground)
        shadowOf(app).nextStartedService
        cadence.startService()
        assertNotNull(shadowOf(app).nextStartedService)
    }

    private fun runService(startId: Int): LocationService =
        Robolectric.buildService(LocationService::class.java).create().startCommand(0, startId).get()

    @Test
    fun aStartTheSystemDropsIsUncountedAndTriedAgain() {
        val sent = AtomicInteger()
        val cadence = AndroidCadence(app) { sent.incrementAndGet(); null }
        assertFalse(cadence.startService())
        assertEquals(0, ServiceGate.pendingStarts)
        assertFalse(cadence.startService())
        assertEquals(2, sent.get())
    }

    @Test
    fun anUnexpectedFailureOfTheStartIsUncountedAndTriedAgain() {
        val sent = AtomicInteger()
        val cadence = AndroidCadence(app) { sent.incrementAndGet(); throw IllegalStateException("odd") }
        assertFalse(cadence.startService())
        assertEquals(0, ServiceGate.pendingStarts)
        assertFalse(cadence.startService())
        assertEquals(2, sent.get())
    }

    @Test
    fun aPendingStartThatNeverArrivesExpires() {
        var now = 1_000L
        ServiceGate.clock = { now }
        val sent = AtomicInteger()
        val cadence = AndroidCadence(app) { sent.incrementAndGet(); android.content.ComponentName(app, LocationService::class.java) }
        assertTrue(cadence.startService())
        now += ServiceGate.PENDING_EXPIRY_MS - 1
        cadence.startService()
        assertEquals(1, sent.get()) // still pending: no second start
        now += 1
        assertEquals(0, ServiceGate.pendingStarts)
        cadence.startService()
        assertEquals(2, sent.get())
        // A late onStartCommand of the forgotten start is still handled: foreground, wanted.
        runService(1)
        assertTrue(ServiceGate.isForeground)
    }
}
