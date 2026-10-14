package app.kinsight.core.data

import app.kinsight.core.keystore.CloudTokenVault
import app.kinsight.core.keystore.GroupKeyFile
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.HandedAccess
import app.kinsight.core.sync.WelcomeCloud
import app.kinsight.core.transport.ble.GroupConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudSecretsTest {
    private val d = TestDevice()
    private val mom = TestDevice()

    @AfterTest
    fun cleanUp() {
        d.close()
        mom.close()
    }

    @Test
    fun theRingStartsAtGenerationZeroAndSurvivesARestart() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        val g = d.groups.read()!!
        val ring = d.cloudSecrets.ring(g)
        assertEquals(setOf(0), ring.keys().keys)
        assertContentEquals(g.discoveryKey, ring.keys().getValue(0))
        ring.put(1, ByteArray(32) { 1 })
        assertEquals(setOf(0, 1), d.cloudSecrets.ring(g).keys().keys)
        ring.retain(setOf(1))
        assertEquals(setOf(1), d.cloudSecrets.ring(g).keys().keys)
    }

    @Test
    fun aRingOfAnotherGroupStartsOver() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        d.cloudSecrets.ring(d.groups.read()!!).put(1, ByteArray(32) { 1 })
        val other = GroupConfig(GroupId("ee".repeat(16)), ByteArray(32) { 2 })
        assertEquals(setOf(0), d.cloudSecrets.ring(other).keys().keys)
        assertContentEquals(ByteArray(32) { 2 }, d.cloudSecrets.ring(other).keys().getValue(0))
        assertEquals(setOf(0, 1), d.cloudSecrets.ring(d.groups.read()!!).keys().keys, "reading another group's ring never overwrites ours")
    }

    @Test
    fun aHandedTokenNeverReplacesTheOwnersTokens() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        d.ownStorage(token = "OWN")
        assertFalse(d.cloudSecrets.saveHanded(d.groups.read()!!.groupId, HandedAccess(1, ByteArray(16) { 1 }, "OTHER", Long.MAX_VALUE)))
        val t = d.cloudSecrets.tokens()!!
        assertEquals("OWN", t.accessToken)
        assertTrue(t.owner)
        assertFalse(
            d.cloudSecrets.saveHanded(d.groups.read()!!.groupId, HandedAccess(2, ByteArray(16) { 2 }, "NEXT", Long.MAX_VALUE)),
            "not even for another storage generation",
        )
        assertEquals("OWN", d.cloudSecrets.tokens()!!.accessToken)
    }

    @Test
    fun theNewerOfTheLogAndTheHandedTokenWins() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        d.joinGroupOf(mom)
        mom.ownStorage(token = "OLD", expiresAtMs = 1_000)
        d.learnsStorageFrom(mom, token = "OLD", expiresAtMs = 1_000)
        // The kid read Mom's CloudAccess from her pack on the Disk.
        d.store().applyBatch(mom.store().entriesAfter(mom.keys.deviceId, 0, 100).filter { it.kind == EntryKind.CLOUD_ACCESS })
        val g = d.groups.read()!!
        suspend fun token() = d.cloudSecrets.access(d.store(), Membership.compute(g.groupId, d.store().membershipEntries())!!, d.keys.deviceId, d.cloudSecrets.ring(g))!!.accessToken
        assertEquals("OLD", token())
        val storageId = ByteArray(16) { 1 }
        assertTrue(d.cloudSecrets.saveHanded(g.groupId, HandedAccess(1, storageId, "NEW", 2_000)))
        assertEquals("NEW", token(), "a later sign-in expires later")
        assertFalse(d.cloudSecrets.saveHanded(g.groupId, HandedAccess(1, storageId, "OLDER", 500)), "an older token never replaces a newer one")
        assertEquals("NEW", token())
    }

    @Test
    fun adoptingAWelcomeSeedsTheNewGroupAndDropsOldTokens() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        d.ownStorage()
        val gid = GroupId("ee".repeat(16))
        d.cloudSecrets.adopt(gid, ByteArray(32) { 2 }, WelcomeCloud(1, ByteArray(32) { 3 }, HandedAccess(1, ByteArray(16) { 4 }, "AT", 5)))
        val t = d.cloudSecrets.tokens()!!
        assertEquals(gid.hex, t.groupHex)
        assertFalse(t.owner)
        assertNull(t.refreshToken)
        assertEquals(setOf(0, 1), d.cloudSecrets.ring(GroupConfig(gid, ByteArray(32) { 2 })).keys().keys)
    }

    @Test
    fun aParentHandsItsNewestKeyAndTheAccessAtPairing() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        d.ownStorage(token = "AT")
        val g = d.groups.read()!!
        val m = Membership.compute(g.groupId, d.store().membershipEntries())!!
        val w = d.cloudSecrets.welcome(g, d.store(), m, d.keys.deviceId)
        assertEquals(0, w.keyEpoch)
        assertNull(w.groupKey, "generation 0 is the discovery key the welcome carries anyway")
        assertEquals("AT", w.access!!.accessToken)
    }

    @Test
    fun onlyTheStorageOwnerHandsTheAccessAtPairing() = runBlocking<Unit> {
        val dad = TestDevice()
        try {
            mom.group.createGroup("Mom")
            dad.joinGroupOf(mom, DeviceRole.PARENT, "Dad")
            mom.ownStorage(token = "AT")
            dad.learnsStorageFrom(mom, token = "AT")
            val g = dad.groups.read()!!
            val m = Membership.compute(g.groupId, dad.store().membershipEntries())!!
            assertEquals("AT", dad.cloudSecrets.access(dad.store(), m, dad.keys.deviceId, dad.cloudSecrets.ring(g))!!.accessToken)
            assertNull(dad.cloudSecrets.welcome(g, dad.store(), m, dad.keys.deviceId).access, "spec §6: the owner hands the token")
        } finally {
            dad.close()
        }
    }

    @Test
    fun forgetLeavesNoFile() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        d.ownStorage()
        d.cloudSecrets.forget()
        assertNull(d.cloudSecrets.tokens())
        assertFalse(File(d.dir, CloudTokenVault.FILE_NAME).exists())
        assertFalse(File(d.dir, GroupKeyFile.FILE_NAME).exists())
    }
}
