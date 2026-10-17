package app.kinsight.core.data

import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.storageOffEntry
import app.kinsight.core.transport.cloud.CloudAccessView
import app.kinsight.core.transport.cloud.CloudPaths
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.YandexOAuth
import app.kinsight.core.transport.cloud.InMemoryDisk
import app.kinsight.core.transport.cloud.StorageMove
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Выключить синхронизацию через Яндекс Диск»: the owner turns the cloud off for the family, and on again by the normal sign-in. */
class CloudOffTest {
    private val mom = TestDevice()
    private val dad = TestDevice()
    private val kid = TestDevice()
    private val disk = InMemoryDisk()
    private val http = FakeOAuthHttp(accessToken = "AT1")

    @AfterTest
    fun cleanUp() {
        mom.close()
        dad.close()
        kid.close()
    }

    private suspend fun membershipOf(d: TestDevice): MembershipState = Membership.compute(d.groups.read()!!.groupId, d.store().membershipEntries())!!

    private fun revoked(): List<String> = http.forms.filter { "client_secret" in it && "access_token" in it }.map { it["access_token"]!! }

    /** Mom turns the cloud on by the sign-in; Dad (an admin) and the kid are handed her token. */
    private suspend fun family() {
        mom.group.createGroup("Mom")
        dad.joinAsAdminOf(mom)
        kid.joinGroupOf(mom)
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        assertEquals(CloudEnableResult.ENABLED, s.finish("1234567"))
        dad.learnsStorageFrom(mom, token = "AT1")
        kid.learnsStorageFrom(mom, token = "AT1")
    }

    /** A Bluetooth meeting with [from]: the membership and [from]'s own log arrive. */
    private suspend fun TestDevice.meets(from: TestDevice) {
        store().storeMembership(from.store().membershipEntries())
        store().applyBatch(from.store().entriesAfter(from.keys.deviceId, 0, 10_000))
    }

    private val notes = mutableListOf<CloudOffNote>()

    private fun TestDevice.cloud(d: (suspend () -> String) -> DiskApi = { _ -> disk }): CloudRunner = CloudRunner(
        db, groups, keyProvider, settings, cloudSecrets, cloudState, appendOwn, d, cloudSecrets.runLock, clock = { now },
        config = { TEST_CLOUD }, oauth = { c -> YandexOAuth(c.clientId, c.clientSecret, http = http, clock = { now }) },
        onTurnedOff = { notes += it },
    )

    private suspend fun offGeneration(d: TestDevice) = membershipOf(d).storageOff!!.generation

