package app.kinsight.core.data

import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.sync.HandedAccess
import app.kinsight.core.transport.cloud.CloudAccessView
import app.kinsight.core.transport.cloud.CloudPaths
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.DiskException
import app.kinsight.core.transport.cloud.DiskItem
import app.kinsight.core.transport.cloud.GroupKey
import app.kinsight.core.transport.cloud.InMemoryDisk
import app.kinsight.core.transport.cloud.OwnerLifecycle
import app.kinsight.core.transport.cloud.StorageMove
import app.kinsight.core.transport.cloud.TokenSet
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudOwnerMoveTest {
    private val mom = TestDevice()
    private val dad = TestDevice()
    private val kid = TestDevice()
    private val momDisk = InMemoryDisk()
    private val dadDisk = InMemoryDisk()
    private val http = FakeOAuthHttp(accessToken = "DAD1")
    private val disks = AccountDisks(mapOf("AT" to momDisk, "DAD1" to dadDisk))

    @AfterTest
    fun cleanUp() {
        mom.close()
        dad.close()
        kid.close()
    }

    private suspend fun membershipOf(d: TestDevice) = Membership.compute(d.groups.read()!!.groupId, d.store().membershipEntries())!!

    /** Mom owns the storage on her Disk (storage id 1s); Dad (admin) and the kid hold her token. */
    private suspend fun family() {
        mom.group.createGroup("Mom")
        dad.joinAsAdminOf(mom)
        kid.joinGroupOf(mom)
        mom.ownStorage(token = "AT")
        StorageMove.writeStorageId(momDisk, mom.groups.read()!!.groupId, ByteArray(16) { 1 })
        dad.learnsStorageFrom(mom)
        kid.learnsStorageFrom(mom)
    }

    /** What Dad's phone gets from Mom's over Bluetooth or the Disk: her membership and her handover request. */
    private suspend fun dadHearsFromMom() {
        dad.store().storeMembership(mom.store().membershipEntries())
        dad.store().applyBatch(mom.store().liveEntries().filter { it.kind == EntryKind.STORAGE_HANDOVER })
    }

    /** Mom's whole log reaches Dad (a Bluetooth session): Dad accounts her seqs, so his RemoveMember keeps her ops. */
    private suspend fun dadHoldsMomsLog() {
        dad.store().applyBatch(mom.store().entriesAfter(mom.keys.deviceId, 0, 10_000))
    }

    /** Dad removes Mom's lost phone (spec §7.2) after holding her log; her StorageOwner stays in force, departed. */
    private suspend fun dadRemovesMom() {
        dadHoldsMomsLog()
        dad.group.remove(mom.keys.deviceId)
        val m = membershipOf(dad)
        assertTrue(mom.keys.deviceId in m.departed, "Mom departed")
        assertEquals(mom.keys.deviceId, m.storageOwner!!.deviceId, "her storage is still in force")
    }

    private suspend fun takeOver(d: TestDevice, accounts: AccountDisks = disks): CloudEnableResult {
        val s = d.signIn(http, accounts::disk)
        assertNotNull(s.start("Pixel", SignInMode.TAKE_OVER), "${s.state.value}")
        return s.finish("1234567")
    }

    /** Every file under the group's root with its Disk write stamp. */
    private suspend fun stamps(disk: InMemoryDisk, g: GroupId): Map<String, String?> {
        val root = CloudPaths.root(g)
        val out = mutableMapOf<String, String?>()
        for (i in disk.list(root)) {
            if (i.isDir) disk.list("$root/${i.name}").forEach { f -> out["${i.name}/${f.name}"] = f.modified } else out[i.name] = i.modified
        }
        return out
    }

    /** storage.id deleted alone (the Disk UI): a child's or another parent's run never writes it, the owner's puts it back. */
    @Test
    fun onlyTheOwnersRunWritesTheStorageId() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!.groupId
        assertEquals(CloudRun.SYNCED, mom.runner(disks::disk).run(CloudTrigger.NOW))
        momDisk.delete(CloudPaths.storageIdFile(g))
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertEquals(CloudRun.SYNCED, dad.runner(disks::disk).run(CloudTrigger.NOW))
        assertTrue(momDisk.files(CloudPaths.storageIdFile(g)).isEmpty())
        assertEquals(CloudRun.SYNCED, mom.runner(disks::disk).run(CloudTrigger.NOW))
        assertContentEquals(ByteArray(16) { 1 }, StorageMove.storageIdOf(momDisk, g))
    }

    @Test
    fun aChildOnTheOldDiskFollowsTheMove() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!.groupId
        val day = kid.ownSummary("2026-10-13", 60_000)
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertEquals(CloudRun.SYNCED, mom.runner(disks::disk).run(CloudTrigger.NOW))
        assertTrue(mom.signIn(http, disks::disk).requestHandover(dad.keys.deviceId))
        dadHearsFromMom()
        assertEquals(CloudEnableResult.TAKEN_OVER, takeOver(dad))
        val owner = membershipOf(dad).storageOwner!!
        assertEquals(dad.keys.deviceId to 2L, owner.deviceId to owner.generation)
        assertEquals(16, owner.tokenId.size, "a fresh sign-in names a new token")
        assertContentEquals(owner.storageId.toByteArray(), StorageMove.storageIdOf(dadDisk, g))
        assertFalse(owner.storageId.toByteArray().contentEquals(ByteArray(16) { 1 }), "another Disk: a new storage id")
        assertContentEquals(ByteArray(16) { 1 }, StorageMove.storageIdOf(momDisk, g), "the old storage keeps its id")
        assertTrue(dadDisk.files(CloudPaths.deviceDir(g, kid.keys.deviceId)).isNotEmpty(), "the kid's packs were copied")
        assertEquals(listOf("AT"), dad.cloudSecrets.retired(g).map { it.accessToken })
        assertEquals(dad.now, dad.cloudState.current().tokenSinceMs, "a new token starts its age")
        val atDad = CloudViews.status(dad.snapshots.load(emptyMap()), CloudState(), available = true)
        assertTrue(atDad.selfIsOwner && atDad.handoverFrom == null && !atDad.canTakeOver, "the request of generation 1 is spent")
        // The kid has not heard of the move: its next run reads the «переезд» from Mom's Disk, the one after goes to Dad's.
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertEquals(dad.keys.deviceId, membershipOf(kid).storageOwner!!.deviceId)
        assertNull(kid.cloudSecrets.tokens(), "the old token goes once the move was read")
        assertTrue(kid.cloudSecrets.retired(g).isEmpty(), "a child never keeps a token for the revoke")
        val later = kid.ownSummary("2026-10-14", 30_000)
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertEquals(CloudRun.SYNCED, dad.runner(disks::disk).run(CloudTrigger.NOW))
        val entriesAtDad = dad.store().liveEntries()
        assertTrue(day in entriesAtDad && later in entriesAtDad)
    }

    @Test
    fun aChildThatHeardTheMoveOverBluetoothReadsItWithItsOldToken() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertTrue(mom.signIn(http, disks::disk).requestHandover(dad.keys.deviceId))
        dadHearsFromMom()
        assertEquals(CloudEnableResult.TAKEN_OVER, takeOver(dad))
        // Bluetooth gives a child the membership only: the new owner, no CloudAccess it could open.
        kid.store().storeMembership(dad.store().membershipEntries())
        val m = membershipOf(kid)
        assertEquals(dad.keys.deviceId to 2L, m.storageOwner!!.let { it.deviceId to it.generation })
        assertNull(CloudAccessView.current(kid.store(), m, kid.cloudSecrets.ring(g).keys()))
        // Its kept token reads Mom's Disk, where the «переезд» waits; the Heartbeat still says generation 1.
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertNull(kid.cloudSecrets.tokens(), "the old token goes once the move was read")
        assertEquals(1L, kid.cloudState.current().syncedGeneration)
        val day = kid.ownSummary("2026-10-14", 30_000)
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertEquals(2L, kid.cloudState.current().syncedGeneration)
        assertEquals(CloudRun.SYNCED, dad.runner(disks::disk).run(CloudTrigger.NOW))
        assertTrue(day in dad.store().liveEntries(), "the kid reached Dad's Disk")
    }

    /** Parked Y3 minor: a kept old token the old Disk refuses is not tried on every run, only after something changed. */
    @Test
    fun aChildStopsCallingTheOldDiskOnceItsKeptTokenIsDead() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertTrue(mom.signIn(http, disks::disk).requestHandover(dad.keys.deviceId))
        dadHearsFromMom()
        assertEquals(CloudEnableResult.TAKEN_OVER, takeOver(dad))
        kid.store().storeMembership(dad.store().membershipEntries()) // the new owner over Bluetooth, no access it opens
        var calls = 0
        val revoked = AccountDisks(mapOf("DAD1" to dadDisk)) // AT was revoked: Mom's Disk answers 401
        val counting: (suspend () -> String) -> DiskApi = { t ->
            val d = revoked.disk(t)
            object : DiskApi by d {
                override suspend fun list(path: String): List<DiskItem> {
                    calls++
                    return d.list(path)
                }
            }
        }
        val runner = kid.runner(counting)
        assertEquals(CloudRun.UNAUTHORIZED, runner.run(CloudTrigger.NOW))
        assertTrue(calls > 0)
        assertEquals(CloudProblem.NO_ACCESS, kid.cloudState.current().problem)
        val after = calls
        repeat(2) { assertEquals(CloudRun.UNAUTHORIZED, runner.run(CloudTrigger.NOW)) }
        assertEquals(after, calls, "the dead token is not tried again")
        assertEquals(CloudProblem.NO_ACCESS, kid.cloudState.current().problem)
        // Dad's CloudAccess reaches the kid (Bluetooth): the new storage opens, the run goes to Dad's Disk.
        kid.store().applyBatch(dad.store().entriesAfter(dad.keys.deviceId, 0, 10_000))
        assertNotNull(CloudAccessView.current(kid.store(), membershipOf(kid), kid.cloudSecrets.ring(g).keys()))
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
    }

    @Test
    fun aChildFollowsARenewalItHeardOfOverBluetooth() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!
        mom.group.remove(dad.keys.deviceId)
        val both = AccountDisks(mapOf("AT" to momDisk, "AT2" to momDisk))
        val s = mom.signIn(FakeOAuthHttp(accessToken = "AT2"), both::disk)
        assertNotNull(s.start("Pixel"))
        assertEquals(CloudEnableResult.RESTORED, s.finish("1234567"))
        assertEquals(CloudRun.SYNCED, mom.runner(both::disk).run(CloudTrigger.NOW)) // the renewed CloudAccess reaches the Disk
        kid.store().storeMembership(mom.store().membershipEntries())
        assertEquals(2L, membershipOf(kid).storageOwner!!.generation)
        assertEquals(CloudRun.SYNCED, kid.runner(both::disk).run(CloudTrigger.NOW))
        assertNull(kid.cloudSecrets.tokens())
        assertEquals("AT2", CloudAccessView.current(kid.store(), membershipOf(kid), kid.cloudSecrets.ring(g).keys())!!.accessToken)
        assertEquals(CloudRun.SYNCED, kid.runner(both::disk).run(CloudTrigger.NOW))
        assertEquals(2L, kid.cloudState.current().syncedGeneration)
    }

    @Test
    fun anOldDiskOutOfReachOrANewDiskRefusingSignsNothing() = runBlocking<Unit> {
        family()
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertTrue(mom.signIn(http, disks::disk).requestHandover(dad.keys.deviceId))
        dadHearsFromMom()
        val before = dad.store().liveEntries().size
        val offline = object : DiskApi by momDisk {
            override suspend fun list(path: String) = throw java.io.IOException("no route to host")
        }
        assertEquals(CloudEnableResult.NETWORK, takeOver(dad, AccountDisks(mapOf("AT" to offline, "DAD1" to dadDisk))))
        val refusing = object : DiskApi by InMemoryDisk() {
            override suspend fun mkdir(path: String) = throw DiskException.Unauthorized()
        }
        assertEquals(CloudEnableResult.SIGN_IN_FAILED, takeOver(dad, AccountDisks(mapOf("AT" to momDisk, "DAD1" to refusing))))
        assertEquals(mom.keys.deviceId to 1L, membershipOf(dad).storageOwner!!.let { it.deviceId to it.generation })
        assertEquals(before, dad.store().liveEntries().size, "nothing signed")
        assertEquals("AT", dad.cloudSecrets.tokens()!!.accessToken)
    }

    @Test
    fun aKidFollowsTheRemovedOwnersStorageWhileItsTokenStillWorks() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!.groupId
        dadRemovesMom()
        assertEquals(CloudEnableResult.TAKEN_OVER, takeOver(dad)) // "AT" still opens Mom's Disk
        val m = membershipOf(dad)
        val access = dad.store().liveEntries()
            .filter { it.author == dad.keys.deviceId && it.kind == EntryKind.CLOUD_ACCESS }.maxBy { it.seq }
        assertEquals(1, access.body.cloud_access!!.key_epoch, "sealed with the key Mom never got")
        assertFalse(m.discoveryRotation!!.keys.any { it.device_id == mom.keys.deviceId.bytes().toByteString() })
        // The kid knows nothing of the removal, the rotation or the move; the «переезд» pack carries the rotation op.
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertEquals(dad.keys.deviceId to 2L, membershipOf(kid).storageOwner!!.let { it.deviceId to it.generation })
        val day = kid.ownSummary("2026-10-14", 30_000)
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertTrue(dadDisk.files(CloudPaths.deviceDir(g, kid.keys.deviceId)).isNotEmpty(), "the kid uploads to Dad's Disk now")
        assertEquals(CloudRun.SYNCED, dad.runner(disks::disk).run(CloudTrigger.NOW))
        assertTrue(day in dad.store().liveEntries())
    }

    @Test
    fun aTakeOverWithADeadOldTokenRebuildsFromLocalCopies() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!.groupId
        dadRemovesMom() // Mom's phone is gone (spec §7.2)
        val revoked = AccountDisks(mapOf("DAD1" to dadDisk)) // the shared token "AT" opens nothing any more
        assertEquals(CloudEnableResult.TAKEN_OVER, takeOver(dad, revoked))
        val owner = membershipOf(dad).storageOwner!!
        assertEquals(dad.keys.deviceId, owner.deviceId)
        assertContentEquals(owner.storageId.toByteArray(), StorageMove.storageIdOf(dadDisk, g))
        assertEquals(CloudRun.SYNCED, dad.runner(revoked::disk).run(CloudTrigger.NOW))
        assertTrue(dadDisk.files(CloudPaths.deviceDir(g, dad.keys.deviceId)).isNotEmpty(), "Dad's own log, from his phone")
    }

    @Test
    fun aNewDiskThatFailsSignsNothing() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!.groupId
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertTrue(mom.signIn(http, disks::disk).requestHandover(dad.keys.deviceId))
        dadHearsFromMom()
        val full = object : DiskApi by dadDisk {
            override suspend fun upload(path: String, bytes: ByteArray) = throw DiskException.StorageFull()
        }
        val before = dad.store().liveEntries().size
        assertEquals(CloudEnableResult.STORAGE_FULL, takeOver(dad, AccountDisks(mapOf("AT" to momDisk, "DAD1" to full))))
        val owner = membershipOf(dad).storageOwner!!
        assertEquals(mom.keys.deviceId to 1L, owner.deviceId to owner.generation)
        assertEquals(before, dad.store().liveEntries().size, "nothing signed")
        assertTrue(dad.cloudSecrets.retired(g).isEmpty())
        assertEquals("AT", dad.cloudSecrets.tokens()!!.accessToken, "the old storage's token stays in use")
    }

    @Test
    fun aMatchingStorageIdOnlyMovesTheRole() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!.groupId
        kid.ownSummary("2026-10-13", 60_000)
        assertEquals(CloudRun.SYNCED, mom.runner(disks::disk).run(CloudTrigger.NOW))
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertEquals(CloudRun.SYNCED, dad.runner(disks::disk).run(CloudTrigger.NOW))
        dadRemovesMom() // the lost phone, removed by the second parent (spec §7.3, §7.4)
        val sameAccount = AccountDisks(mapOf("AT" to momDisk, "DAD1" to momDisk)) // the new phone signs in to the same account
        val before = stamps(momDisk, g)
        val reads = momDisk.downloads.size
        assertTrue(before.keys.any { it.startsWith(kid.keys.deviceId.hex) }, "the storage holds the family's packs")
        assertEquals(CloudEnableResult.TAKEN_OVER, takeOver(dad, sameAccount))
        val owner = membershipOf(dad).storageOwner!!
        assertEquals(dad.keys.deviceId to 2L, owner.deviceId to owner.generation)
        assertContentEquals(ByteArray(16) { 1 }, owner.storageId.toByteArray())
        assertContentEquals(ByteArray(16) { 1 }, StorageMove.storageIdOf(momDisk, g))
        assertEquals(before, stamps(momDisk, g), "nothing copied or rewritten, no move record: the same storage")
        assertEquals(listOf(CloudPaths.storageIdFile(g)), momDisk.downloads.drop(reads).distinct(), "only storage.id was read")
        assertEquals(listOf("AT"), dad.cloudSecrets.retired(g).map { it.accessToken }, "the lost phone's token goes once all switched")
    }

    @Test
    fun aRestoreIntoAnotherAccountMovesTheStorage() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!.groupId
        val first = kid.ownSummary("2026-10-13", 60_000)
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        val otherDisk = InMemoryDisk()
        val accounts = AccountDisks(mapOf("AT" to momDisk, "M2" to otherDisk))
        val s = mom.signIn(FakeOAuthHttp(accessToken = "M2"), accounts::disk)
        assertNotNull(s.start("Pixel"))
        assertEquals(CloudEnableResult.RESTORED, s.finish("1234567"))
        val owner = membershipOf(mom).storageOwner!!
        assertEquals(mom.keys.deviceId to 2L, owner.deviceId to owner.generation)
        assertFalse(owner.storageId.toByteArray().contentEquals(ByteArray(16) { 1 }), "another account: a new storage id")
        assertContentEquals(owner.storageId.toByteArray(), StorageMove.storageIdOf(otherDisk, g))
        assertTrue(otherDisk.files(CloudPaths.deviceDir(g, kid.keys.deviceId)).isNotEmpty(), "the kid's packs were copied")
        assertTrue(momDisk.files(CloudPaths.deviceDir(g, mom.keys.deviceId)).isNotEmpty(), "the «переезд» waits on the old Disk")
        assertEquals(listOf("AT"), mom.cloudSecrets.retired(g).map { it.accessToken })
        // The kid follows: the move from the old Disk, then the new one.
        assertEquals(CloudRun.SYNCED, kid.runner(accounts::disk).run(CloudTrigger.NOW))
        assertEquals(2L, membershipOf(kid).storageOwner!!.generation)
        val day = kid.ownSummary("2026-10-14", 30_000)
        assertEquals(CloudRun.SYNCED, kid.runner(accounts::disk).run(CloudTrigger.NOW))
        assertEquals(CloudRun.SYNCED, mom.runner(accounts::disk).run(CloudTrigger.NOW))
        assertTrue(first in mom.store().liveEntries() && day in mom.store().liveEntries())
    }

    @Test
    fun aRestoreIntoTheSameAccountCopiesNothing() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!.groupId
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk).run(CloudTrigger.NOW))
        assertEquals(CloudRun.SYNCED, mom.runner(disks::disk).run(CloudTrigger.NOW))
        val same = AccountDisks(mapOf("AT" to momDisk, "AT2" to momDisk))
        val before = stamps(momDisk, g)
        val s = mom.signIn(FakeOAuthHttp(accessToken = "AT2"), same::disk)
        assertNotNull(s.start("Pixel"))
        assertEquals(CloudEnableResult.RESTORED, s.finish("1234567"))
        val owner = membershipOf(mom).storageOwner!!
        assertEquals(1L, owner.generation, "the same storage, a new CloudAccess only")
        assertContentEquals(ByteArray(16) { 1 }, owner.storageId.toByteArray())
        assertEquals(before, stamps(momDisk, g), "nothing copied or rewritten")
        assertEquals("AT2", CloudAccessView.current(mom.store(), membershipOf(mom), mom.cloudSecrets.ring(mom.groups.read()!!).keys())!!.accessToken)
    }

    @Test
    fun withoutARequestOrAMissingOwnerNobodyTakesOver() = runBlocking<Unit> {
        family()
        val s = dad.signIn(http, disks::disk)
        assertNull(s.start("Pixel", SignInMode.TAKE_OVER))
        assertEquals(SignInState.Done(CloudEnableResult.NOT_ALLOWED), s.state.value)
        // The owner is still here: heard six days ago on Dad's clock, not yet a week.
        dadHoldsMomsLog()
        dad.runner(disks::disk).noteOwnerHeard()
        dad.now += OwnerLifecycle.UNAVAILABLE_AFTER_MS - 24L * 60 * 60 * 1000
        assertNull(dad.signIn(http, disks::disk).start("Pixel", SignInMode.TAKE_OVER))
        assertFalse(CloudViews.status(dad.snapshots.load(emptyMap()), dad.cloudState.current(), true, nowMs = dad.now).canTakeOver)
        assertFalse(dad.signIn(http, disks::disk).requestHandover(kid.keys.deviceId), "only the owner asks")
        assertFalse(mom.signIn(http, disks::disk).requestHandover(kid.keys.deviceId), "never a child")
    }

    @Test
    fun aRemovalAsksTheOwnerForANewTokenAndTheSignInRenewsIt() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!
        mom.group.remove(kid.keys.deviceId)
        assertEquals(CloudProblem.RENEW_ACCESS, CloudViews.status(mom.snapshots.load(emptyMap()), CloudState(), available = true).problem)
        val renew = FakeOAuthHttp(accessToken = "AT2")
        val s = mom.signIn(renew, AccountDisks(mapOf("AT" to momDisk, "AT2" to momDisk))::disk)
        assertNotNull(s.start("Pixel"))
        assertEquals(CloudEnableResult.RESTORED, s.finish("1234567"))
        val m = membershipOf(mom)
        assertEquals(2L, m.storageOwner!!.generation)
        assertContentEquals(ByteArray(16) { 1 }, m.storageOwner!!.storageId.toByteArray())
        assertTrue(kid.keys.deviceId in m.storageOwner!!.departed, "the new token's record names the removal")
        assertEquals("AT2", CloudAccessView.current(mom.store(), m, mom.cloudSecrets.ring(g).keys())!!.accessToken)
        val newest = mom.store().liveEntries().filter { it.author == mom.keys.deviceId && it.kind == EntryKind.CLOUD_ACCESS }.maxBy { it.seq }
        assertEquals(1, newest.body.cloud_access!!.key_epoch, "sealed with the key the removed phone never got")
        assertFalse(m.discoveryRotation!!.keys.any { it.device_id == kid.keys.deviceId.bytes().toByteString() })
        assertEquals(listOf("AT"), mom.cloudSecrets.retired(g.groupId).map { it.accessToken })
        assertEquals(mom.now, mom.cloudState.current().tokenSinceMs)
        assertNull(CloudViews.status(mom.snapshots.load(emptyMap()), CloudState(), available = true).problem)
    }

    @Test
    fun theOwnersRequestShowsOnTheChosenParentsPhone() = runBlocking<Unit> {
        family()
        assertTrue(mom.signIn(http, disks::disk).requestHandover(dad.keys.deviceId))
        dadHearsFromMom()
        val atDad = CloudViews.status(dad.snapshots.load(emptyMap()), CloudState(), available = true)
        assertEquals("Mom", atDad.handoverFrom)
        assertTrue(atDad.canTakeOver)
        val atMom = CloudViews.status(mom.snapshots.load(emptyMap()), CloudState(), available = true)
        assertEquals("Dad", atMom.handoverPendingTo)
        assertEquals(listOf("Dad"), atMom.handoverTargets.map { it.name })
        assertTrue(atMom.handoverNeeded)
    }

    /** Spec §7.1 «Владелец выбирает родителя», §3 «родитель-администратор»: the request makes a plain parent an admin. */
    @Test
    fun aParentWhoIsNotAnAdminIsMadeOneByTheHandover() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dad.joinGroupOf(mom, DeviceRole.PARENT, "Dad")
        kid.joinGroupOf(mom)
        mom.ownStorage(token = "AT")
        val before = CloudViews.status(mom.snapshots.load(emptyMap()), CloudState(), available = true)
        assertEquals(listOf("Dad"), before.handoverTargets.map { it.name }, "a parent, admin or not; never the child")
        assertTrue(before.handoverNeeded)
        assertFalse(mom.signIn(http, disks::disk).requestHandover(kid.keys.deviceId))
        assertTrue(mom.signIn(http, disks::disk).requestHandover(dad.keys.deviceId))
        dadHearsFromMom()
        assertTrue(membershipOf(dad).active.getValue(dad.keys.deviceId).isAdmin)
        val atDad = CloudViews.status(dad.snapshots.load(emptyMap()), CloudState(), available = true)
        assertEquals("Mom", atDad.handoverFrom)
        assertTrue(atDad.canTakeOver)
    }

    @Test
    fun aSilentOwnerIsUnavailableAfterAWeekOfThisPhonesTime() = runBlocking<Unit> {
        family()
        // Mom's clock lags three weeks: her stamp decides nothing, only when this phone saw her log grow (rulings Q14).
        val stamp = mom.now - 21L * 24 * 60 * 60 * 1000
        check(mom.appendOwn { a -> listOf(a.create { it.copy(heartbeat = Heartbeat(app_version = "t", protocol_version = 3), created_at_ms = stamp) }) })
        dadHoldsMomsLog()
        kid.store().applyBatch(mom.store().heartbeats())
        val heard = dad.now
        dad.runner(disks::disk).noteOwnerHeard()
        kid.runner(disks::disk).noteOwnerHeard()
        val atDad = dad.cloudState.current()
        assertEquals(heard, atDad.ownerHeardFrom(mom.keys.deviceId))
        val week = OwnerLifecycle.UNAVAILABLE_AFTER_MS
        assertFalse(CloudViews.status(dad.snapshots.load(emptyMap()), atDad, true, nowMs = heard + week - 1).ownerUnavailable)
        val later = CloudViews.status(dad.snapshots.load(emptyMap()), atDad, true, nowMs = heard + week + 1)
        assertTrue(later.ownerUnavailable && later.canTakeOver)
        assertFalse(CloudViews.status(kid.snapshots.load(emptyMap()), kid.cloudState.current(), true, nowMs = heard + week + 1).canTakeOver, "never a child")
        // The same rule decides the sign-in: Dad may take over once a week passed on his clock.
        val s = dad.signIn(http, disks::disk)
        assertNull(s.start("Pixel", SignInMode.TAKE_OVER))
        dad.now = heard + week + 1
        assertNotNull(dad.signIn(http, disks::disk).start("Pixel", SignInMode.TAKE_OVER))
    }

    /**
     * Final review, Important 3: Mom's phone is lost. Dad removes it and pairs her new phone, which cannot open her old
     * CloudAccess (sealed under a key generation it never got) and signs in afresh. Dad's access came only from the log,
     * never handed: he still revokes the lost phone's token, once every phone switched.
     */
    @Test
    fun aParentWithAccessFromTheLogRevokesTheLostOwnersToken() = runBlocking<Unit> {
        val pixel = TestDevice()
        try {
            mom.group.createGroup("Mom")
            dad.joinAsAdminOf(mom)
            kid.joinGroupOf(mom)
            val g = mom.groups.read()!!
            val year = 365L * 24 * 60 * 60 * 1000
            val before = membershipOf(mom)
            check(mom.appendOwn { a -> listOf(GroupKey.rotate(a, before)) }) // key generation 1
            val storage = ByteArray(16) { 1 }
            assertNotNull(OwnerRecords.claim(mom.appendOwn, mom.store(), mom.keys, mom.cloudSecrets.ring(g), storage, 1, "AT", mom.now + year))
            mom.cloudSecrets.saveOwner(g.groupId, 1, storage, TokenSet("AT", "RT", mom.now + year))
            StorageMove.writeStorageId(momDisk, g.groupId, storage)
            dad.store().storeMembership(mom.store().membershipEntries())
            dadHoldsMomsLog()
            OwnerRecords.writeKey(dad.keys, membershipOf(dad), dad.cloudSecrets.ring(g)) // as a sync absorbs the rotation
            val newDisk = InMemoryDisk()
            val accounts = AccountDisks(mapOf("AT" to momDisk, "NEW" to newDisk))
            val oauth = FakeOAuthHttp(accessToken = "NEW")
            assertEquals(CloudRun.SYNCED, dad.runner(accounts::disk, oauth).run(CloudTrigger.NOW))
            assertNull(dad.cloudSecrets.tokens(), "Dad's access is the log's CloudAccess only")
            dadRemovesMom()
            assertEquals(CloudRun.SYNCED, dad.runner(accounts::disk, oauth).run(CloudTrigger.NOW)) // rotates for the removal
            pixel.joinAsAdminOf(dad, name = "Pixel")
            val s = pixel.signIn(oauth, accounts::disk)
            assertNotNull(s.start("Pixel", SignInMode.TAKE_OVER), "${s.state.value}")
            assertEquals(CloudEnableResult.TAKEN_OVER, s.finish("1234567"))
            assertTrue(pixel.cloudSecrets.retired(g.groupId).isEmpty(), "the new phone could not open the lost phone's access")
            // The new owner's records and its token reach Dad over Bluetooth.
            dad.store().storeMembership(pixel.store().membershipEntries())
            dad.store().applyBatch(pixel.store().entriesAfter(pixel.keys.deviceId, 0, 10_000))
            val owner = membershipOf(dad).storageOwner!!
            assertEquals(pixel.keys.deviceId to 2L, owner.deviceId to owner.generation)
            assertTrue(dad.cloudSecrets.saveHanded(g.groupId, HandedAccess(2, owner.storageId.toByteArray(), "NEW", pixel.now + year), keep = true))
            val revokes = { oauth.urls.count { it.endsWith("/revoke_token") } }
            assertEquals(CloudRun.SYNCED, dad.runner(accounts::disk, oauth).run(CloudTrigger.NOW))
            assertEquals(listOf(1L to "AT"), dad.cloudSecrets.retired(g.groupId).map { it.generation to it.accessToken })
            assertEquals(0, revokes(), "the kid and the new phone have not switched yet")
            dad.store().applyBatch(listOf(kid.beat(2), pixel.beat(2)))
            assertEquals(CloudRun.SYNCED, dad.runner(accounts::disk, oauth).run(CloudTrigger.NOW))
            assertEquals(1, revokes())
            assertEquals("AT", oauth.forms.last()["access_token"])
            assertEquals(CloudRun.SYNCED, dad.runner(accounts::disk, oauth).run(CloudTrigger.NOW))
            assertEquals(1, revokes(), "revoked once, never taken from the log again")
        } finally {
            pixel.close()
        }
    }

    /**
     * Re-review: Dad's ring lacks the key generation Mom's CloudAccess is sealed under until his sync absorbs it. Another
     * parent moved the storage meanwhile; Dad still retires Mom's token in that same run, after the sync.
     */
    @Test
    fun aKeyAbsorbedInTheSyncStillRetiresTheOlderAccess() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dad.joinAsAdminOf(mom)
        kid.joinAsAdminOf(mom, name = "Grandma") // a third parent, who moves the storage
        val g = mom.groups.read()!!
        val year = 365L * 24 * 60 * 60 * 1000
        val before = membershipOf(mom)
        check(mom.appendOwn { a -> listOf(GroupKey.rotate(a, before)) }) // key generation 1, sealed to all, absorbed by nobody yet
        assertNotNull(OwnerRecords.claim(mom.appendOwn, mom.store(), mom.keys, mom.cloudSecrets.ring(g), ByteArray(16) { 1 }, 1, "AT", mom.now + year))
        kid.store().storeMembership(mom.store().membershipEntries())
        kid.store().applyBatch(mom.store().entriesAfter(mom.keys.deviceId, 0, 10_000))
        val moved = ByteArray(16) { 2 }
        assertNotNull(OwnerRecords.claim(kid.appendOwn, kid.store(), kid.keys, kid.cloudSecrets.ring(g), moved, 2, "G2", kid.now + year))
        dad.store().storeMembership(kid.store().membershipEntries())
        dadHoldsMomsLog()
        dad.store().applyBatch(kid.store().entriesAfter(kid.keys.deviceId, 0, 10_000))
        assertFalse(1 in dad.cloudSecrets.ring(g).keys(), "Dad's ring lacks generation 1 before his sync")
        assertTrue(dad.cloudSecrets.saveHanded(g.groupId, HandedAccess(2, moved, "G2", kid.now + year), keep = true))
        val newDisk = InMemoryDisk()
        val oauth = FakeOAuthHttp(accessToken = "G2")
        assertEquals(CloudRun.SYNCED, dad.runner(AccountDisks(mapOf("G2" to newDisk))::disk, oauth).run(CloudTrigger.NOW))
        assertTrue(1 in dad.cloudSecrets.ring(g).keys(), "the sync absorbed it")
        assertEquals(listOf(1L to "AT"), dad.cloudSecrets.retired(g.groupId).map { it.generation to it.accessToken })
    }
}
