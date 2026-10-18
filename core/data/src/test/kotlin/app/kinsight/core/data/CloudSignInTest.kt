package app.kinsight.core.data

import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.Membership
import app.kinsight.core.model.StorageOwnerState
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.sync.CloudAccessRules
import app.kinsight.core.transport.cloud.CloudAccessView
import app.kinsight.core.transport.cloud.CloudPaths
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.DiskItem
import app.kinsight.core.transport.cloud.GroupKey
import app.kinsight.core.transport.cloud.InMemoryDisk
import app.kinsight.core.transport.cloud.Pkce
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okio.ByteString.Companion.toByteString
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudSignInTest {
    private val mom = TestDevice()
    private val kid = TestDevice()
    private val disk = InMemoryDisk()
    private val http = FakeOAuthHttp()

    @AfterTest
    fun cleanUp() {
        mom.close()
        kid.close()
    }

    @Test
    fun aParentTurnsTheCloudOnWithOneSignIn() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        var enabled = 0
        val s = mom.signIn(http, { disk }, onEnabled = { enabled++ })
        val url = s.start("Pixel")!!
        val q = query(url)
        assertEquals(TEST_CLOUD.clientId, q["client_id"])
        assertEquals(CloudConfig.CODE_PAGE, q["redirect_uri"])
        assertEquals(mom.keys.deviceId.hex, q["device_id"], "spec §6: the owner's token carries device_id")
        assertEquals(SignInState.Waiting(url), s.state.value)
        assertEquals(CloudEnableResult.ENABLED, s.finish("1234567"))
        assertEquals(SignInState.Done(CloudEnableResult.ENABLED), s.state.value)
        assertEquals(1, enabled)
        assertEquals("1234567", http.forms.single()["code"])
        assertEquals(q["code_challenge"], Pkce.challenge(http.forms.single()["code_verifier"]!!))
        assertNull(http.forms.single()["client_secret"], "spec §3: the code exchange goes without the secret")
        val g = mom.groups.read()!!
        val storageId = disk.files(CloudPaths.storageIdFile(g.groupId)).values.single()
        assertEquals(16, storageId.size)
        val m = Membership.compute(g.groupId, mom.store().membershipEntries())!!
        val owner = m.storageOwner!!
        assertEquals(StorageOwnerState(mom.keys.deviceId, storageId.toByteString(), 1), StorageOwnerState(owner.deviceId, owner.storageId, owner.generation))
        assertEquals(16, owner.tokenId.size, "rulings Q15: a fresh sign-in names a new token")
        assertTrue(owner.departed.isEmpty(), "nobody departed")
        val t = mom.cloudSecrets.tokens()!!
        assertTrue(t.owner)
        assertEquals("AT1", t.accessToken)
        assertEquals("RT", t.refreshToken)
        assertEquals("AT1", CloudAccessView.current(mom.store(), m, mom.cloudSecrets.ring(g).keys())!!.accessToken)
    }

    @Test
    fun pastedCodeWithSpacesOrTheAppLinkIsAccepted() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        val first = s.start("Pixel")!!
        // A paste that is not a code keeps the sign-in open (ruling R1): the paste field stays, nothing is posted.
        assertEquals(CloudEnableResult.SIGN_IN_FAILED, s.finish("not a code!"))
        assertEquals(SignInState.Waiting(first, rejected = true), s.state.value)
        assertTrue(http.forms.isEmpty())
        assertEquals(CloudEnableResult.ENABLED, s.finish("  1234567 \n"))
        assertEquals("1234567", http.forms.single()["code"])
        // A second sign-in (restoring access) answered by an app link: its state must be this sign-in's.
        http.accessToken = "AT2"
        val second = s.start("Pixel")!!
        val st = query(second)["state"]!!
        val scheme = "yx${TEST_CLOUD.clientId}"
        assertEquals(CloudEnableResult.SIGN_IN_FAILED, s.finish("$scheme://authorize?code=7654321&state=other"))
        assertEquals(SignInState.Waiting(second, rejected = true), s.state.value, "a link of another sign-in is refused, the paste field stays")
        assertEquals(1, http.forms.size)
        assertEquals(CloudEnableResult.RESTORED, s.finish("$scheme://authorize?code=7654321&state=$st"))
        assertEquals(SignInState.Done(CloudEnableResult.RESTORED), s.state.value)
        assertEquals("7654321", http.forms.last()["code"])
        assertNull(CloudSignIn.codeOf("   ", st))
        assertNull(CloudSignIn.codeOf("not a code!", st))
        assertNull(CloudSignIn.codeOf("$scheme://authorize?code=7654321", st), "a link without the state")
    }

    @Test
    fun anErrorFromTheSignInPageEndsTheSignIn() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        s.start("Pixel")!!
        s.refused()
        assertEquals(SignInState.Done(CloudEnableResult.SIGN_IN_FAILED), s.state.value)
        assertEquals(CloudEnableResult.SIGN_IN_EXPIRED, s.finish("1234567"), "the refused sign-in is over")
        assertTrue(http.forms.isEmpty())
        s.dismiss()
        s.refused()
        assertEquals(SignInState.Idle, s.state.value, "nothing pending: nothing changes")
    }

    @Test
    fun anOldPhoneInTheGroupBlocksTurningOn() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        check(kid.pipeline.appendOwn { a -> listOf(a.create { it.copy(heartbeat = Heartbeat(app_version = "0.8.3", protocol_version = 2)) }) })
        mom.store().applyBatch(kid.store().heartbeats())
        val s = mom.signIn(http, { disk })
        assertNull(s.start("Pixel"))
        assertEquals(SignInState.Done(CloudEnableResult.OLD_APP_IN_GROUP), s.state.value)
        assertTrue(disk.files("app:").isEmpty())
    }

    @Test
    fun aChildCannotTurnItOn() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        assertEquals(CloudEnableResult.NOT_ADMIN, kid.signIn(http, { disk }).precheck())
    }

    @Test
    fun aKeyStillHeldByARemovedPhoneIsRotatedFirst() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        mom.group.remove(kid.keys.deviceId)
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        assertEquals(CloudEnableResult.ENABLED, s.finish("1234567"))
        val g = mom.groups.read()!!
        assertEquals(1, GroupKey.currentEpoch(Membership.compute(g.groupId, mom.store().membershipEntries())!!))
        val access = mom.store().entriesAfter(mom.keys.deviceId, 0, 100).last { it.kind == EntryKind.CLOUD_ACCESS }
        assertEquals(1, access.body.cloud_access!!.key_epoch)
        assertEquals(setOf(0, 1), mom.cloudSecrets.ring(g).keys().keys)
    }

    @Test
    fun theOwnerRestoresAccessWithANewSignIn() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        s.finish("1234567")
        http.accessToken = "AT2"
        mom.now += 1_000
        s.start("Pixel")
        assertEquals(CloudEnableResult.RESTORED, s.finish("7654321"))
        val g = mom.groups.read()!!
        val m = Membership.compute(g.groupId, mom.store().membershipEntries())!!
        assertEquals(1L, m.storageOwner!!.generation, "the same storage")
        assertEquals("AT2", mom.cloudSecrets.tokens()!!.accessToken)
        assertEquals("AT2", CloudAccessView.current(mom.store(), m, mom.cloudSecrets.ring(g).keys())!!.accessToken)
    }

    @Test
    fun aRefusedCodeKeepsTheCloudOff() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        // Well formed, so it reaches the token endpoint, which refuses it (ruling R2).
        assertEquals(CloudEnableResult.SIGN_IN_FAILED, s.finish("badcode1"))
        assertEquals("badcode1", http.forms.single()["code"])
        assertEquals(SignInState.Done(CloudEnableResult.SIGN_IN_FAILED), s.state.value)
        val g = mom.groups.read()!!
        assertNull(Membership.compute(g.groupId, mom.store().membershipEntries())!!.storageOwner)
        assertNull(mom.cloudSecrets.tokens())
        assertTrue(disk.files("app:").isEmpty())
        assertEquals(CloudEnableResult.SIGN_IN_EXPIRED, s.finish("1234567"), "a refused code ends the sign-in")
        assertEquals(1, http.forms.size)
    }

    @Test
    fun twoFinishesAtOnceExchangeTheCodeOnce() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        val gate = CompletableDeferred<Unit>().also { http.gate = it }
        // The app link and a paste arrive together.
        val first = async { s.finish("1234567") }
        http.entered.await()
        val second = async { s.finish("7654321") }
        yield()
        assertEquals(SignInState.Working, s.state.value)
        gate.complete(Unit)
        assertEquals(CloudEnableResult.ENABLED, first.await())
        assertEquals(CloudEnableResult.ENABLED, second.await(), "the second one reports the winner's outcome")
        assertEquals(SignInState.Done(CloudEnableResult.ENABLED), s.state.value)
        assertEquals(listOf("1234567"), http.forms.map { it["code"] })
        val g = mom.groups.read()!!
        assertEquals(1, mom.store().membershipEntries().count { it.body.membership_op?.storage_owner != null }, "one storage")
        assertEquals(1, disk.files(CloudPaths.storageIdFile(g.groupId)).size)
    }

    @Test
    fun aWaitingFinishTriesItsOwnCodeWhenTheFirstOneLeftTheSignInOpen() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        val gate = CompletableDeferred<Unit>().also { http.gate = it }
        // The first one meets a busy endpoint (the sign-in stays open); the paste waiting behind it is a good code.
        val first = async { s.finish("busy1234") }
        http.entered.await()
        val second = async { s.finish("1234567") }
        yield()
        gate.complete(Unit)
        assertEquals(CloudEnableResult.NETWORK, first.await())
        assertEquals(CloudEnableResult.ENABLED, second.await(), "the waiting input gets its own try")
        assertEquals(listOf("busy1234", "1234567"), http.forms.map { it["code"] })
        assertEquals(SignInState.Done(CloudEnableResult.ENABLED), s.state.value)
    }

    @Test
    fun aWaitingFinishAfterARefusedCodeReportsTheRefusal() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        val gate = CompletableDeferred<Unit>().also { http.gate = it }
        val first = async { s.finish("badcode1") }
        http.entered.await()
        val second = async { s.finish("1234567") }
        yield()
        gate.complete(Unit)
        assertEquals(CloudEnableResult.SIGN_IN_FAILED, first.await())
        assertEquals(CloudEnableResult.SIGN_IN_FAILED, second.await(), "a refused code ended the sign-in")
        assertEquals(1, http.forms.size)
    }

    @Test
    fun aSignInStartedDuringAFinishIsKept() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        val gate = CompletableDeferred<Unit>().also { http.gate = it }
        val first = async { s.finish("1234567") }
        http.entered.await()
        val again = s.start("Pixel")!!
        gate.complete(Unit)
        assertEquals(CloudEnableResult.ENABLED, first.await())
        assertEquals(SignInState.Waiting(again), s.state.value, "the newer sign-in still waits for its code")
        http.accessToken = "AT2"
        assertEquals(CloudEnableResult.RESTORED, s.finish("7654321"))
        assertEquals("AT2", mom.cloudSecrets.tokens()!!.accessToken)
    }

    /** Cancel on the dialog ends the sign-in: an app link arriving afterwards turns nothing on. */
    @Test
    fun aDismissedSignInIsOver() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        s.dismiss()
        assertEquals(SignInState.Idle, s.state.value)
        assertEquals(CloudEnableResult.SIGN_IN_EXPIRED, s.finish("1234567"))
        assertTrue(http.forms.isEmpty(), "no code exchanged")
        assertNull(mom.cloudSecrets.tokens())
    }

    /** Dismissed while the code is out: the endpoint not answering does not bring the paste field back. */
    @Test
    fun aSignInDismissedDuringTheExchangeDoesNotReopen() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        val gate = CompletableDeferred<Unit>().also { http.gate = it }
        val first = async { s.finish("busy1234") }
        http.entered.await()
        s.dismiss()
        gate.complete(Unit)
        assertEquals(CloudEnableResult.NETWORK, first.await())
        assertEquals(SignInState.Idle, s.state.value)
        assertEquals(CloudEnableResult.SIGN_IN_EXPIRED, s.finish("1234567"), "the sign-in is over")
        assertEquals(1, http.forms.size)
    }

    /** Closure audit (Part 1): a token too large for a CloudAccess is a failed sign-in, not «you are not an admin». */
    @Test
    fun aTokenTooLargeToHandOutFailsTheSignInBeforeAnythingIsWritten() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        http.accessToken = "A".repeat(CloudAccessRules.MAX_CIPHERTEXT_BYTES)
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        assertEquals(CloudEnableResult.SIGN_IN_FAILED, s.finish("1234567"))
        assertNull(mom.cloudSecrets.tokens())
        assertTrue(disk.files("app:").isEmpty(), "no storage.id written")
        val g = mom.groups.read()!!
        assertNull(Membership.compute(g.groupId, mom.store().membershipEntries())!!.storageOwner)
    }

    @Test
    fun noAnswerFromTheTokenEndpointKeepsThePasteField() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        val url = s.start("Pixel")!!
        http.unreachable = true
        assertEquals(CloudEnableResult.NETWORK, s.finish("1234567"))
        assertEquals(SignInState.Waiting(url, networkError = true), s.state.value)
        // Spec §11: a 5xx is retried like no answer.
        assertEquals(CloudEnableResult.NETWORK, s.finish("busy1234"))
        assertEquals(SignInState.Waiting(url, networkError = true), s.state.value)
        // Final review: a 503 whose body names an OAuth error is endpoint trouble too, not a refused code.
        assertEquals(CloudEnableResult.NETWORK, s.finish("down1234"))
        assertEquals(SignInState.Waiting(url, networkError = true), s.state.value)
        assertEquals(CloudEnableResult.ENABLED, s.finish("1234567"), "the same code, sent again")
        assertEquals(SignInState.Done(CloudEnableResult.ENABLED), s.state.value)
    }

    @Test
    fun aDiskFailureAfterTheExchangeEndsTheSignIn() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { FailingDisk(IOException("connection reset")) })
        s.start("Pixel")
        assertEquals(CloudEnableResult.NETWORK, s.finish("1234567"))
        assertEquals(SignInState.Done(CloudEnableResult.NETWORK), s.state.value, "the code is spent: start again")
        assertEquals(CloudEnableResult.SIGN_IN_EXPIRED, s.finish("1234567"))
        assertEquals(1, http.forms.size)
        val g = mom.groups.read()!!
        assertNull(Membership.compute(g.groupId, mom.store().membershipEntries())!!.storageOwner)
        assertNull(mom.cloudSecrets.tokens())
    }

    @Test
    fun anUnexpectedFailureNeverLeavesTheSignInWorking() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { FailingDisk(IllegalStateException("boom")) })
        s.start("Pixel")
        assertEquals(CloudEnableResult.SIGN_IN_FAILED, s.finish("1234567"))
        assertEquals(SignInState.Done(CloudEnableResult.SIGN_IN_FAILED), s.state.value)
        assertNull(mom.cloudSecrets.tokens())
    }

    /** A Disk whose every call fails with [e]. */
    private class FailingDisk(private val e: Exception) : DiskApi {
        override suspend fun list(path: String): List<DiskItem> = throw e
        override suspend fun mkdir(path: String) = throw e
        override suspend fun upload(path: String, bytes: ByteArray) = throw e
        override suspend fun download(path: String): ByteArray = throw e
        override suspend fun delete(path: String) = throw e
    }

    @Test
    fun aSignInLeftOpenTooLongExpires() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        s.start("Pixel")
        mom.now += CloudSignIn.PENDING_MS + 1
        assertEquals(CloudEnableResult.SIGN_IN_EXPIRED, s.finish("1234567"))
        assertTrue(http.forms.isEmpty())
    }
}