    @Test
    fun theOwnerUploadsTheOffAndKeepsItsTokenForTheRevoke() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!
        val before = membershipOf(mom).storageOwner!!
        val momPacks = disk.files(CloudPaths.deviceDir(g.groupId, mom.keys.deviceId)).size
        assertEquals(CloudOffResult.OFF, mom.cloud().turnOff())
        val m = membershipOf(mom)
        assertNull(m.storageOwner)
        assertEquals(before.generation + 1, m.storageOff!!.generation)
        assertEquals(before.storageId, m.storageOff!!.storageId)
        assertTrue(disk.files(CloudPaths.deviceDir(g.groupId, mom.keys.deviceId)).size > momPacks, "the off record went up with the token")
        assertTrue(revoked().isEmpty(), "not revoked at once")
        assertEquals(listOf(CloudOffNote(mom.now + CloudOff.REVOKE_AFTER_MS, revokePending = true)), notes)
        assertNull(mom.cloudSecrets.tokens(), "the vault goes")
        assertEquals(listOf("AT1"), mom.cloudSecrets.retired(g.groupId).map { it.accessToken }, "kept for the revoke")
        assertEquals(m.storageOff!!.generation, mom.cloudState.current().syncedGeneration, "the Heartbeat reports the off")
        val runner = mom.cloud()
        assertFalse(runner.isOn())
        assertEquals(CloudRun.OFF, runner.run(CloudTrigger.NOW))
        assertTrue(disk.files(CloudPaths.root(g.groupId)).isNotEmpty(), "the family folder stays on the Disk")
        assertEquals(CloudOffResult.NOT_OWNER, runner.turnOff(), "nothing left to turn off")
        assertEquals(1, notes.size)
    }

    @Test
    fun aCloudOnlyMemberLearnsTheOffThroughTheDisk() = runBlocking<Unit> {
        family()
        assertEquals(CloudRun.SYNCED, kid.cloud().run(CloudTrigger.NOW))
        assertEquals(CloudOffResult.OFF, mom.cloud().turnOff())
        // No Bluetooth meeting: the kid's next run reads the off record with the token that still works.
        assertNotNull(membershipOf(kid).storageOwner)
        assertEquals(CloudRun.OFF, kid.cloud().run(CloudTrigger.NOW))
        assertTrue(CloudOff.inForce(membershipOf(kid)))
        assertNull(kid.cloudSecrets.tokens(), "the handed token goes, as over Bluetooth")
        assertEquals(offGeneration(mom), kid.cloudState.current().syncedGeneration)
        assertEquals(offGeneration(mom), kid.cloudState.current().offRevoke!!.generation)
        assertEquals(CloudRun.OFF, kid.cloud().run(CloudTrigger.NOW))
        assertTrue(revoked().isEmpty(), "a child revokes nothing")
        assertEquals(CloudOffNote(kid.now + CloudOff.REVOKE_AFTER_MS, revokePending = false), kid.cloud().settleOff())
    }

    @Test
    fun theRevokeWaitsUntilEveryMemberConfirmedTheOff() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!
        val runner = mom.cloud()
        assertEquals(CloudOffResult.OFF, runner.turnOff())
        assertEquals(RevokeRun.WAITING, runner.revokePending())
        // Dad learns it over Bluetooth, the kid through the Disk; each signs a Heartbeat with the off generation.
        dad.meets(mom)
        assertNotNull(dad.cloud().settleOff())
        assertNull(dad.cloudSecrets.tokens())
        dad.beat(dad.cloudState.current().syncedGeneration!!)
        assertEquals(CloudRun.OFF, kid.cloud().run(CloudTrigger.NOW))
        kid.beat(kid.cloudState.current().syncedGeneration!!)
        mom.meets(dad)
        assertEquals(RevokeRun.WAITING, runner.revokePending(), "the kid has not confirmed here yet")
        mom.meets(kid)
        assertEquals(RevokeRun.REVOKED, runner.revokePending())
        assertEquals(listOf("AT1"), revoked())
        assertTrue(mom.cloudSecrets.retired(g.groupId).isEmpty())
        assertEquals(RevokeRun.NOTHING, runner.revokePending())
        assertEquals(1, revoked().size, "revoked once")
    }

    @Test
    fun theRevokeGoesAfterAWeekAndNoAnswerIsRetried() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!
        val runner = mom.cloud()
        assertEquals(CloudOffResult.OFF, runner.turnOff())
        mom.now += CloudOff.REVOKE_AFTER_MS - 1
        assertEquals(RevokeRun.WAITING, runner.revokePending())
        mom.now += 1
        http.unreachable = true
        assertEquals(RevokeRun.RETRY, runner.revokePending())
        assertEquals(listOf("AT1"), mom.cloudSecrets.retired(g.groupId).map { it.accessToken }, "not dropped")
        assertTrue(revoked().isEmpty())
        assertEquals(RevokeRun.REVOKED, runner.revokePending())
        assertEquals(listOf("AT1"), revoked())
        assertTrue(mom.cloudSecrets.retired(g.groupId).isEmpty())
    }

    @Test
    fun aParentsOwnRetiredTokenWaitsForTheSameRevoke() = runBlocking<Unit> {
        family()
        val g = dad.groups.read()!!
        dad.cloudSecrets.retire(g.groupId, 1, "OLD")
        assertEquals(CloudOffResult.OFF, mom.cloud().turnOff())
        dad.meets(mom)
        val note = dad.cloud().settleOff()!!
        assertTrue(note.revokePending)
        assertEquals(listOf("OLD"), dad.cloudSecrets.retired(g.groupId).map { it.accessToken }, "the handed token is not his to revoke")
        dad.now += CloudOff.REVOKE_AFTER_MS
        assertEquals(RevokeRun.REVOKED, dad.cloud().revokePending())
        assertEquals(listOf("OLD"), revoked())
    }

    @Test
    fun onlyTheOwnerTurnsItOff() = runBlocking<Unit> {
        family()
        assertEquals(CloudOffResult.NOT_OWNER, dad.cloud().turnOff())
        assertEquals(CloudOffResult.NOT_OWNER, kid.cloud().turnOff())
        assertNotNull(membershipOf(mom).storageOwner)
        // Dad's own off record is void everywhere (the owner in force signs it alone).
        val owner = membershipOf(dad).storageOwner!!
        check(dad.pipeline.appendOwn { a -> listOf(a.storageOffEntry(owner.storageId.toByteArray(), owner.generation + 1)) })
        mom.meets(dad)
        assertEquals(mom.keys.deviceId, membershipOf(mom).storageOwner!!.deviceId)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun theChildsTrackingGateClosesWhenTheCloudGoesOff() = runBlocking<Unit> {
        family()
        kid.settings.recordConsent(kid.now)
        kid.pipeline.setSharing(true)
        val host = FamilyLocation(kid.db, kid.groups, kid.keyProvider, kid.settings, kid.appendOwn, kid.snapshots, upload = {}, clock = { kid.now })
        assertNotNull(host.zones())
        assertEquals(CloudOffResult.OFF, mom.cloud().turnOff())
        kid.meets(mom)
        assertNull(host.zones(), "no cloud: the location engine stops")
    }

    @Test
    fun turningItOnAgainRevokesTheOldTokenFirst() = runBlocking<Unit> {
        family()
        assertEquals(CloudOffResult.OFF, mom.cloud().turnOff())
        http.accessToken = "AT7"
        val again = mom.signIn(http, { disk })
        again.start("Pixel")
        assertEquals(CloudEnableResult.ENABLED, again.finish("1234567"))
        assertEquals(listOf("AT1"), revoked())
        val revokeAt = http.urls.indexOfFirst { it.endsWith("/revoke_token") }
        assertTrue(revokeAt > http.urls.indexOfLast { it.endsWith("/token") && !it.endsWith("/revoke_token") }, "after the exchange, before the new storage is used")
        assertEquals("AT7", mom.cloudSecrets.tokens()!!.accessToken)
        assertTrue(mom.cloudSecrets.retired(mom.groups.read()!!.groupId).isEmpty())
        assertEquals(RevokeRun.NOTHING, mom.cloud().revokePending(), "the cloud is on again")
    }

    @Test
    fun yandexHandingTheSameTokenAgainKeepsIt() = runBlocking<Unit> {
        family()
        assertEquals(CloudOffResult.OFF, mom.cloud().turnOff())
        // Yandex gives a phone that signs in anew (same device_id) the token it already holds.
        val again = mom.signIn(http, { disk })
        again.start("Pixel")
        assertEquals(CloudEnableResult.ENABLED, again.finish("1234567"))
        assertTrue(revoked().isEmpty(), "the token just signed in with is never revoked")
        assertEquals("AT1", mom.cloudSecrets.tokens()!!.accessToken)
        assertTrue(mom.cloudSecrets.retired(mom.groups.read()!!.groupId).isEmpty())
    }

    @Test
    fun anyAdminParentTurnsItOnAgainOnTheSameFolder() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!
        val first = membershipOf(mom).storageOwner!!
        assertEquals(CloudRun.SYNCED, mom.runner({ _ -> disk }, http).run(CloudTrigger.NOW))
        assertEquals(CloudOffResult.OFF, mom.cloud().turnOff())
        dad.meets(mom)
        dad.cloud().settleOff()
        // Dad signs in to the same Yandex account: the folder the off left is that storage, its id goes on.
        http.accessToken = "AT9"
        val dadSignIn = dad.signIn(http, { disk })
        assertNull(dadSignIn.precheck(), "an off record is no owner")
        dadSignIn.start("Dad's phone")
        assertEquals(CloudEnableResult.ENABLED, dadSignIn.finish("7654321"))
        val m = membershipOf(dad)
        val owner = m.storageOwner!!
        assertEquals(dad.keys.deviceId, owner.deviceId)
        assertEquals(first.generation + 2, owner.generation, "above the off record")
        assertEquals(first.storageId, owner.storageId, "the same Disk keeps its storage.id (rulings Q5)")
        assertFalse(owner.tokenId == first.tokenId, "a fresh token id (rulings Q3)")
        assertNull(m.storageOff)
        assertEquals("AT9", dad.cloudSecrets.tokens()!!.accessToken)
        assertTrue(dad.cloudSecrets.tokens()!!.owner)
        // Mom and the kid follow at their next Bluetooth meeting with Dad; the access is Dad's «новый доступ».
        mom.meets(dad)
        kid.meets(dad)
        kid.ownSummary("2026-10-17", 60_000)
        for (d in listOf(mom, kid)) {
            val access = CloudAccessView.current(d.store(), membershipOf(d), d.cloudSecrets.ring(d.groups.read()!!).keys())
            assertEquals("AT9", access?.accessToken)
            assertEquals(CloudRun.SYNCED, d.runner({ _ -> disk }, http).run(CloudTrigger.NOW))
        }
        assertEquals(CloudRun.SYNCED, dad.runner({ _ -> disk }, http).run(CloudTrigger.NOW))
        val packs = disk.files(CloudPaths.deviceDir(g.groupId, kid.keys.deviceId))
        assertTrue(packs.isNotEmpty(), "the kid uploads again")
    }

    @Test
    fun turningOnAgainOnAnotherAccountWritesAFreshStorageId() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!
        val first = membershipOf(mom).storageOwner!!
        assertEquals(CloudOffResult.OFF, mom.cloud().turnOff())
        val other = InMemoryDisk()
        http.accessToken = "AT5"
        val again = mom.signIn(http, { other })
        again.start("Pixel")
        assertEquals(CloudEnableResult.ENABLED, again.finish("1234567"))
        val owner = membershipOf(mom).storageOwner!!
        assertEquals(first.generation + 2, owner.generation)
        assertFalse(owner.storageId == first.storageId, "another Disk never gets the old id")
        assertContentEquals(owner.storageId.toByteArray(), StorageMove.storageIdOf(other, g.groupId))
        assertContentEquals(first.storageId.toByteArray(), StorageMove.storageIdOf(disk, g.groupId), "the old folder is left as it was")
        val access = mom.store().entriesAfter(mom.keys.deviceId, 0, 1_000).last { it.kind == EntryKind.CLOUD_ACCESS }.body.cloud_access!!
        assertEquals(owner.generation, access.generation)
    }
}
