package app.kinsight.core.data

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.keystore.KeyStorageException
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.sync.pairing.Invite
import app.kinsight.core.transport.ble.PairingRadioException
import app.kinsight.core.transport.ble.RadioProblem
import app.kinsight.core.transport.cloud.GroupKey
import app.kinsight.core.transport.cloud.InMemoryDisk
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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

    private fun coordinator(
        d: TestDevice,
        sync: SyncControl = d.sync,
        clock: () -> Long = { d.now },
        keys: suspend () -> DeviceKeys = d.keyProvider,
        onJoined: () -> Unit = {},
    ) = PairingCoordinator(
        link = air, db = d.db, groups = d.groups, keys = keys, pipeline = d.pipeline, settings = d.settings,
        reset = d.reset, sync = sync, snapshots = d.snapshots, cloud = d.cloudSecrets, onJoined = onJoined, clock = clock,
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
    fun aJoinerGetsTheAccessAndTheKeyInTheWelcome() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT")
        val (invite, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = true)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        val t = kid.cloudSecrets.tokens()!!
        assertEquals("AT", t.accessToken)
        assertFalse(t.owner)
        assertNull(t.refreshToken)
        val g = kid.groups.read()!!
        assertEquals(setOf(0), kid.cloudSecrets.ring(g).keys().keys)
        assertNotNull(Membership.compute(g.groupId, kid.store().membershipEntries())!!.storageOwner)
    }

    /** Review Minor 3: the welcome token is stored before the group file, so the cloud is scheduled once the join is done. */
    @Test
    fun aJoinSchedulesTheCloudOnceTheGroupFileIsWritten() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT")
        val groupFileAtHook = mutableListOf<Boolean>()
        val pairing = coordinator(kid, onJoined = { groupFileAtHook += kid.groups.exists() })
        val (invite, qr) = openInvite()
        val join = pairing.join(scope, qr, "Kid", consent = true)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertEquals(listOf(true), groupFileAtHook)
        assertTrue(kid.runner(InMemoryDisk()).isOn(), "the welcome token makes the cloud on for the joiner")
    }

    @Test
    fun theAddMemberRecordsTheKeyGenerationHandedInTheWelcome() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val g = mom.groups.read()!!
        val before = Membership.compute(g.groupId, mom.store().membershipEntries())!!
        check(mom.pipeline.appendOwn { a -> listOf(GroupKey.rotate(a, before)) })
        val (epoch, key) = GroupKey.open(mom.keys, Membership.compute(g.groupId, mom.store().membershipEntries())!!)!!
        mom.cloudSecrets.ring(g).put(epoch, key)
        val (invite, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = true)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertNull(kid.cloudSecrets.tokens(), "no storage owner: no access")
        val ring = kid.cloudSecrets.ring(kid.groups.read()!!).keys()
        assertEquals(setOf(0, 1), ring.keys)
        assertContentEquals(key, ring.getValue(1))
        val m = Membership.compute(g.groupId, mom.store().membershipEntries())!!
        val add = m.effectiveEntries.mapNotNull { it.body.membership_op?.add }.single { it.display_name == "Kid" }
        assertEquals(1, add.group_key_epoch)
        assertTrue(GroupKey.membersWithoutKey(m).isEmpty(), "the kid got generation 1 in the welcome: no rotation needed")
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
    fun aPendingResetFinishesBeforeTheJoin() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.resetMarker.set()
        val oldKid = kid.keys.deviceId
        val (invite, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = false)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertEquals(1, kid.identityResets.size) // the keys were replaced before the join
        assertNotEquals(oldKid, kid.keys.deviceId)
        assertTrue(kid.keys.deviceId in Membership.compute(mom.groups.read()!!.groupId, mom.store().membershipEntries())!!.active)
        assertFalse(kid.resetMarker.exists())
        assertEquals(mom.groups.read()!!.groupId, kid.groups.read()!!.groupId)
    }

    /** The bug seen on a phone: a child left, kept its DeviceId and was refused REMOVED_BEFORE by the next invitation. */
    @Test
    fun leavingEndsWithANewIdentitySoAParentInviteJoinsAgain() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.prefs.setDeviceName("Kid")
        val (first, childQr) = openInvite(Role.CHILD)
        val joined = kidPairing.join(scope, childQr, "Kid", consent = true)
        first.state.awaitFirst { it is InviteState.Confirm }
        first.confirm()
        assertEquals(JoinState.Joined, joined.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        val oldKid = kid.keys.deviceId

        val leave = kid.group.leaveAndForget()
        withTimeout(5_000) {
            while (kid.sessions.subscriptionCount.value == 0 ||
                kid.store().stateEvents().none { it.author == oldKid && it.body.state_event!!.kind == StateEvent.Kind.LEFT_GROUP }
            ) kotlinx.coroutines.delay(10)
        }
        // The session that carries LEFT_GROUP: the admin receives the kid's log, then the kid's session counter moves.
        mom.store().applyBatch(kid.store().entriesAfter(oldKid, 0, 1_000))
        kid.sessions.value = SessionProgress(finished = 1, syncing = false)
        withTimeout(10_000) { leave.join() }
        assertNull(kid.group.leaveError.value)

        assertNotEquals(oldKid, kid.keys.deviceId, "leaving must end with a fresh identity")
        assertEquals(1, kid.identityResets.size)
        assertFalse(kid.resetMarker.exists())
        assertFalse(kid.groups.exists())
        assertEquals(Prefs(deviceName = "Kid"), kid.prefs.current()) // the name the user typed stays
        val groupId = mom.groups.read()!!.groupId
        assertTrue(oldKid in Membership.compute(groupId, mom.store().membershipEntries())!!.departed)

        val (second, parentQr) = openInvite(Role.PARENT)
        val rejoin = kidPairing.join(scope, parentQr, "Kid", consent = false)
        second.state.awaitFirst { it is InviteState.Confirm || it is InviteState.Failed }
        second.confirm()
        assertEquals(JoinState.Joined, rejoin.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        val member = Membership.compute(groupId, mom.store().membershipEntries())!!.active[kid.keys.deviceId]
        assertNotNull(member)
        assertEquals(DeviceRole.PARENT, member.role)
        assertEquals(groupId, kid.groups.read()!!.groupId)
    }

    @Test
    fun aRemovedDeviceResetsItsIdentityAndRetriesTheSameInvite() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.pipeline.appendOwn { a ->
            listOf(
                a.addMember(kid.keys.sign.publicKey, kid.keys.dh.publicKey, "Kid", DeviceRole.CHILD),
                a.removeMemberEntry(kid.keys.deviceId, 0),
            )
        }
        kid.prefs.setDeviceName("Kid")
        val oldKid = kid.keys.deviceId
        val (invite, qr) = openInvite()
        val refused = kidPairing.join(scope, qr, "Kid", consent = false)
        assertEquals(JoinState.Failed(PairFailure.REMOVED_BEFORE), refused.state.awaitFirst { it is JoinState.Failed || it is JoinState.Joined })
        val retry = kidPairing.join(scope, qr, "Kid", consent = false, freshIdentity = true)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Joined, retry.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertNotEquals(oldKid, kid.keys.deviceId)
        assertEquals(1, kid.identityResets.size)
        assertEquals("Kid", kid.prefs.current().deviceName)
        assertTrue(kid.keys.deviceId in Membership.compute(mom.groups.read()!!.groupId, mom.store().membershipEntries())!!.active)
    }

    @Test
    fun cancellingAFreshIdentityJoinMidResetStillFinishesTheReset() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.prefs.setDeviceName("Kid")
        val oldKid = kid.keys.deviceId
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        kid.onKeyWipe = {
            entered.countDown()
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        val (_, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = false, freshIdentity = true)
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(kid.resetMarker.exists()) // mid-reset: a cancellable one would be cut short here
        join.cancel() // «Отмена», or leaving the screen
        release.countDown()
        withTimeout(10_000) { while (kid.resetMarker.exists() || kid.reset.status.value != ResetStatus.Idle) kotlinx.coroutines.delay(10) }
        assertEquals(ResetStatus.Idle, kid.reset.status.value) // no RESET_FAILED
        assertTrue(kid.reset.dismissible)
        assertNotEquals(oldKid, kid.keys.deviceId)
        assertEquals(1, kid.identityResets.size)
        assertEquals(Prefs(deviceName = "Kid"), kid.prefs.current())
        assertFalse(kid.groups.exists())
        kotlinx.coroutines.delay(200)
        assertIs<JoinState.Searching>(join.state.value) // the cancel took effect before the dial
        assertEquals(0, adds(mom))
    }

    @Test
    fun aFreshIdentityRetryNeverWipesAGroup() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.group.createGroup("Kid")
        val (_, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = false, freshIdentity = true)
        assertEquals(JoinState.Failed(PairFailure.ALREADY_IN_GROUP), join.state.awaitFirst { it is JoinState.Failed || it is JoinState.Joined })
        assertTrue(kid.groups.exists())
        assertTrue(kid.identityResets.isEmpty())
    }

    @Test
    fun aKeyChangeBetweenHandshakeAndCommitRefusesTheJoin() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val first = kid.keys
        var calls = 0
        // The second look at the keys (at commit) finds another identity: a reset ran after the handshake.
        val pairing = coordinator(kid, keys = { if (calls++ == 0) first else DeviceKeys.generate() })
        val (invite, qr) = openInvite()
        val join = pairing.join(scope, qr, "Kid", consent = false)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Failed(PairFailure.IDENTITY), join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertFalse(kid.groups.exists())
    }

    @Test
    fun aMarkerAppearingBeforeCommitRefusesTheJoin() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        var calls = 0
        val first = kid.keys
        val pairing = coordinator(kid, keys = { if (calls++ == 0) first.also { kid.resetMarker.set() } else first })
        val (invite, qr) = openInvite()
        val join = pairing.join(scope, qr, "Kid", consent = false)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Failed(PairFailure.IDENTITY), join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertFalse(kid.groups.exists())
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
