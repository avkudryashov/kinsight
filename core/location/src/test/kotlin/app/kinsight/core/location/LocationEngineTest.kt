package app.kinsight.core.location

import app.kinsight.core.model.proto.Location
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias Recorded = Pair<List<ZoneEvent>, Report?>

private class FakeHost(var zones: List<Zone>?) : LocationHost {
    val recorded = mutableListOf<Recorded>()
    var uploads = 0

    override suspend fun zones() = zones

    override suspend fun record(events: List<ZoneEvent>, report: Report?): Boolean {
        recorded += events to report
        return true
    }

    override fun upload() {
        uploads++
    }
}

private class FakeFixes : FixSource {
    var ready = true
    var next: Fix? = null
    var asked = 0

    override fun ready() = ready

    override suspend fun fix(timeoutMs: Long): Fix? {
        asked++
        return next
    }
}

private class FakeAlerts : ProximityAlerts {
    val registered = linkedMapOf<String, Zone>()
    val log = mutableListOf<String>()

    override fun register(zone: Zone) {
        registered[zone.id] = zone
        log += "+${zone.id}"
    }

    override fun unregister(zoneId: String) {
        registered.remove(zoneId)
        log += "-$zoneId"
    }
}

private class FakeCadence : Cadence {
    var service = false
    var refused = false
    var tickInMs: Long? = null
    val arms = mutableListOf<Long>()
    var periodic = false

    override fun startService(): Boolean {
        if (refused) return false
        service = true
        return true
    }

    override fun stopService() {
        service = false
    }

    override fun armTick(delayMs: Long) {
        arms += delayMs
        tickInMs = delayMs
    }

    override fun cancelTick() {
        tickInMs = null
    }

    override fun schedulePeriodic() {
        periodic = true
    }

    override fun cancelPeriodic() {
        periodic = false
    }
}

private class MemoryTrackerStore : TrackerStore {
    var state = TrackerState()

    override suspend fun load() = state

    override suspend fun save(s: TrackerState) {
        state = s
    }

    override suspend fun clear() {
        state = TrackerState()
    }
}

class LocationEngineTest {
    private val home = Zone("aa".repeat(16), 404_168_000, -37_038_000, 150)
    private val school = Zone("bb".repeat(16), 404_268_000, -37_038_000, 200) // about 1.1 km north
    private val now = 1_760_000_000_000L
    private val host = FakeHost(listOf(home, school))
    private val store = MemoryTrackerStore()
    private val fixes = FakeFixes()
    private val alerts = FakeAlerts()
    private val cadence = FakeCadence()
    private val engine = LocationEngine(host, store, fixes, alerts, cadence, clock = { now })

    private fun at(z: Zone) = Fix(z.latE7, z.lonE7, 10, now, Location.Provider.GPS)
    private val away = Fix(404_168_000 - 100_000, -37_038_000, 10, now, Location.Provider.GPS) // about 1.1 km south of home

    @Test
    fun theFirstFixIsABaselineWithoutEvents() = runTest {
        fixes.next = at(home)
        engine.refresh()
        assertEquals(setOf(home.id, school.id), alerts.registered.keys)
        assertEquals<List<Recorded>>(listOf(emptyList<ZoneEvent>() to Report.InZone(home.id, now)), host.recorded)
        assertEquals(Mode.IN_ZONE, store.state.mode)
        assertTrue(cadence.periodic)
        assertFalse(cadence.service)
        assertEquals(0, host.uploads)
    }

    /** Review Focus 1. */
    @Test
    fun anExitSignsTheEventAndAPointAndStartsTheOutsideCadence() = runTest {
        store.state = TrackerState(Mode.IN_ZONE, setOf(home.id))
        fixes.next = away
        engine.onProximity(home.id, entering = false)
        assertEquals<List<Recorded>>(listOf(listOf(ZoneEvent(home.id, entered = false, atMs = now)) to Report.Point(away)), host.recorded)
        assertTrue(cadence.service)
        assertEquals(LocationEngine.TICK_MS, cadence.tickInMs)
        assertEquals(1, host.uploads)
        host.recorded.clear()
        engine.onProximity(home.id, entering = true)
        assertEquals<List<Recorded>>(listOf(listOf(ZoneEvent(home.id, entered = true, atMs = now)) to null), host.recorded)
        assertFalse(cadence.service)
        assertNull(cadence.tickInMs)
        assertEquals(2, host.uploads)
    }

