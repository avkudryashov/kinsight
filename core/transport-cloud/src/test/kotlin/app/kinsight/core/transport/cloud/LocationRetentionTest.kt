package app.kinsight.core.transport.cloud

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.locationEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.Location
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spec §8 «Записи LOCATION старше … 48 ч удаляются» on the Disk too (Y4 final review Important 4): a child re-seals each
 * own pack once every LOCATION in it is past the retention, without them, and only once.
 */
class LocationRetentionTest {
    private val disk = InMemoryDisk()
    private val hour = 3_600_000L
    private val retention = SyncPolicy.LOCATION_RETENTION_MS

    // The receivers' SyncPolicy reads the real clock: the scenario ends at the real now.
    private val t0 = System.currentTimeMillis() - 49 * hour

    private suspend fun Phone.point(at: Long) = publish(author.locationEntry(404_000_000, -37_000_000, 20, at, Location.Provider.GPS))

    /** Fix times of the LOCATION entries in [kid]'s packs on the Disk. */
    private suspend fun fixesOnDisk(kid: Phone): List<Long> {
        val dir = CloudPaths.deviceDir(kid.store.groupId, kid.id)
        return disk.files(dir).flatMap { (path, bytes) ->
            val opened = PackCodec.open(bytes, kid.ring.keys(), kid.store.groupId, kid.id, kid.membership(), CloudPaths.parsePack(path.substringAfterLast('/')))
            assertIs<PackOutcome.Opened>(opened, path)
            opened.pack.entries.map(DecodedEntry::decode).mapNotNull { it.body.location?.fix_time_ms }
        }
    }

    @Test
    fun aFrozenPackLosesItsExpiredPointsOnceAndTheParentStillSyncs() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        var now = t0
        // smallPackBytes = 1: no daily merge ever takes these packs (a child's big packs, frozen until now).
        suspend fun run() = kid.sync(disk, { now }, smallPackBytes = 1).run()
        kid.publish(kid.author.summary("2026-10-01", 1))
        kid.point(t0)
        assertIs<CloudResult.Synced>(run())
        now = t0 + 24 * hour
        kid.point(now)
        kid.publish(kid.author.summary("2026-10-02", 1))
        assertIs<CloudResult.Synced>(run())
        now = t0 + 48 * hour
        kid.point(now)
        assertIs<CloudResult.Synced>(run())
        assertTrue(fixesOnDisk(kid).contains(t0), "still within 48 h + margin")
        now = t0 + 49 * hour
        assertIs<CloudResult.Synced>(run())
        val fixes = fixesOnDisk(kid)
        assertTrue(fixes.all { it >= now - retention }, "no point older than 48 h on the Disk")
        assertEquals(listOf(t0 + 24 * hour, t0 + 48 * hour), fixes.sorted())
        val dir = CloudPaths.deviceDir(kid.store.groupId, kid.id)
        val cleaned = disk.files(dir).getValue("$dir/${CloudPaths.packName(1, 2)}")
        // Rewritten once: later runs leave the location-clean pack alone.
        now = t0 + 60 * hour
        assertIs<CloudResult.Synced>(run())
        assertContentEquals(cleaned, disk.files(dir).getValue("$dir/${CloudPaths.packName(1, 2)}"))
        assertTrue(CloudPaths.packName(1, 2) !in kid.cursor.cursor.locationDue)
        // A parent reading the rewritten packs for the first time: synced, the kid's range complete, no gap.
        assertIs<CloudResult.Synced>(mom.sync(disk, { now }).run())
        assertEquals(kid.store.versionVector()[kid.id], mom.store.versionVector()[kid.id])
        assertTrue(mom.cursor.cursor.locationDue.isEmpty(), "a parent's packs carry no LOCATION")
    }

    @Test
    fun theDailyMergeDropsExpiredPointsAndKeepsEachPackShortInTime() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        var now = t0
        suspend fun run() = kid.sync(disk, { now }).run()
        for (h in listOf(0L, 10L, 20L)) {
            now = t0 + h * hour
            kid.point(now)
            assertIs<CloudResult.Synced>(run())
        }
        now = t0 + 25 * hour // the daily merge of the three small packs
        kid.publish(kid.author.summary("2026-10-02", 1))
        assertIs<CloudResult.Synced>(run())
        assertEquals(listOf(t0, t0 + 10 * hour, t0 + 20 * hour), fixesOnDisk(kid).sorted())
        now = t0 + 49 * hour
        assertIs<CloudResult.Synced>(run())
        val fixes = fixesOnDisk(kid)
        assertTrue(fixes.all { it >= now - retention }, "no point older than 48 h on the Disk: $fixes")
        assertEquals(listOf(t0 + 10 * hour, t0 + 20 * hour), fixes.sorted())
        assertIs<CloudResult.Synced>(mom.sync(disk, { now }).run())
        assertEquals(kid.store.versionVector()[kid.id], mom.store.versionVector()[kid.id])
    }

    /** A full Disk keeps the pack due: the next run with room cleans it. */
    @Test
    fun aFullDiskKeepsThePackDueForTheNextRun() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        var full = false
        val guarded = object : DiskApi by disk {
            override suspend fun upload(path: String, bytes: ByteArray) {
                if (full) throw DiskException.StorageFull()
                disk.upload(path, bytes)
            }
        }
        var now = t0
        suspend fun run() = kid.sync(guarded, { now }, smallPackBytes = 1).run()
        kid.point(t0)
        assertIs<CloudResult.Synced>(run())
        now = t0 + 49 * hour
        full = true
        assertIs<CloudResult.StorageFull>(run())
        assertEquals(listOf(t0), fixesOnDisk(kid))
        assertTrue(CloudPaths.packName(1, 1) in kid.cursor.cursor.locationDue)
        full = false
        assertIs<CloudResult.Synced>(run())
        assertEquals(emptyList(), fixesOnDisk(kid))
        assertTrue(kid.cursor.cursor.locationDue.isEmpty())
    }
}
