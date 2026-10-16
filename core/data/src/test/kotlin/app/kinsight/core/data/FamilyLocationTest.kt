package app.kinsight.core.data

import app.kinsight.core.location.Fix
import app.kinsight.core.location.Report
import app.kinsight.core.location.Zone
import app.kinsight.core.location.ZoneEvent
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.proto.Location
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.safeZoneEntry
import app.kinsight.core.transport.ble.GateState
import app.kinsight.core.transport.cloud.InMemoryDisk
import app.kinsight.core.usage.UsageSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FamilyLocationTest {
    private val mom = TestDevice()
    private val kid = TestDevice()
    private val uploads = AtomicInteger()

    @AfterTest
    fun cleanUp() {
        mom.close()
        kid.close()
    }

    private fun host(d: TestDevice, appendOwn: OwnAppend = d.appendOwn, clock: () -> Long = { d.now }) =
        FamilyLocation(d.db, d.groups, d.keyProvider, d.settings, appendOwn, d.snapshots, upload = { uploads.incrementAndGet() }, clock = clock)

    private val point get() = Report.Point(Fix(1, 2, 10, kid.now, Location.Provider.GPS))

    private suspend fun family(cloud: Boolean = true) {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        if (cloud) {
            mom.ownStorage()
            kid.learnsStorageFrom(mom)
        }
    }

    private suspend fun consent() {
        kid.settings.recordConsent(kid.now)
        kid.pipeline.setSharing(true)
    }

    @Test
    fun onlyAConsentingSharingChildOfACloudGroupTracks() = runBlocking<Unit> {
        family()
        assertNull(host(mom).zones(), "a parent never tracks")
        assertNull(host(kid).zones(), "no consent yet")
        consent()
        assertEquals(emptyList(), host(kid).zones())
        lateinit var zone: DecodedEntry
        check(mom.pipeline.appendOwn { a -> listOf(a.safeZoneEntry(ByteArray(16) { 5 }, "Home", 404_168_000, -37_038_000, 150, rank = 1).also { zone = it }) })
        kid.store().applyBatch(listOf(zone))
        assertEquals(listOf(Zone("05".repeat(16), 404_168_000, -37_038_000, 150)), host(kid).zones())
        kid.pipeline.setSharing(false)
        assertNull(host(kid).zones(), "the child's pause stops tracking")
    }

    @Test
    fun withoutACloudStorageNothingIsTracked() = runBlocking<Unit> {
        family(cloud = false)
        consent()
        assertNull(host(kid).zones())
    }

    @Test
    fun anExitIsSignedBeforeItsPointAndAZoneReportHasNoCoordinates() = runBlocking<Unit> {
        family()
        consent()
        val zone = "05".repeat(16)
        val fix = Fix(404_168_000, -37_038_000, 12, kid.now, Location.Provider.GPS)
        assertTrue(host(kid).record(listOf(ZoneEvent(zone, entered = false, atMs = kid.now)), Report.Point(fix)))
        val own = kid.store().entriesAfter(kid.keys.deviceId, 0, 1_000).takeLast(2)
        assertEquals(StateEvent.Kind.ZONE_EXIT, own[0].body.state_event!!.kind)
        assertEquals(zone, own[0].body.state_event!!.zone_id.hex())
        assertEquals(404_168_000, own[1].body.location!!.lat_e7)
        assertTrue(host(kid).record(emptyList(), Report.InZone(zone, kid.now)))
        val inZone = kid.store().locations().last().body.location!!
        assertEquals(0, inZone.lat_e7)
        assertEquals(zone, inZone.zone_id.hex())
    }

    /** Review Minor 1: a pause that lands while the engine waits for its fix (the gate was open at zones()) signs nothing. */
    @Test
    fun aPauseDuringTheFixSignsNothing() = runBlocking<Unit> {
        family()
        consent()
        val pausedMeanwhile: OwnAppend = { build ->
            kid.pipeline.setSharing(false)
            kid.pipeline.appendOwn(build = build)
        }
        val h = host(kid, appendOwn = pausedMeanwhile)
        assertEquals(emptyList(), h.zones(), "the gate is open when the engine decides")
        val before = kid.store().entriesAfter(kid.keys.deviceId, 0, 1_000).size
        assertEquals(false, h.record(listOf(ZoneEvent("05".repeat(16), entered = false, atMs = kid.now)), point))
        val after = kid.store().entriesAfter(kid.keys.deviceId, 0, 1_000)
        assertTrue(after.drop(before).none { it.kind == EntryKind.LOCATION || it.body.state_event?.kind == StateEvent.Kind.ZONE_EXIT })
        assertEquals(emptyList(), kid.store().locations())
    }

    @Test
    fun aParentOrAPhoneWithoutCloudSignsNoLocation() = runBlocking<Unit> {
        family(cloud = false)
        consent()
        assertEquals(false, host(kid).record(emptyList(), point), "no cloud storage")
        assertEquals(false, host(mom).record(emptyList(), point), "a parent")
        assertEquals(emptyList(), kid.store().locations())
        assertEquals(emptyList(), mom.store().locations())
    }

    @Test
    fun theParentFollowsWhileTheChildIsOutside() = runBlocking<Unit> {
        family()
        consent()
        assertEquals(false, host(mom).followNeeded())
        assertTrue(host(kid).record(emptyList(), point))
        mom.store().applyBatch(kid.store().locations())
        assertEquals(true, host(mom).followNeeded())
        assertEquals(false, host(kid).followNeeded(), "the child's own phone never follows")
    }

    @Test
    fun theFollowUpStopsWhenTheWordIsStale() = runBlocking<Unit> {
        family()
        consent()
        assertTrue(host(kid).record(emptyList(), point))
        mom.store().applyBatch(kid.store().locations())
        val later = host(mom, clock = { mom.now + LocationReadModel.FOLLOW_FOR_MS + 1 })
        assertEquals(false, later.followNeeded(), "the child stopped sending")
    }

    @Test
    fun theFollowUpStopsWhenTheChildIsBackInAZone() = runBlocking<Unit> {
        family()
        consent()
        assertTrue(host(kid).record(emptyList(), point))
        kid.now += 60_000
        val zone = "05".repeat(16)
        assertTrue(host(kid).record(listOf(ZoneEvent(zone, entered = true, atMs = kid.now)), Report.InZone(zone, kid.now)))
        mom.now = kid.now
        mom.store().applyBatch(kid.store().entriesAfter(kid.keys.deviceId, 0, 1_000))
        assertEquals(false, host(mom).followNeeded())
    }

    /** Rulings L6/L8: the engine reads zones, signs and asks for an upload while a cloud run holds runLock. */
    @Test
    fun theHostNeverWaitsForACloudRun() = runBlocking<Unit> {
        family()
        consent()
        val h = host(kid)
        kid.cloudSecrets.runLock.lock()
        try {
            withTimeout(10_000) {
                assertEquals(emptyList(), h.zones())
                assertTrue(h.record(emptyList(), Report.Point(Fix(1, 2, 10, kid.now, Location.Provider.GPS))))
            }
            // The upload's only suspending step is CloudWork's isOn check (CloudRunner.isOn), then an enqueue.
            val enqueued = AtomicInteger()
            withTimeout(10_000) { CloudWork.enqueueIfOn(kid.scope, kid.runner(InMemoryDisk())::isOn) { enqueued.incrementAndGet() }.join() }
            assertEquals(1, enqueued.get(), "the upload is enqueued while a cloud run holds runLock")
            assertEquals(androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, CloudWork.UPLOAD_POLICY)
            h.upload()
            assertEquals(1, uploads.get())
        } finally {
            kid.cloudSecrets.runLock.unlock()
        }
    }

    @Test
    fun anOldConsentTracksNothingUntilTheChildAgreesAgain() = runBlocking<Unit> {
        family()
        kid.settings.recordConsent(kid.now, version = UsageSettings.LEGACY_CONSENT_VERSION)
        kid.pipeline.setSharing(true)
        assertNull(host(kid).zones(), "the Bluetooth-only text never mentioned the location")
        kid.settings.upgradeConsent()
        assertEquals(emptyList(), host(kid).zones())
    }

    @Test
    fun theWatchGateIsTheTrackingGateWithoutThePermission() = runBlocking<Unit> {
        family()
        assertEquals(GateState.Closed, host(kid).watchGate(), "no consent yet")
        consent()
        assertEquals(GateState.Open(kid.now), host(kid).watchGate())
        assertEquals(GateState.Closed, host(mom).watchGate(), "a parent is never watched")
        kid.identityBroken = IllegalStateException("keystore")
        assertEquals(GateState.Unknown, host(kid).watchGate(), "a passing failure changes nothing")
        kid.identityBroken = null
        kid.pipeline.setSharing(false)
        assertEquals(GateState.Closed, host(kid).watchGate())
    }
}