    /** Review Focus 2. */
    @Test
    fun inAZoneOnlyTheZoneIdIsReported() = runTest {
        store.state = TrackerState(Mode.IN_ZONE, setOf(home.id))
        fixes.next = at(home)
        engine.onTick(Tick.PERIODIC)
        assertEquals<List<Recorded>>(listOf(emptyList<ZoneEvent>() to Report.InZone(home.id, now)), host.recorded)
        assertEquals(0, host.uploads, "an in-zone report rides the normal child cadence (spec §9)")
    }

    @Test
    fun outsideEveryTickSignsAPointAndUploads() = runTest {
        store.state = TrackerState(Mode.OUTSIDE)
        fixes.next = away
        engine.onTick(Tick.OUTSIDE)
        engine.onTick(Tick.OUTSIDE)
        assertEquals(2, host.recorded.count { it.second is Report.Point })
        assertEquals(2, host.uploads)
        assertEquals(LocationEngine.TICK_MS, cadence.tickInMs)
        assertEquals(now + LocationEngine.TICK_MS, store.state.nextTickMs)
    }

    @Test
    fun aRefusedServiceStillTicks() = runTest {
        cadence.refused = true
        store.state = TrackerState(Mode.IN_ZONE, setOf(home.id))
        fixes.next = away
        engine.onProximity(home.id, entering = false)
        assertFalse(cadence.service)
        assertEquals(LocationEngine.TICK_MS, cadence.tickInMs, "spec §14: without the service the 5-minute tick goes on")
    }

    @Test
    fun aStaleOutsideTickInAZoneStopsTheCadence() = runTest {
        store.state = TrackerState(Mode.IN_ZONE, setOf(home.id))
        cadence.service = true
        cadence.tickInMs = LocationEngine.TICK_MS
        engine.onTick(Tick.OUTSIDE)
        assertFalse(cadence.service)
        assertNull(cadence.tickInMs)
        assertTrue(host.recorded.isEmpty())
        assertEquals(0, fixes.asked)
    }

    @Test
    fun refreshRegistersChangedZonesOnlyUnlessRearmed() = runTest {
        fixes.next = at(home)
        engine.refresh()
        host.zones = listOf(home.copy(radiusM = 300))
        engine.refresh()
        assertEquals(mapOf(home.id to home.copy(radiusM = 300)), alerts.registered)
        alerts.log.clear()
        engine.refresh()
        assertTrue(alerts.log.isEmpty(), "unchanged zones are not registered again")
        engine.refresh(rearm = true)
        assertEquals(listOf("+${home.id}"), alerts.log, "after a boot every alert is registered again")
    }

    @Test
    fun refreshFiresAnOverdueTickAtOnceWithoutAnExtraFix() = runTest {
        store.state = TrackerState(Mode.OUTSIDE, registered = setOf(home.signature, school.signature), nextTickMs = now - 2 * LocationEngine.TICK_MS)
        engine.refresh()
        assertTrue(cadence.service)
        assertEquals(0L, cadence.tickInMs)
        assertEquals(now, store.state.nextTickMs)
        assertEquals(0, fixes.asked)
    }

    /** Review Important 1: the 30-minute check over an overdue outside tick takes one fix and arms the tick once. */
    @Test
    fun theCheckOverAnOverdueTickTakesOneFix() = runTest {
        store.state = TrackerState(Mode.OUTSIDE, registered = setOf(home.signature, school.signature), nextTickMs = now - 2 * LocationEngine.TICK_MS)
        fixes.next = away
        engine.onTick(Tick.PERIODIC)
        assertEquals(1, fixes.asked)
        assertEquals<List<Recorded>>(listOf(emptyList<ZoneEvent>() to Report.Point(away)), host.recorded)
        assertEquals(listOf(LocationEngine.TICK_MS), cadence.arms, "no zero-delay tick next to the check's own fix")
        assertEquals(now + LocationEngine.TICK_MS, store.state.nextTickMs)
    }

    /** Emulator finding: a process start (rearm) keeps the saved tick time instead of pushing it 5 minutes on. */
    @Test
    fun aRestartKeepsTheSavedTickTime() = runTest {
        val registered = setOf(home.signature, school.signature)
        store.state = TrackerState(Mode.OUTSIDE, registered = registered, nextTickMs = now + 120_000)
        engine.refresh(rearm = true)
        assertEquals(120_000L, cadence.tickInMs)
        assertEquals(now + 120_000, store.state.nextTickMs)

        store.state = TrackerState(Mode.OUTSIDE, registered = registered, nextTickMs = now - 60_000)
        engine.refresh(rearm = true)
        assertEquals(0L, cadence.tickInMs, "a tick already due fires at once")
        assertEquals(now, store.state.nextTickMs)

        store.state = TrackerState(Mode.OUTSIDE, registered = registered, nextTickMs = now + 3 * LocationEngine.TICK_MS)
        engine.refresh(rearm = true)
        assertEquals(LocationEngine.TICK_MS, cadence.tickInMs, "a clock gone back never delays the tick past 5 minutes")
        assertEquals(now + LocationEngine.TICK_MS, store.state.nextTickMs)

        store.state = TrackerState(Mode.OUTSIDE, registered = registered, nextTickMs = null)
        engine.refresh(rearm = true)
        assertEquals(0L, cadence.tickInMs)
        assertEquals(0, fixes.asked)
    }

