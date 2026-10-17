package app.kinsight.core.data

import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.storageOffEntry
import app.kinsight.core.transport.cloud.CloudAccessView
import app.kinsight.core.transport.cloud.CloudPaths
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
    private suspend fun family(): CloudSignIn {
        mom.group.createGroup("Mom")
        dad.joinAsAdminOf(mom)
        kid.joinGroupOf(mom)
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        assertEquals(CloudEnableResult.ENABLED, s.finish("1234567"))
        dad.learnsStorageFrom(mom, token = "AT1")
        kid.learnsStorageFrom(mom, token = "AT1")
        return s
    }

    /** A Bluetooth meeting with [from]: the membership and [from]'s own log arrive. */
    private suspend fun TestDevice.meets(from: TestDevice) {
        store().storeMembership(from.store().membershipEntries())
        store().applyBatch(from.store().entriesAfter(from.keys.deviceId, 0, 10_000))
    }

    @Test
    fun theOwnerTurnsTheCloudOffAndRevokesItsTokenOnce() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dad.joinAsAdminOf(mom)
        kid.joinGroupOf(mom)
        var off = 0
        val s = mom.signIn(http, { disk }, onTurnedOff = { off++ })
        s.start("Pixel")
        assertEquals(CloudEnableResult.ENABLED, s.finish("1234567"))
        val before = membershipOf(mom).storageOwner!!
        assertEquals(CloudOffResult.OFF, s.turnOff())
        assertEquals(1, off, "the app stops the cloud work")
        val m = membershipOf(mom)
        assertNull(m.storageOwner)
        assertEquals(before.generation + 1, m.storageOff!!.generation)
        assertEquals(before.storageId, m.storageOff!!.storageId)
        assertEquals(listOf("AT1"), revoked(), "the owner's token is revoked once")
        assertNull(mom.cloudSecrets.tokens(), "the vault goes")
        assertEquals(CloudState(), mom.cloudState.current())
        val runner = mom.runner { _ -> disk }
        assertFalse(runner.isOn())
        assertEquals(CloudRun.OFF, runner.run(CloudTrigger.NOW))
        assertNull(mom.cloudSecrets.access(mom.store(), m, mom.keys.deviceId, mom.cloudSecrets.ring(mom.groups.read()!!)))
        assertTrue(disk.files(CloudPaths.root(mom.groups.read()!!.groupId)).isNotEmpty(), "the family folder stays on the Disk")
        assertEquals(CloudOffResult.NOT_OWNER, s.turnOff(), "nothing left to turn off")
        assertEquals(1, revoked().size)
        assertEquals(1, off)
    }

    @Test
    fun aRevokeWithoutAnAnswerDoesNotKeepTheCloudOn() = runBlocking<Unit> {
        val s = family()
        val posts = http.forms.size
        http.unreachable = true
        assertEquals(CloudOffResult.OFF, s.turnOff())
        assertEquals(posts, http.forms.size, "the revoke never reached Yandex")
        assertNull(mom.cloudSecrets.tokens())
        assertNull(membershipOf(mom).storageOwner)
    }

    @Test
    fun onlyTheOwnerTurnsItOff() = runBlocking<Unit> {
        family()
        assertEquals(CloudOffResult.NOT_OWNER, dad.signIn(http, { disk }).turnOff())
        assertEquals(CloudOffResult.NOT_OWNER, kid.signIn(http, { disk }).turnOff())
        assertNotNull(membershipOf(mom).storageOwner)
        assertTrue(revoked().isEmpty())
        // Dad's own off record is void everywhere (the owner in force signs it alone).
        val g = dad.groups.read()!!
        val owner = membershipOf(dad).storageOwner!!
        check(dad.pipeline.appendOwn { a -> listOf(a.storageOffEntry(owner.storageId.toByteArray(), owner.generation + 1)) })
        mom.meets(dad)
        assertEquals(mom.keys.deviceId, membershipOf(mom).storageOwner!!.deviceId)
    }

    @Test
    fun theOtherPhonesDropTheirTokensWhenTheyLearnIt() = runBlocking<Unit> {
        val s = family()
        val g = dad.groups.read()!!
        dad.cloudSecrets.retire(g.groupId, 1, "OLD") // a token Dad still had to revoke
        assertEquals(CloudOffResult.OFF, s.turnOff())
        assertEquals(listOf("AT1"), revoked())
        // Over Bluetooth: the session hook lets go of the tokens and stops the cloud work.
        dad.meets(mom)
        val dadRunner = dad.runner({ _ -> disk }, http)
        assertTrue(dadRunner.settleOff())
        assertNull(dad.cloudSecrets.tokens(), "the handed token goes")
        assertTrue(dad.cloudSecrets.retired(g.groupId).isEmpty())
        assertEquals(listOf("AT1", "OLD"), revoked(), "Dad revokes his own retired token, not the owner's handed one")
        assertFalse(dadRunner.isOn())
        assertTrue(dadRunner.settleOff(), "still off")
        assertEquals(2, revoked().size, "nothing held: nothing revoked again")
        // The kid at its next cloud run (the record came over Bluetooth after its last session).
        kid.meets(mom)
        assertNotNull(kid.cloudSecrets.tokens())
        assertEquals(CloudRun.OFF, kid.runner({ _ -> disk }, http).run(CloudTrigger.NOW))
        assertNull(kid.cloudSecrets.tokens())
        assertEquals(2, revoked().size, "a child revokes nothing")
    }

    @Test
    fun theChildsTrackingGateClosesWhenTheCloudGoesOff() = runBlocking<Unit> {
        val s = family()
        kid.settings.recordConsent(kid.now)
        kid.pipeline.setSharing(true)
        val host = FamilyLocation(kid.db, kid.groups, kid.keyProvider, kid.settings, kid.appendOwn, kid.snapshots, upload = {}, clock = { kid.now })
        assertNotNull(host.zones())
        assertEquals(CloudOffResult.OFF, s.turnOff())
        kid.meets(mom)
        assertNull(host.zones(), "no cloud: the location engine stops")
    }

    @Test
    fun anyAdminParentTurnsItOnAgainOnTheSameFolder() = runBlocking<Unit> {
        val s = family()
        val g = mom.groups.read()!!
        val first = membershipOf(mom).storageOwner!!
        assertEquals(CloudRun.SYNCED, mom.runner({ _ -> disk }, http).run(CloudTrigger.NOW))
        assertEquals(CloudOffResult.OFF, s.turnOff())
        dad.meets(mom)
        dad.runner({ _ -> disk }, http).settleOff()
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
        val s = family()
        val g = mom.groups.read()!!
        val first = membershipOf(mom).storageOwner!!
        assertEquals(CloudOffResult.OFF, s.turnOff())
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
