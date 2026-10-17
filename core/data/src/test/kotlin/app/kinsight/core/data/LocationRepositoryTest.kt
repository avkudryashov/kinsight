package app.kinsight.core.data

import app.kinsight.core.model.locationEntry
import app.kinsight.core.model.proto.Location
import app.kinsight.core.sync.SafeZoneRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationRepositoryTest {
    private val mom = TestDevice()
    private val dad = TestDevice()
    private val kid = TestDevice()
    private val today = LocalDate.of(2026, 10, 16)
    private val noon = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @AfterTest
    fun cleanUp() {
        mom.close()
        dad.close()
        kid.close()
    }

    private fun repo(d: TestDevice) = LocationRepository(d.snapshots, d.appendOwn, date = { today }, clock = { noon }, zone = { ZoneId.of("UTC") })

    private suspend fun <T> Flow<T>.firstWithin(p: (T) -> Boolean): T = withTimeout(10_000) { first(p) }

    @Test
    fun aParentAddsEditsAndDeletesAZone() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val r = repo(mom)
        assertEquals(ZoneSave.SAVED, r.save(ZoneDraft(null, " Home ", 404_168_000, -37_038_000, 150)))
        val home = r.zones.firstWithin { it.isNotEmpty() }.single()
        assertEquals("Home", home.name)
        assertEquals(ZoneSave.UNCHANGED, r.save(ZoneDraft(home.id, "Home", 404_168_000, -37_038_000, 150)))
        assertEquals(ZoneSave.SAVED, r.save(ZoneDraft(home.id, "Home", 404_168_000, -37_038_000, 300)))
        assertEquals(300, r.zones.firstWithin { it.singleOrNull()?.radiusM == 300 }.single().radiusM)
        assertEquals(ZoneSave.SAVED, r.delete(home.id))
        assertEquals(emptyList(), r.zones.firstWithin { it.isEmpty() })
        assertEquals(ZoneSave.UNCHANGED, r.delete(home.id))
    }

    @Test
    fun anotherParentsEditWinsByRank() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dad.joinAsAdminOf(mom)
        assertEquals(ZoneSave.SAVED, repo(mom).save(ZoneDraft(null, "School", 404_268_000, -37_038_000, 200)))
        dad.store().applyBatch(mom.store().safeZones())
        val school = repo(dad).zones.firstWithin { it.isNotEmpty() }.single()
        assertEquals(ZoneSave.SAVED, repo(dad).save(ZoneDraft(school.id, "School", 404_268_000, -37_038_000, 500)))
        mom.store().applyBatch(dad.store().safeZones())
        assertEquals(500, repo(mom).zones.firstWithin { it.singleOrNull()?.radiusM == 500 }.single().radiusM)
    }

    @Test
    fun aChildAndAnInvalidZoneSignNothing() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        assertEquals(ZoneSave.NOT_ALLOWED, repo(kid).save(ZoneDraft(null, "Home", 1, 2, 150)))
        assertEquals(false, repo(kid).canEdit.firstWithin { true })
        assertEquals(ZoneSave.INVALID, repo(mom).save(ZoneDraft(null, "Home", 1, 2, 10)))
        assertEquals(ZoneSave.INVALID, repo(mom).save(ZoneDraft(null, "  ", 1, 2, 150)))
        assertEquals(ZoneSave.INVALID, repo(mom).save(ZoneDraft(null, "Home", 910_000_000, 2, 150)))
        assertTrue(mom.store().safeZones().isEmpty())
    }

    @Test
    fun theChildFlowAndTheLastPointsCarryTheRoute() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        val gid = mom.groups.read()!!.groupId
        val author = app.kinsight.core.model.EntryAuthor(gid, kid.keys, lastSeq = 0)
        mom.store().applyBatch(listOf(author.locationEntry(404_000_000, -37_000_000, 20, noon - 600_000, Location.Provider.GPS)))
        val c = repo(mom).child(kid.keys.deviceId).firstWithin { it?.route?.isNotEmpty() == true }!!
        assertEquals(404_000_000, c.last!!.latE7)
        assertEquals(listOf("Kid"), repo(mom).lastPoints.firstWithin { it.isNotEmpty() }.map { it.first.name })
    }

    @Test
    fun aChildPhoneReadsNoChildLocation() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        val gid = mom.groups.read()!!.groupId
        val author = app.kinsight.core.model.EntryAuthor(gid, kid.keys, lastSeq = 0)
        val entry = author.locationEntry(404_000_000, -37_000_000, 20, noon - 600_000, Location.Provider.GPS)
        kid.store().applyBatch(listOf(entry))
        assertEquals(null, repo(kid).child(kid.keys.deviceId).firstWithin { true })
        assertEquals(emptyList(), repo(kid).lastPoints.firstWithin { true })
    }

    /** Y4 parked minors: the repository's own gates and bounds. */
    @Test
    fun onlyAnAdminParentSignsAndTheBoundsHold() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dad.joinGroupOf(mom, app.kinsight.core.model.proto.DeviceRole.PARENT, "Dad") // a parent, not an admin
        assertEquals(ZoneSave.NOT_ALLOWED, repo(dad).save(ZoneDraft(null, "Home", 1, 2, 150)))
        assertEquals(false, repo(dad).canEdit.firstWithin { true })
        assertTrue(dad.store().safeZones().isEmpty())
        val r = repo(mom)
        assertEquals(ZoneSave.INVALID, r.save(ZoneDraft(null, "A", 1, 2, SafeZoneRules.MIN_RADIUS_M - 1)))
        assertEquals(ZoneSave.SAVED, r.save(ZoneDraft(null, "B", 1, 2, SafeZoneRules.MIN_RADIUS_M)))
        assertEquals(ZoneSave.SAVED, r.save(ZoneDraft(null, "C", 1, 2, SafeZoneRules.MAX_RADIUS_M)))
        assertEquals(ZoneSave.INVALID, r.save(ZoneDraft(null, "D", 1, 2, SafeZoneRules.MAX_RADIUS_M + 1)))
        assertEquals(ZoneSave.SAVED, r.save(ZoneDraft(null, "я".repeat(64), 1, 2, 150)))
        assertEquals(ZoneSave.INVALID, r.save(ZoneDraft(null, "я".repeat(65), 1, 2, 150)))
        assertEquals(ZoneSave.INVALID, r.save(ZoneDraft("not-hex", "E", 1, 2, 150)))
        assertEquals(3, mom.store().safeZones().size)
    }

    /** L3: a child's phone never shows a sibling's route or place, even when it holds the sibling's points. */
    @Test
    fun aChildPhoneReadsNoSiblingRoute() = runBlocking<Unit> {
        val sister = TestDevice()
        try {
            mom.group.createGroup("Mom")
            kid.joinGroupOf(mom)
            sister.joinGroupOf(mom, name = "Sister")
            val gid = mom.groups.read()!!.groupId
            val author = app.kinsight.core.model.EntryAuthor(gid, kid.keys, lastSeq = 0)
            sister.store().applyBatch(listOf(author.locationEntry(404_000_000, -37_000_000, 20, noon - 600_000, Location.Provider.GPS)))
            assertEquals(null, repo(sister).child(kid.keys.deviceId).firstWithin { true })
            assertEquals(emptyList(), repo(sister).lastPoints.firstWithin { true })
        } finally {
            sister.close()
        }
    }

    /** Y4 parked minor: the rank is read inside the append, so a version that arrived meanwhile is outranked. */
    @Test
    fun theRankIsReadInsideTheAppend() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dad.joinAsAdminOf(mom)
        assertEquals(ZoneSave.SAVED, repo(mom).save(ZoneDraft(null, "School", 404_268_000, -37_038_000, 200)))
        dad.store().applyBatch(mom.store().safeZones())
        val school = repo(dad).zones.firstWithin { it.isNotEmpty() }.single()
        assertEquals(ZoneSave.SAVED, repo(dad).save(ZoneDraft(school.id, "School", 404_268_000, -37_038_000, 500))) // rank 2
        // Dad's edit reaches Mom between her screen's read and her append: it must not tie with hers.
        val late: OwnAppend = { build ->
            mom.store().applyBatch(dad.store().safeZones())
            mom.appendOwn(build)
        }
        val r = LocationRepository(mom.snapshots, late, date = { today }, clock = { noon }, zone = { ZoneId.of("UTC") })
        assertEquals(ZoneSave.SAVED, r.save(ZoneDraft(school.id, "School", 404_268_000, -37_038_000, 300)))
        val ranks = mom.store().safeZones().filter { it.author == mom.keys.deviceId }.map { it.body.safe_zone!!.rank }
        assertEquals(3L, ranks.max(), "above Dad's rank 2")
        assertEquals(300, repo(mom).zones.firstWithin { it.singleOrNull()?.radiusM == 300 }.single().radiusM)
    }

    /** Sweep review Minor 4: a zone write reads only the membership and the zones under the pipeline lock. */
    @Test
    fun aZoneWriteReadsOnlyWhatTheRankNeeds() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        var reads = 0
        val r = LocationRepository(
            mom.snapshots, mom.appendOwn, date = { today }, clock = { noon }, zone = { ZoneId.of("UTC") },
            zoneState = { reads++; mom.snapshots.zoneWriteState() },
        )
        assertEquals(ZoneSave.SAVED, r.save(ZoneDraft(null, "Home", 404_168_000, -37_038_000, 150)))
        val home = r.zones.firstWithin { it.isNotEmpty() }.single()
        assertEquals(ZoneSave.UNCHANGED, r.save(ZoneDraft(home.id, "Home", 404_168_000, -37_038_000, 150)))
        assertEquals(ZoneSave.SAVED, r.delete(home.id))
        assertEquals(3, reads)
        val st = mom.snapshots.zoneWriteState()!!
        assertEquals(mom.keys.deviceId, st.self)
        assertEquals(true, st.safeZones.single().body.safe_zone!!.deleted, "the tombstone supersedes the zone")
    }
}