    /** Review Focus 5. */
    @Test
    fun aResetOrALostGateTurnsEverythingOff() = runTest {
        fixes.next = away
        engine.refresh()
        assertTrue(cadence.service)
        host.zones = null // the child paused, was removed, or the cloud went off
        host.recorded.clear()
        engine.onTick(Tick.OUTSIDE)
        assertTrue(alerts.registered.isEmpty())
        assertFalse(cadence.service)
        assertNull(cadence.tickInMs)
        assertFalse(cadence.periodic)
        assertEquals(TrackerState(), store.state)
        assertTrue(host.recorded.isEmpty())
        // LocalReset (under the cloud runLock) does the same through forget().
        host.zones = listOf(home)
        engine.refresh()
        engine.forget()
        assertTrue(alerts.registered.isEmpty())
        assertFalse(cadence.service)
        assertEquals(TrackerState(), store.state)
    }

    @Test
    fun withoutTheBackgroundPermissionOnlyTheCheckRuns() = runTest {
        fixes.ready = false
        engine.refresh()
        assertTrue(alerts.registered.isEmpty())
        assertTrue(cadence.periodic, "the 30-minute check stays to find access given back")
        assertEquals(0, fixes.asked)
    }

    /** Emulator finding: access revoked, then given back in the system settings with the app closed. */
    @Test
    fun theCheckPicksUpAccessGivenBackOutsideTheApp() = runTest {
        fixes.next = away
        engine.refresh()
        assertTrue(cadence.service)
        fixes.ready = false
        engine.onTick(Tick.OUTSIDE)
        assertTrue(alerts.registered.isEmpty())
        assertFalse(cadence.service)
        assertNull(cadence.tickInMs)
        assertTrue(cadence.periodic)
        assertEquals(TrackerState(), store.state)

        fixes.ready = true
        fixes.asked = 0
        host.recorded.clear()
        engine.onTick(Tick.PERIODIC)
        assertEquals(setOf(home.id, school.id), alerts.registered.keys, "the alerts are registered again")
        assertEquals(1, fixes.asked, "the first decision is the check's only fix")
        assertEquals<List<Recorded>>(listOf(emptyList<ZoneEvent>() to Report.Point(away)), host.recorded)
        assertEquals(Mode.OUTSIDE, store.state.mode)
        assertTrue(cadence.service)
    }

    /** Ruling L2: a non-finite or out-of-range fix is dropped, never signed. */
    @Test
    fun aNonFiniteOrOutOfRangeFixIsNeverSigned() = runTest {
        fun of(lat: Double, lon: Double, acc: Float? = 10f, time: Long = now) = FixCheck.of(lat, lon, acc, time, Location.Provider.GPS)
        assertEquals(Fix(404_168_000, -37_038_000, 13, now, Location.Provider.GPS), of(40.4168, -3.7038, 12.4f))
        assertNull(of(Double.NaN, -3.7038))
        assertNull(of(40.4168, Double.NaN))
        assertNull(of(Double.POSITIVE_INFINITY, -3.7038))
        assertNull(of(40.4168, Double.NEGATIVE_INFINITY))
        assertNull(of(40.4168, -3.7038, Float.NaN))
        assertNull(of(40.4168, -3.7038, Float.POSITIVE_INFINITY))
        assertNull(of(90.0001, -3.7038))
        assertNull(of(40.4168, -180.0001))
        assertNull(of(40.4168, -3.7038, time = 0))
        assertEquals(Int.MAX_VALUE, of(40.4168, -3.7038, 3e9f)?.accuracyM)

        store.state = TrackerState(Mode.OUTSIDE)
        for (bad in listOf(away.copy(latE7 = 900_000_001), away.copy(lonE7 = -1_800_000_001), away.copy(timeMs = 0))) {
            fixes.next = bad
            engine.onTick(Tick.OUTSIDE)
        }
        assertTrue(host.recorded.isEmpty())
        assertEquals(0, host.uploads)
        assertEquals(LocationEngine.TICK_MS, cadence.tickInMs, "the outside cadence goes on")
    }

