package app.kinsight.core.data

import app.kinsight.core.model.Membership
import app.kinsight.core.transport.ble.GateState
import app.kinsight.core.transport.cloud.CloudAccessView
import app.kinsight.core.transport.cloud.CloudPaths
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudDebugEnableTest {
    private val mom = TestDevice()
    private val kid = TestDevice()
    private val http = FakeOAuthHttp()

    @AfterTest
    fun cleanUp() {
        CloudDebugDisk.set(false)
        mom.close()
        kid.close()
    }

    @Test
    fun theFakeTokenTurnsTheCloudOnThroughTheRealPath() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        CloudDebugDisk.set(true)
        var enabled = 0
        val s = mom.signIn(http, CloudGraph::disk, onEnabled = { enabled++ })
        assertEquals(CloudEnableResult.ENABLED, CloudDebugEnable.enable(s, mom.now))
        assertEquals(1, enabled, "a run is asked for, as after a real sign-in")
        assertTrue(http.forms.isEmpty(), "nothing reached the token endpoint")
        val g = mom.groups.read()!!
        val storageId = CloudDebugDisk.current!!.files(CloudPaths.storageIdFile(g.groupId)).values.single()
        val m = Membership.compute(g.groupId, mom.store().membershipEntries())!!
        val owner = m.storageOwner!!
        assertEquals(mom.keys.deviceId, owner.deviceId)
        assertTrue(storageId.contentEquals(owner.storageId.toByteArray()))
        assertEquals(16, owner.tokenId.size, "a fresh token id, as a real sign-in names")
        val t = mom.cloudSecrets.tokens()!!
        assertEquals(CloudDebugEnable.FAKE_ACCESS to CloudDebugEnable.FAKE_REFRESH, t.accessToken to t.refreshToken)
        assertEquals(mom.now + CloudDebugEnable.VALID_MS, t.expiresAtMs)
        assertEquals(CloudDebugEnable.FAKE_ACCESS, CloudAccessView.current(mom.store(), m, mom.cloudSecrets.ring(g).keys())!!.accessToken)
        assertEquals(mom.now, mom.cloudState.current().tokenSinceMs, "the token age starts, as after a real sign-in")

        // The child hears of it over Bluetooth (the owner's log): its tracking gate opens with a storage owner.
        kid.store().applyBatch(mom.store().entriesAfter(mom.keys.deviceId, 0, Int.MAX_VALUE))
        kid.pipeline.setSharing(true)
        val gate = FamilyLocation(kid.db, kid.groups, kid.keyProvider, kid.settings, kid.appendOwn, kid.snapshots, upload = {}).watchGate()
        assertIs<GateState.Open>(gate)
        val km = Membership.compute(g.groupId, kid.store().membershipEntries())!!
        assertEquals(CloudDebugEnable.FAKE_ACCESS, CloudAccessView.current(kid.store(), km, kid.cloudSecrets.ring(g).keys())?.accessToken)
    }

    @Test
    fun itIsRefusedWithoutTheFakeDiskAndForAChild() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        val s = mom.signIn(http, CloudGraph::disk)
        assertFailsWith<IllegalStateException> { CloudDebugEnable.enable(s, mom.now) }
        assertNull(mom.cloudSecrets.tokens(), "the fake token never meets Yandex")
        CloudDebugDisk.set(true)
        assertEquals(CloudEnableResult.NOT_ADMIN, CloudDebugEnable.enable(kid.signIn(http, CloudGraph::disk), kid.now))
        assertNull(kid.cloudSecrets.tokens())
        assertTrue(CloudDebugDisk.current!!.files("app:").isEmpty())
    }

    /** The seam stays internal: no public JVM method of CloudSignIn takes a TokenSet. */
    @Test
    fun theSeamIsNotPublic() {
        assertEquals(emptyList(), CloudSignIn::class.java.methods.map { it.name }.filter { it == "signedIn" })
    }
}
