package app.kinsight.core.data

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.keystore.KeyStorageException
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.sync.pairing.Invite
import app.kinsight.core.transport.ble.PairingRadioException
import app.kinsight.core.transport.ble.RadioProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingCoordinatorTest {
    private val air = FakePairingLink()
    private val mom = TestDevice()
    private val kid = TestDevice()
    private val kid2 = TestDevice()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var momClock = mom.now

    private fun coordinator(d: TestDevice, sync: SyncControl = d.sync, clock: () -> Long = { d.now }) = PairingCoordinator(
        link = air, db = d.db, groups = d.groups, keys = d.keyProvider, pipeline = d.pipeline, settings = d.settings,
        reset = d.reset, sync = sync, snapshots = d.snapshots, clock = clock,
    )

    /** A service that refuses the «sync now» nudge (start), as a dead or restricted service would. */
    private class NudgeFails(private val d: SyncControl) : SyncControl by d {
        override fun start(fast: Boolean) = throw IllegalStateException("service refused")
    }

    private suspend fun ownSharingEvents(d: TestDevice) =
        d.store().stateEvents().count { it.author == d.keys.deviceId && it.body.state_event!!.kind in SHARING }

    private val momPairing = coordinator(mom, clock = { momClock })
    private val kidPairing = coordinator(kid)
    private val kid2Pairing = coordinator(kid2)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        mom.close()
        kid.close()
        kid2.close()
    }

    private suspend fun <T> StateFlow<T>.awaitFirst(predicate: (T) -> Boolean): T = withTimeout(20_000) { first(predicate) }

    private suspend fun openInvite(role: Role = Role.CHILD): Pair<PairingCoordinator.InviteSession, Invite> {
        val session = momPairing.invite(scope, role, "Mom")
        val showing = session.state.awaitFirst { it is InviteState.Showing || it is InviteState.Failed }
        return session to Invite.parse(assertIs<InviteState.Showing>(showing).uri)
    }

    private suspend fun adds(d: TestDevice) =
        d.store().membershipEntries().count { it.kind == EntryKind.MEMBERSHIP_OP && it.body.membership_op!!.add != null }

    @Test
    fun adminInvitesAChildWhoJoinsWithConsent() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (invite, qr) = openInvite()
        assertEquals(qr.toUri(), momPairing.currentInviteUri.value)
        val join = kidPairing.join(scope, qr, "Kid", consent = true)
        val confirm = assertIs<InviteState.Confirm>(invite.state.awaitFirst { it is InviteState.Confirm })
        assertEquals("Kid" to Role.CHILD, confirm.name to confirm.role)
        val waiting = assertIs<JoinState.Waiting>(join.state.awaitFirst { it is JoinState.Waiting })
        assertEquals("Mom", waiting.adminName)
        assertEquals(6, confirm.code.length)
        assertEquals(confirm.code, waiting.code) // the admin compares exactly what the guest's screen shows
        invite.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertEquals(InviteState.Added("Kid"), invite.state.awaitFirst { it is InviteState.Added })
        assertNull(momPairing.currentInviteUri.value)
        assertEquals(0, air.hosting())
        assertEquals(mom.groups.read()!!.groupId, kid.groups.read()!!.groupId)
        assertTrue(kid.groups.read()!!.discoveryKey.contentEquals(mom.groups.read()!!.discoveryKey))
        assertNotNull(kid.settings.current().consentAtMs)
        assertTrue(kid.settings.current().sharing)
        assertTrue(kid.store().stateEvents().any { it.author == kid.keys.deviceId && it.body.state_event!!.kind == StateEvent.Kind.SHARING_RESUMED })
        assertTrue("start:true" in mom.sync.calls)
        assertTrue("start:true" in kid.sync.calls)
    }

    @Test
    fun declineKeepsTheInviteOpen() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (invite, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = true)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.decline()
        assertEquals(JoinState.Failed(PairFailure.DECLINED), join.state.awaitFirst { it is JoinState.Failed || it is JoinState.Joined })
        assertIs<InviteState.Showing>(invite.state.awaitFirst { it is InviteState.Showing })
        assertFalse(kid.groups.exists())
    }

    @Test
    fun expiredInviteFails() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (_, qr) = openInvite()
        momClock += Invite.LIFETIME_MS + 1
        val join = kidPairing.join(scope, qr, "Kid", consent = false)
        assertEquals(JoinState.Failed(PairFailure.EXPIRED), join.state.awaitFirst { it is JoinState.Failed || it is JoinState.Joined })
    }

    @Test
    fun closedInviteIsNotFound() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (invite, qr) = openInvite()
        invite.close()
        val join = kidPairing.join(scope, qr, "Kid", consent = false)
        assertEquals(JoinState.Failed(PairFailure.NOT_FOUND), join.state.awaitFirst { it is JoinState.Failed || it is JoinState.Joined })
    }

    @Test
    fun rejoinAfterLostWelcomeAddsNothing() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        // The first attempt: the admin signed AddMember, the welcome never reached the kid.
        mom.pipeline.appendOwn { a -> listOf(a.addMember(kid.keys.sign.publicKey, kid.keys.dh.publicKey, "Kid", DeviceRole.CHILD)) }
        val (invite, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = false)
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertIs<InviteState.Added>(invite.state.awaitFirst { it is InviteState.Added })
        assertEquals(1, adds(mom))
    }

    @Test
    fun removedDeviceCannotRejoin() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.pipeline.appendOwn { a ->
            listOf(
                a.addMember(kid.keys.sign.publicKey, kid.keys.dh.publicKey, "Kid", DeviceRole.CHILD),
                a.removeMemberEntry(kid.keys.deviceId, 0),
            )
        }
        val (_, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = false)
        assertEquals(JoinState.Failed(PairFailure.REMOVED_BEFORE), join.state.awaitFirst { it is JoinState.Failed || it is JoinState.Joined })
    }

    @Test
    fun joinerAlreadyInAGroupFails() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.group.createGroup("Kid's own group")
        val (_, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = true)
        assertEquals(JoinState.Failed(PairFailure.ALREADY_IN_GROUP), join.state.awaitFirst { it is JoinState.Failed })
    }

    @Test
    fun parentInviteIgnoresConsent() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (invite, qr) = openInvite(Role.PARENT)
        val join = kidPairing.join(scope, qr, "Dad", consent = true)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertNull(kid.settings.current().consentAtMs)
        assertFalse(kid.settings.current().sharing)
    }

    @Test
    fun nonAdminCannotInvite() = runBlocking<Unit> {
        val session = kidPairing.invite(scope, Role.CHILD, "Kid")
        assertEquals(InviteState.Failed(PairFailure.NOT_ADMIN), session.state.awaitFirst { it is InviteState.Failed })
    }

    // Carries from the reviews of tasks 2, 7 and 10.

    @Test
    fun aRadioProblemIsShownWhileTheInviteStaysUsable() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (invite, qr) = openInvite()
        val failed = RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, code = 3)
        air.problem.value = failed
        assertEquals(failed, assertIs<InviteState.Showing>(invite.state.awaitFirst { (it as? InviteState.Showing)?.problem != null }).problem)
        air.problem.value = null
        invite.state.awaitFirst { it is InviteState.Showing && it.problem == null }
        val join = kidPairing.join(scope, qr, "Kid", consent = false)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
    }

    @Test
    fun aRadioThatCannotHostFailsTheInvite() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        air.hostError.set(PairingRadioException(PairingRadioException.Reason.BLUETOOTH_OFF, "Bluetooth is off"))
        val session = momPairing.invite(scope, Role.CHILD, "Mom")
        assertEquals(InviteState.Failed(PairFailure.BLUETOOTH_OFF), session.state.awaitFirst { it is InviteState.Failed || it is InviteState.Showing })
        assertNull(momPairing.currentInviteUri.value)
    }

    @Test
    fun aBlankAdminNameIsInvalidNotARadioFailure() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val session = momPairing.invite(scope, Role.CHILD, " ‮ ")
        assertEquals(InviteState.Failed(PairFailure.INVALID), session.state.awaitFirst { it is InviteState.Failed || it is InviteState.Showing })
        assertEquals(0, air.hosting())
    }

    @Test
    fun aGuestWithAnotherTokenSeesAnInvalidCode() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (invite, qr) = openInvite()
        val forged = Invite(qr.protocolVersion, qr.groupId, qr.adminSignKey, qr.adminDhKey, Primitives.randomBytes(16), qr.role, qr.adminName)
        air.alias(forged.advertTag, qr.advertTag)
        val join = kidPairing.join(scope, forged, "Kid", consent = false)
        assertEquals(JoinState.Failed(PairFailure.BAD_TOKEN), join.state.awaitFirst { it is JoinState.Failed || it is JoinState.Joined })
        assertIs<InviteState.Showing>(invite.state.value)
        assertFalse(kid.groups.exists())
    }

    @Test
    fun aBrokenIdentityFailsTheJoin() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (_, qr) = openInvite()
        kid.identityBroken = KeyStorageException("keystore gone")
        val join = kidPairing.join(scope, qr, "Kid", consent = false)
        assertEquals(JoinState.Failed(PairFailure.IDENTITY), join.state.awaitFirst { it is JoinState.Failed || it is JoinState.Joined })
    }

    @Test
    fun twoGuestsAtOnceGetOneConfirmation() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (invite, qr) = openInvite()
        val a = kidPairing.join(scope, qr, "Kid", consent = false)
        val b = kid2Pairing.join(scope, qr, "Kid2", consent = false)
        val confirm = assertIs<InviteState.Confirm>(invite.state.awaitFirst { it is InviteState.Confirm })
        val (first, second) = if (confirm.name == "Kid") a to b else b to a
        assertEquals(JoinState.Failed(PairFailure.LINK), second.state.awaitFirst { it is JoinState.Failed || it is JoinState.Joined })
        first.state.awaitFirst { it is JoinState.Waiting }
        invite.confirm()
        assertEquals(JoinState.Joined, first.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertEquals(InviteState.Added(confirm.name), invite.state.awaitFirst { it is InviteState.Added })
        assertEquals(1, adds(mom))
    }

    @Test
    fun closingWhileTheAdminDecidesAddsNobody() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (invite, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = false)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.close()
        assertIs<JoinState.Failed>(join.state.awaitFirst { it is JoinState.Failed || it is JoinState.Joined })
        invite.confirm() // too late: nothing to confirm
        assertNull(momPairing.currentInviteUri.value)
        assertEquals(0, air.hosting())
        assertEquals(0, adds(mom))
        assertFalse(kid.groups.exists())
    }

    // Fix round 1.

    @Test
    fun childWithoutConsentDoesNotShare() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (invite, qr) = openInvite(Role.CHILD)
        val join = kidPairing.join(scope, qr, "Kid", consent = false)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertFalse(kid.settings.current().sharing)
        assertNull(kid.settings.current().consentAtMs)
        assertEquals(0, ownSharingEvents(kid))
    }

    @Test
    fun aStoredGroupIsJoinedEvenWhenTheNudgeFails() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (invite, qr) = openInvite()
        val join = coordinator(kid, sync = NudgeFails(kid.sync)).join(scope, qr, "Kid", consent = true)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertTrue(kid.groups.exists())
        assertTrue(kid.settings.current().sharing)
    }

    @Test
    fun anInterruptedConsentIsAppliedAtStart() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (invite, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = false)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        join.state.awaitFirst { it is JoinState.Joined }
        // The state a crash between the consent record and turning sharing on leaves behind.
        kid.settings.recordConsent(kid.now)
        kidPairing.finishInterruptedJoin()
        assertTrue(kid.settings.current().sharing)
        assertEquals(1, ownSharingEvents(kid))
        // A child who paused afterwards stays paused.
        kid.pipeline.setSharing(false)
        kidPairing.finishInterruptedJoin()
        assertFalse(kid.settings.current().sharing)
    }

    @Test
    fun noConsentOrNoGroupRepairsNothing() = runBlocking<Unit> {
        kidPairing.finishInterruptedJoin() // no group
        assertFalse(kid.groups.exists())
        mom.group.createGroup("Mom")
        momPairing.finishInterruptedJoin() // a parent admin, no consent
        mom.settings.recordConsent(mom.now)
        momPairing.finishInterruptedJoin() // a parent never shares, consent or not
        assertFalse(mom.settings.current().sharing)
        assertEquals(0, ownSharingEvents(mom))
    }

    @Test
    fun theEndOfTheScreenScopeClosesTheInvite() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val screen = CoroutineScope(Dispatchers.Default + Job())
        val session = momPairing.invite(screen, Role.CHILD, "Mom")
        session.state.awaitFirst { it is InviteState.Showing }
        assertNotNull(momPairing.currentInviteUri.value)
        screen.cancel()
        withTimeout(5_000) { momPairing.currentInviteUri.first { it == null } }
        assertEquals(0, air.hosting())
    }

    @Test
    fun aFinishedSessionLeavesNothingRunning() = runBlocking<Unit> {
        val failed = CoroutineScope(Dispatchers.Default + Job())
        kidPairing.invite(failed, Role.CHILD, "Kid").state.awaitFirst { it is InviteState.Failed }
        withTimeout(5_000) { while (failed.coroutineContext.job.children.any()) kotlinx.coroutines.delay(10) }

        mom.group.createGroup("Mom")
        val added = CoroutineScope(Dispatchers.Default + Job())
        val invite = momPairing.invite(added, Role.CHILD, "Mom")
        val qr = Invite.parse(assertIs<InviteState.Showing>(invite.state.awaitFirst { it is InviteState.Showing }).uri)
        kidPairing.join(scope, qr, "Kid", consent = false)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        invite.state.awaitFirst { it is InviteState.Added }
        withTimeout(10_000) { while (added.coroutineContext.job.children.any()) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun addedIsShownEvenWhenTheAdminsNudgeFails() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val pairing = coordinator(mom, sync = NudgeFails(mom.sync), clock = { momClock })
        val invite = pairing.invite(scope, Role.CHILD, "Mom")
        val qr = Invite.parse(assertIs<InviteState.Showing>(invite.state.awaitFirst { it is InviteState.Showing }).uri)
        val join = kidPairing.join(scope, qr, "Kid", consent = false)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertEquals(InviteState.Added("Kid"), invite.state.awaitFirst { it is InviteState.Added || it is InviteState.Failed })
        assertNull(pairing.currentInviteUri.value)
    }

    @Test
    fun aGroupMadeWhileWaitingWinsOverTheJoin() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val (invite, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = true)
        invite.state.awaitFirst { it is InviteState.Confirm }
        join.state.awaitFirst { it is JoinState.Waiting }
        kid.group.createGroup("Kid's own group")
        val own = kid.groups.read()!!.groupId
        invite.confirm()
        assertEquals(JoinState.Failed(PairFailure.ALREADY_IN_GROUP), join.state.awaitFirst { it is JoinState.Failed || it is JoinState.Joined })
        assertEquals(own, kid.groups.read()!!.groupId)
        assertNull(kid.settings.current().consentAtMs)
    }

    private companion object {
        val SHARING = setOf(StateEvent.Kind.SHARING_PAUSED, StateEvent.Kind.SHARING_RESUMED)
    }
}
