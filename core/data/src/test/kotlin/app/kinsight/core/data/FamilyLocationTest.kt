package app.kinsight.core.data

import app.kinsight.core.location.Fix
import app.kinsight.core.location.Report
import app.kinsight.core.location.Zone
import app.kinsight.core.location.ZoneEvent
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.proto.Location
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.safeZoneEntry
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

    private fun host(d: TestDevice) =
        FamilyLocation(d.db, d.groups, d.keyProvider, d.settings, d.appendOwn, d.snapshots, upload = { uploads.incrementAndGet() }, clock = { d.now })

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

    @Test
    fun theParentFollowsWhileTheChildIsOutside() = runBlocking<Unit> {
        family()
        consent()
        assertEquals(false, host(mom).followNeeded())
        host(kid).record(emptyList(), Report.Point(Fix(1, 2, 10, kid.now, Location.Provider.GPS)))
        mom.store().applyBatch(kid.store().locations())
        assertEquals(true, host(mom).followNeeded())
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
            h.upload()
            assertEquals(1, uploads.get(), "upload only hands the request on (CloudWork enqueues a run)")
        } finally {
            kid.cloudSecrets.runLock.unlock()
        }
    }
}
