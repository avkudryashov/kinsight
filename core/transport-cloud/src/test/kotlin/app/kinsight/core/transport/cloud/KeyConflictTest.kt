package app.kinsight.core.transport.cloud

import app.kinsight.core.model.Membership
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KeyConflictTest {
    private val disk = InMemoryDisk()

    @Test
    fun concurrentRotationsConvergeOnTheWinnersKey() = runTest {
        val (mom, dad, grandpa) = family(DeviceRole.PARENT, DeviceRole.PARENT)
        val phones = listOf(mom, dad, grandpa)
        val grant = mom.author.grantAdminEntry(dad.id)
        mom.publish(grant)
        dad.store.storeMembership(listOf(grant)) // Dad learns he is an admin (over Bluetooth in the app)
        phones.forEachIndexed { i, p -> p.publish(p.author.summary("2026-10-0${i + 1}", 1)) }
        // Both admins create generation 1 before either sees the other's op.
        mom.publish(GroupKey.rotate(mom.author, mom.membership()))
        dad.publish(GroupKey.rotate(dad.author, dad.membership()))
        repeat(3) { phones.forEach { it.sync(disk).run() } }
        phones.forEachIndexed { i, p -> p.publish(p.author.summary("2026-10-1${i + 1}", 2)) }
        repeat(3) { phones.forEach { it.sync(disk).run() } }

        val union = Membership.compute(mom.store.groupId, phones.flatMap { it.store.membershipEntries() })!!
        val (epoch, winner) = GroupKey.open(mom.keys, union)!!
        assertEquals(1, epoch)
        for (p in phones) {
            assertContentEquals(winner, p.ring.keys()[1], "${p.name} holds the winner's generation 1")
            assertTrue(p.cursor.cursor.deferred.isEmpty(), "${p.name} has packs left unread")
            assertTrue(p.cursor.cursor.seen.values.none { it == -1 }, "${p.name} rejected a pack")
            for ((path, bytes) in disk.files(CloudPaths.deviceDir(p.store.groupId, p.id))) {
                assertIs<PackOutcome.Opened>(PackCodec.open(bytes, mapOf(1 to winner), p.store.groupId, p.id, union), path)
            }
            for (other in phones - p) {
                assertEquals(other.store.versionVector()[other.id], p.store.versionVector()[other.id], "${p.name} reads ${other.name}")
            }
        }
    }
}
