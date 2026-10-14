package app.kinsight.core.data

import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.Membership
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.HandedAccess
import app.kinsight.core.sync.MemoryPipe
import app.kinsight.core.sync.SecureChannel
import app.kinsight.core.sync.SessionResult
import app.kinsight.core.sync.SyncSession
import app.kinsight.core.transport.ble.SyncPolicies
import app.kinsight.core.transport.cloud.InMemoryDisk
import app.kinsight.core.transport.cloud.TokenSet
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudHandoffTest {
    private val mom = TestDevice()
    private val dad = TestDevice()
    private val kid = TestDevice()
    private val kid2 = TestDevice()
    private val disk = InMemoryDisk()

    @AfterTest
    fun cleanUp() {
        mom.close()
        dad.close()
        kid.close()
        kid2.close()
    }

    /** One Bluetooth session between [a] and [b], each with its cloud hand-off, as SyncEngine runs it. */
    private suspend fun bluetooth(a: TestDevice, b: TestDevice): Pair<SessionResult, SessionResult> = coroutineScope {
        suspend fun session(d: TestDevice): SyncSession {
            val g = d.groups.read()!!
            val m = Membership.compute(g.groupId, d.store().membershipEntries())
            return SyncSession(
                d.store(), SyncPolicies.forDevice(m, d.keys.deviceId, d.settings.current()),
                handoff = CloudSessionHandoff(d.cloudSecrets, g, d.store(), d.keys.deviceId),
            )
        }
        val (sa, sb) = MemoryPipe.pair()
        val prologue = SecureChannel.prologue(a.groups.read()!!.groupId)
        val sessionA = session(a)
        val sessionB = session(b)
        val ra = async { sessionA.run(SecureChannel.initiate(sa, a.keys.dh, prologue)) }
        val rb = async { sessionB.run(SecureChannel.accept(sb, b.keys.dh, prologue)) }
        ra.await() to rb.await()
    }

    /** Dad, a second parent with admin rights (not the storage owner): the one R3 must keep from handing the token. */
    private suspend fun dadJoinsAsAdmin() {
        dad.joinGroupOf(mom, role = DeviceRole.PARENT, name = "Dad")
        check(mom.pipeline.appendOwn { a -> listOf(a.grantAdminEntry(dad.keys.deviceId)) })
        dad.store().storeMembership(mom.store().membershipEntries())
    }

    private suspend fun membershipOf(d: TestDevice) = Membership.compute(d.groups.read()!!.groupId, d.store().membershipEntries())!!

    private fun handoffOf(d: TestDevice) = CloudSessionHandoff(d.cloudSecrets, d.groups.read()!!, d.store(), d.keys.deviceId)

    @Test
    fun aChildPairedBeforeTheCloudGetsTheTokenOverBluetooth() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom) // paired while the cloud was off
        mom.ownStorage(token = "AT")
        assertEquals(CloudRun.OFF, kid.runner(disk).run(CloudTrigger.NOW), "the kid knows no storage yet")
        val (rm, rk) = bluetooth(mom, kid)
        assertIs<SessionResult.Completed>(rm)
        assertIs<SessionResult.Completed>(rk)
        val t = kid.cloudSecrets.tokens()!!
        assertEquals("AT", t.accessToken)
        assertFalse(t.owner)
        assertNull(t.refreshToken, "spec §6: the refresh token stays on the owner's phone")
        assertEquals("RT", mom.cloudSecrets.tokens()!!.refreshToken)
        val day = kid.ownSummary("2026-10-13", 60_000)
        assertEquals(CloudRun.SYNCED, kid.runner(disk).run(CloudTrigger.NOW))
        assertEquals(CloudRun.SYNCED, mom.runner(disk).run(CloudTrigger.NOW))
        assertTrue(day in mom.store().liveEntries())
    }

    @Test
    fun aChildHandsNothing() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dadJoinsAsAdmin()
        kid.joinGroupOf(mom)
        mom.ownStorage(token = "AT")
        dad.learnsStorageFrom(mom, token = "AT")
        kid.learnsStorageFrom(mom, token = "KIDS-COPY", expiresAtMs = Long.MAX_VALUE)
        // The owner keeps its own tokens.
        assertIs<SessionResult.Completed>(bluetooth(kid, mom).first)
        assertEquals("AT", mom.cloudSecrets.tokens()!!.accessToken)
        assertTrue(mom.cloudSecrets.tokens()!!.owner)
        // Another parent would take a later-expiring token from the owner, but never from a child.
        assertIs<SessionResult.Completed>(bluetooth(kid, dad).first)
        val held = dad.cloudSecrets.tokens()!!
        assertEquals("AT", held.accessToken)
        assertEquals(mom.now + 365L * 24 * 60 * 60 * 1000, held.expiresAtMs)
        // The kid does not offer it, and Dad's side refuses it even when a child sends it (SyncSession does not check).
        val m = membershipOf(dad)
        assertNull(handoffOf(kid).offer(m.active.getValue(dad.keys.deviceId), m))
        val copy = kid.cloudSecrets.tokens()!!
        handoffOf(dad).receive(m.active.getValue(kid.keys.deviceId), m, HandedAccess(copy.storageGeneration, copy.storageId, copy.accessToken, copy.expiresAtMs))
        assertEquals("AT", dad.cloudSecrets.tokens()!!.accessToken)
    }

    @Test
    fun aParentThatDoesNotOwnTheStorageHandsNothing() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dadJoinsAsAdmin()
        kid2.joinGroupOf(mom, name = "Kid2")
        mom.ownStorage(token = "AT")
        dad.learnsStorageFrom(mom, token = "AT")
        kid2.store().storeMembership(mom.store().membershipEntries())
        assertIs<SessionResult.Completed>(bluetooth(dad, kid2).first)
        assertNull(kid2.cloudSecrets.tokens(), "only the owner hands the token (spec §6)")
        val m = membershipOf(dad)
        assertTrue(m.active.getValue(dad.keys.deviceId).isAdmin)
        assertNull(handoffOf(dad).offer(m.active.getValue(kid2.keys.deviceId), m), "Dad holds a token but does not offer it")
        assertIs<SessionResult.Completed>(bluetooth(mom, kid2).first)
        assertEquals("AT", kid2.cloudSecrets.tokens()!!.accessToken)
    }

    @Test
    fun aStaleOwnerRoleIsDroppedAndTheOwnersTokenTaken() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dadJoinsAsAdmin()
        // Dad turned the cloud on at the same time; Mom's StorageOwner won.
        val g = dad.groups.read()!!
        dad.cloudSecrets.saveOwner(g.groupId, 1, ByteArray(16) { 9 }, TokenSet("DADS", "DRT", Long.MAX_VALUE))
        mom.ownStorage(token = "AT")
        dad.store().storeMembership(mom.store().membershipEntries())
        assertIs<SessionResult.Completed>(bluetooth(mom, dad).first)
        val t = dad.cloudSecrets.tokens()!!
        assertEquals("AT", t.accessToken)
        assertFalse(t.owner)
        assertNull(t.refreshToken, "the stale refresh token is gone")
    }

    @Test
    fun aCloudRunDropsAStaleOwnerRole() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        dadJoinsAsAdmin()
        val g = dad.groups.read()!!
        dad.cloudSecrets.saveOwner(g.groupId, 1, ByteArray(16) { 9 }, TokenSet("DADS", "DRT", Long.MAX_VALUE))
        mom.ownStorage(token = "AT")
        dad.store().storeMembership(mom.store().membershipEntries())
        dad.runner(disk).run(CloudTrigger.NOW)
        assertNull(dad.cloudSecrets.tokens()?.refreshToken)
        assertFalse(dad.cloudSecrets.tokens()?.owner ?: false)
    }

    @Test
    fun afterTheOwnerRestoresAccessAChildFollowsOverBluetooth() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        val http = FakeOAuthHttp("AT1")
        var accepted = setOf("AT1")
        val diskFor = tokenDisk(disk) { accepted }
        val signIn = mom.signIn(http, diskFor)
        signIn.start("Pixel")
        assertEquals(CloudEnableResult.ENABLED, signIn.finish("1234567"))
        bluetooth(mom, kid)
        assertEquals(CloudRun.SYNCED, mom.runner(diskFor).run(CloudTrigger.NOW))
        assertEquals(CloudRun.SYNCED, kid.runner(diskFor).run(CloudTrigger.NOW))
        assertTrue(kid.store().entriesAfter(mom.keys.deviceId, 0, 100).any { it.kind == EntryKind.CLOUD_ACCESS }, "the kid read Mom's CloudAccess")
        accepted = setOf("AT2") // AT1 died
        assertEquals(CloudRun.UNAUTHORIZED, mom.runner(diskFor).run(CloudTrigger.NOW))
        assertEquals(CloudRun.UNAUTHORIZED, kid.runner(diskFor).run(CloudTrigger.NOW))
        http.accessToken = "AT2"
        mom.now += 60_000
        signIn.start("Pixel")
        assertEquals(CloudEnableResult.RESTORED, signIn.finish("7654321"))
        assertEquals(CloudRun.SYNCED, mom.runner(diskFor).run(CloudTrigger.NOW))
        assertEquals(CloudRun.UNAUTHORIZED, kid.runner(diskFor).run(CloudTrigger.NOW), "AT1 cannot read the new CloudAccess")
        bluetooth(mom, kid)
        assertEquals(CloudRun.SYNCED, kid.runner(diskFor).run(CloudTrigger.NOW))
        assertEquals("AT2", kid.cloudSecrets.tokens()!!.accessToken)
    }
}