    /** Ruling L4: a fix without accuracy is vague: it decides nothing and is never signed as precise. */
    @Test
    fun aFixWithoutAccuracyIsVague() = runTest {
        val vague = LocationEngine.VAGUE_ACCURACY_M
        assertTrue(vague > ZoneTracker.MAX_DECISIVE_ACCURACY_M)
        assertEquals(vague, FixCheck.of(40.4168, -3.7038, null, now, Location.Provider.GPS)?.accuracyM)
        assertEquals(vague, FixCheck.of(40.4168, -3.7038, 0f, now, Location.Provider.GPS)?.accuracyM)
        assertEquals(vague, FixCheck.of(40.4168, -3.7038, -3f, now, Location.Provider.GPS)?.accuracyM)

        fixes.next = at(home).copy(accuracyM = 0)
        engine.refresh()
        assertEquals(Mode.UNKNOWN, store.state.mode, "no baseline from a fix without accuracy")
        assertTrue(host.recorded.isEmpty())

        store.state = TrackerState(Mode.IN_ZONE, setOf(home.id), store.state.registered)
        fixes.next = away.copy(accuracyM = 0)
        engine.onTick(Tick.PERIODIC)
        assertEquals<List<Recorded>>(listOf(emptyList<ZoneEvent>() to Report.InZone(home.id, now)), host.recorded, "no exit from a vague fix")

        host.recorded.clear()
        store.state = TrackerState(Mode.OUTSIDE, registered = store.state.registered)
        fixes.next = away.copy(accuracyM = -5)
        engine.onTick(Tick.OUTSIDE)
        assertEquals<List<Recorded>>(listOf(emptyList<ZoneEvent>() to Report.Point(away.copy(accuracyM = vague))), host.recorded)
    }

    @Test
    fun anOverdueTickArmedByARefreshIsNotPushedBackByTheNext() = runTest {
        store.state = TrackerState(Mode.OUTSIDE, registered = setOf(home.signature, school.signature), nextTickMs = now - 2 * LocationEngine.TICK_MS)
        engine.refresh()
        assertEquals(now, store.state.nextTickMs)
        cadence.tickInMs = null
        engine.refresh()
        assertNull(cadence.tickInMs, "a tick armed in time is left alone")
    }

    @Test
    fun aGateLostBeforeAnyDecisionStillCancelsTheCheck() = runTest {
        host.zones = emptyList()
        fixes.next = null
        engine.refresh()
        assertTrue(cadence.periodic)
        assertEquals(TrackerState(), store.state)
        host.zones = null
        engine.refresh()
        assertFalse(cadence.periodic)
    }

    /** Y4 parked minor: a stale alert of a deleted zone is taken back and the mode follows what is left. */
    @Test
    fun aStaleAlertOfADeletedZoneIsUnregistered() = runTest {
        store.state = TrackerState(Mode.IN_ZONE, setOf(home.id), registered = setOf(home.signature, school.signature))
        host.zones = listOf(school) // home deleted, no refresh yet
        engine.onProximity(home.id, entering = false)
        assertEquals(listOf("-${home.id}"), alerts.log)
        assertEquals(setOf(school.signature), store.state.registered)
        assertEquals(Mode.OUTSIDE, store.state.mode)
        assertTrue(cadence.service, "outside now: the 5-minute cadence")
        assertTrue(host.recorded.isEmpty())
    }

    /** Y4 parked minor: the alerts are on the saved list before the platform registers them. */
    @Test
    fun alertsAreSavedBeforeTheyAreRegistered() = runTest {
        val seen = mutableListOf<Set<String>>()
        val watching = object : ProximityAlerts {
            override fun register(zone: Zone) {
                seen += kotlinx.coroutines.runBlocking { store.load().registered }
            }

            override fun unregister(zoneId: String) = Unit
        }
        fixes.next = at(home)
        LocationEngine(host, store, fixes, watching, cadence, clock = { now }).refresh()
        assertEquals(2, seen.size)
        for (s in seen) assertTrue(s.containsAll(setOf(home.signature, school.signature)), "$s")
    }

    /** Y4 parked minor: a record that signed nothing (no group) leaves the state, so the event is tried again. */
    @Test
    fun anUnsignedStepDoesNotMoveTheStateOn() = runTest {
        val refusing = object : LocationHost by host {
            override suspend fun record(events: List<ZoneEvent>, report: Report?) = false
        }
        val start = TrackerState(Mode.IN_ZONE, setOf(home.id), registered = setOf(home.signature, school.signature))
        store.state = start
        fixes.next = away
        LocationEngine(refusing, store, fixes, alerts, cadence, clock = { now }).onProximity(home.id, entering = false)
        assertEquals(start, store.state)
        assertEquals(0, host.uploads)
        assertFalse(cadence.service)
    }
}
