package app.kinsight.core.data

import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.Membership
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.MemoryPipe
import app.kinsight.core.sync.ProtocolVersions
import app.kinsight.core.sync.SecureChannel
import app.kinsight.core.sync.SessionResult
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.SyncSession
import app.kinsight.core.transport.ble.SyncPolicies
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Review C2: a parent's category override written before protocol 4 never went to a child, and the child completed the
 * parent's log over it. The parent's one-shot re-sign at protocol 4 puts it above the child's position, so it arrives.
 */
class CategoryOverrideResignTest {
    private val mom = TestDevice()
    private val dad = TestDevice()
    private val kid = TestDevice()

    @AfterTest
    fun cleanUp() {
        mom.close()
        dad.close()
        kid.close()
    }

    /** One Bluetooth session [from] → kid; [old] plays the protocol 3 builds on both phones (their three share kinds). */
    private suspend fun session(from: TestDevice, old: Boolean) = coroutineScope {
        suspend fun of(d: TestDevice): SyncSession {
            val m = Membership.compute(d.groups.read()!!.groupId, d.store().membershipEntries())
            val p = SyncPolicies.forDevice(m, d.keys.deviceId, d.settings.current())
            return if (old) SyncSession(d.store(), p.copy(shareKinds = OLD_SHARE), versions = 2..3) else SyncSession(d.store(), p, ProtocolVersions.SUPPORTED)
        }
        val (sa, sb) = MemoryPipe.pair()
        val prologue = SecureChannel.prologue(kid.groups.read()!!.groupId)
        val a = of(from)
        val b = of(kid)
        val ra = async { a.run(SecureChannel.initiate(sa, from.keys.dh, prologue)) }
        val rb = async { b.run(SecureChannel.accept(sb, kid.keys.dh, prologue)) }
        assertIs<SessionResult.Completed>(ra.await())
        assertIs<SessionResult.Completed>(rb.await())
    }

    private suspend fun TestDevice.effective() = CategoryOverrides.effective(snapshots.load(emptyMap()) as GroupSnapshot.Ready)

    @Test
    fun anOverrideAnOldChildCompletedOverReachesItAfterTheParentsResign() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dad.joinGroupOf(mom, DeviceRole.PARENT, "Dad")
        kid.joinGroupOf(mom)
        mom.usage.setCategory("com.example.chat", AppCategory.GAMES)
        mom.usage.setCategory("com.example.video", AppCategory.GAMES)
        mom.usage.setCategory("com.example.video", null) // a deliberate clear is re-signed too
        mom.ownSummary("2026-10-23", 60_000) // a later seq the child never takes
        session(mom, old = true)
        assertEquals(mom.store().versionVector()[mom.keys.deviceId], kid.store().versionVector()[mom.keys.deviceId], "completed over the override")
        assertEquals(emptyMap(), kid.effective())

        // Both phones update: without the re-sign nothing new is above the child's position (the reviewer's probe).
        session(mom, old = false)
        assertEquals(emptyMap(), kid.effective(), "the bug: the parent's override never arrives")

        val before = mom.effective()
        mom.usage.resignCategoryOverrides()
        assertEquals(before, mom.effective(), "the re-sign changes nothing for the parents")
        assertTrue(mom.prefs.categoryOverridesResigned())
        session(mom, old = false)
        assertEquals(mapOf("com.example.chat" to AppCategory.GAMES), kid.effective())
        assertEquals(mom.effective(), kid.effective())

        // Once only: a second start signs nothing.
        val seq = mom.store().authorState(mom.keys.deviceId).lastSeq
        mom.usage.resignCategoryOverrides()
        assertEquals(seq, mom.store().authorState(mom.keys.deviceId).lastSeq)
    }

    @Test
    fun onlyTheParentsOwnWinnersAndNeverOnAChildsPhone() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dad.joinGroupOf(mom, DeviceRole.PARENT, "Dad")
        kid.joinGroupOf(mom)
        dad.store().storeMembership(mom.store().membershipEntries())
        mom.usage.setCategory("com.example.chat", AppCategory.GAMES)
        // Dad learns mom's override and overrides it; mom's entry is no longer a winner, so she re-signs nothing.
        dad.store().applyBatch(mom.store().categoryOverrides())
        dad.usage.setCategory("com.example.chat", AppCategory.SOCIAL)
        mom.store().applyBatch(dad.store().categoryOverrides().filter { it.author == dad.keys.deviceId })
        val momSeq = mom.store().authorState(mom.keys.deviceId).lastSeq
        mom.usage.resignCategoryOverrides()
        assertEquals(momSeq, mom.store().authorState(mom.keys.deviceId).lastSeq, "not her winner")
        assertTrue(mom.prefs.categoryOverridesResigned())
        val kidSeq = kid.store().authorState(kid.keys.deviceId).lastSeq
        kid.usage.resignCategoryOverrides()
        assertEquals(kidSeq, kid.store().authorState(kid.keys.deviceId).lastSeq)
        assertTrue(kid.prefs.categoryOverridesResigned(), "a child has nothing to re-sign: done for good")
    }

    /** Re-review: the re-sign keeps the winner's own rank, so another parent's newer choice made out of contact still wins. */
    @Test
    fun theResignKeepsItsRankSoANewerChoiceOfAnotherParentStillWins() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dad.joinGroupOf(mom, DeviceRole.PARENT, "Dad")
        kid.joinGroupOf(mom)
        dad.store().storeMembership(mom.store().membershipEntries())
        mom.usage.setCategory("com.example.chat", AppCategory.GAMES)
        val momsRank = mom.store().categoryOverrides().single().body.category_override!!.rank
        // Dad (updated, out of contact) changes the package over mom's winner: rank R + 1.
        dad.store().applyBatch(mom.store().categoryOverrides())
        dad.usage.setCategory("com.example.chat", AppCategory.SOCIAL)
        val dads = dad.store().categoryOverrides().single { it.author == dad.keys.deviceId }
        assertEquals(momsRank + 1, dads.body.category_override!!.rank)

        // Mom updates before she hears of it: her one-shot re-signs her winner at its own rank R.
        mom.usage.resignCategoryOverrides()
        val resigned = mom.store().categoryOverrides().single { it.author == mom.keys.deviceId }
        assertEquals(momsRank, resigned.body.category_override!!.rank)
        assertEquals(CategoryOverrides.wire(AppCategory.GAMES), resigned.body.category_override!!.category)

        // Both parents meet: dad's deliberate newer choice wins on both phones.
        mom.store().applyBatch(listOf(dads))
        dad.store().applyBatch(listOf(resigned))
        assertEquals(mapOf("com.example.chat" to AppCategory.SOCIAL), mom.effective())
        assertEquals(mapOf("com.example.chat" to AppCategory.SOCIAL), dad.effective())
    }

    private companion object {
        /** FROM_PARENTS_TO_ALL of the protocol 3 builds. */
        val OLD_SHARE = setOf(EntryKind.CLOUD_ACCESS, EntryKind.SAFE_ZONE, EntryKind.PARENT_PIN)
    }
}
