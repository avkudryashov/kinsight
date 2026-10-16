package app.kinsight.core.data

import app.kinsight.core.crypto.Primitives
import kotlin.time.Duration.Companion.seconds
import okio.ByteString.Companion.toByteString
import app.kinsight.core.transport.cloud.TokenSet
import app.kinsight.core.sync.pairing.PairingProtocol
import app.kinsight.core.sync.SecureChannel
import app.kinsight.core.model.proto.PairRequest
import app.kinsight.core.model.proto.PairMessage
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.keystore.KeyStorageException
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.PackFile
import app.kinsight.core.model.proto.PackHeader
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.sync.pairing.Invite
import app.kinsight.core.transport.ble.PairingRadioException
import app.kinsight.core.transport.ble.RadioProblem
import app.kinsight.core.transport.cloud.CloudPaths
import app.kinsight.core.transport.cloud.GroupKey
import app.kinsight.core.transport.cloud.InMemoryDisk
import app.kinsight.core.transport.cloud.OwnerLifecycle
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
    private val mom = TestDevice(leaveTimeoutMs = 300) // the automatic leave (spec §7.3) does not wait a minute for a session
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
        ownerTransfer: OwnerTransfer? = null,
    ) = PairingCoordinator(
        link = air, db = d.db, groups = d.groups, keys = keys, pipeline = d.pipeline, settings = d.settings,
        reset = d.reset, sync = sync, snapshots = d.snapshots, cloud = d.cloudSecrets, onJoined = onJoined, clock = clock,
        ownerTransfer = ownerTransfer,
    )

    /** A service that refuses the «sync now» nudge (start), as a dead or restricted service would. */
    private class NudgeFails(private val d: SyncControl) : SyncControl by d {
        override fun start(fast: Boolean) = throw IllegalStateException("service refused")
    }

    private suspend fun ownSharingEvents(d: TestDevice) =
        d.store().stateEvents().count { it.author == d.keys.deviceId && it.body.state_event!!.kind in SHARING }

    private val momTransfer = OwnerTransfer(mom.db, mom.groups, mom.keyProvider, mom.cloudState, leave = { mom.group.leaveAndForget().join() }, clock = { momClock })
    private val momPairing = coordinator(mom, clock = { momClock }, ownerTransfer = momTransfer)
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

    /**
     * Final review Minor 6, end to end over one InMemoryDisk: the real sign-in turns the cloud on with a rotation to
     * generation 1 (a departed phone held generation 0), the real welcome hands a kid that key and the owner's token, the
     * kid's runner uploads sealed with generation 1, and Mom reads the kid's entry from the Disk.
     */
    @Test
    fun theCloudTurnedOnWithARotationReachesAKidPairedAfterIt() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid2.joinGroupOf(mom)
        mom.group.remove(kid2.keys.deviceId)
        val disk = InMemoryDisk()
        val signIn = mom.signIn(FakeOAuthHttp(), { disk })
        signIn.start("Pixel")
        assertEquals(CloudEnableResult.ENABLED, signIn.finish("1234567"))
        val g = mom.groups.read()!!
        assertEquals(1, GroupKey.currentEpoch(Membership.compute(g.groupId, mom.store().membershipEntries())!!))
        val (invite, qr) = openInvite()
        val join = kidPairing.join(scope, qr, "Kid", consent = true)
        invite.state.awaitFirst { it is InviteState.Confirm }
        invite.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertEquals(setOf(0, 1), kid.cloudSecrets.ring(kid.groups.read()!!).keys().keys)
        assertEquals("AT1", kid.cloudSecrets.tokens()!!.accessToken)
        val day = kid.ownSummary("2026-10-13", 60_000)
        assertEquals(CloudRun.SYNCED, kid.runner(disk).run(CloudTrigger.NOW))
        val pack = disk.files(CloudPaths.deviceDir(g.groupId, kid.keys.deviceId)).values.single()
        assertEquals(1, PackHeader.ADAPTER.decode(PackFile.ADAPTER.decode(pack).header_).key_epoch, "sealed with generation 1")
        assertEquals(CloudRun.SYNCED, mom.runner(disk).run(CloudTrigger.NOW))
        assertTrue(day in mom.store().liveEntries())
        assertNull(mom.cloudState.current().problem)
        assertNull(kid.cloudState.current().problem)
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

    @Test
    fun theOwnersNewPhoneTakesTheRoleByQr() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT")
        val session = momPairing.invite(scope, Role.PARENT, "Mom", transferOwner = true)
        val qr = Invite.parse(assertIs<InviteState.Showing>(session.state.awaitFirst { it is InviteState.Showing || it is InviteState.Failed }).uri)
        val join = kidPairing.join(scope, qr, "Pixel", consent = false) // here the "kid" device plays Mom's new phone
        session.state.awaitFirst { it is InviteState.Confirm }
        session.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertEquals(InviteState.Added("Pixel", ownerTransferred = true), session.state.awaitFirst { it is InviteState.Added })
        val g = kid.groups.read()!!
        val m = Membership.compute(g.groupId, kid.store().membershipEntries())!!
        assertEquals(kid.keys.deviceId to 2L, m.storageOwner!!.deviceId to m.storageOwner!!.generation)
        assertContentEquals(ByteArray(16) { 1 }, m.storageOwner!!.storageId.toByteArray())
        assertTrue(m.active[kid.keys.deviceId]!!.isAdmin)
        val before = Membership.compute(g.groupId, mom.store().membershipEntries())!!.storageOwner!!
        assertEquals(before.tokenId, m.storageOwner!!.tokenId, "Q3: the same token keeps its id")
        assertEquals(before.departed, m.storageOwner!!.departed, "Q3: and its departed list")
        val t = kid.cloudSecrets.tokens()!!
        assertTrue(t.owner)
        assertEquals(Triple("AT", "RT", 2L), Triple(t.accessToken, t.refreshToken, t.storageGeneration), "0 taps: the same tokens (spec §7.3)")
        // The old phone learns it, gives the role up and keeps nothing to revoke: the same token goes on.
        mom.store().storeMembership(kid.store().membershipEntries())
        mom.store().applyBatch(kid.store().liveEntries().filter { it.author == kid.keys.deviceId })
        assertEquals(CloudRun.SYNCED, mom.runner(InMemoryDisk()).run(CloudTrigger.NOW))
        assertFalse(mom.cloudSecrets.tokens()?.owner ?: false)
        assertTrue(mom.cloudSecrets.retired(g.groupId).isEmpty())
        val momState = Membership.compute(g.groupId, mom.store().membershipEntries())!!
        assertNull(OwnerLifecycle.leaveBlock(momState, mom.keys.deviceId, emptyList()), "the old phone may leave the group now")
    }

    @Test
    fun onlyTheStorageOwnerOpensATransferInvite() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val session = momPairing.invite(scope, Role.PARENT, "Mom", transferOwner = true)
        assertEquals(InviteState.Failed(PairFailure.NOT_ADMIN), session.state.awaitFirst { it is InviteState.Showing || it is InviteState.Failed })
    }

    @Test
    fun aTransferInviteIsForAParentOnly() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT")
        val session = momPairing.invite(scope, Role.CHILD, "Mom", transferOwner = true)
        assertEquals(InviteState.Failed(PairFailure.NOT_ADMIN), session.state.awaitFirst { it is InviteState.Showing || it is InviteState.Failed })
    }

    @Test
    fun aParentInviteFromTheOwnerHandsNoRefreshToken() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT")
        val session = momPairing.invite(scope, Role.PARENT, "Mom")
        val qr = Invite.parse(assertIs<InviteState.Showing>(session.state.awaitFirst { it is InviteState.Showing || it is InviteState.Failed }).uri)
        val join = kidPairing.join(scope, qr, "Dad", consent = false)
        session.state.awaitFirst { it is InviteState.Confirm }
        session.confirm()
        assertEquals(JoinState.Joined, join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertEquals(InviteState.Added("Dad"), session.state.awaitFirst { it is InviteState.Added })
        val t = kid.cloudSecrets.tokens()!!
        assertFalse(t.owner)
        assertNull(t.refreshToken)
        assertEquals(mom.keys.deviceId, Membership.compute(kid.groups.read()!!.groupId, kid.store().membershipEntries())!!.storageOwner!!.deviceId)
    }

    /** Mom's transfer invite, the kid device playing her new phone; [beforeConfirm] runs while the joiner waits. */
    private suspend fun transferByQr(beforeConfirm: () -> Unit = {}): Pair<InviteState, JoinState> {
        val session = momPairing.invite(scope, Role.PARENT, "Mom", transferOwner = true)
        val qr = Invite.parse(assertIs<InviteState.Showing>(session.state.awaitFirst { it is InviteState.Showing || it is InviteState.Failed }).uri)
        val join = kidPairing.join(scope, qr, "Pixel", consent = false)
        join.state.awaitFirst { it is JoinState.Waiting }
        session.state.awaitFirst { it is InviteState.Confirm }
        beforeConfirm()
        session.confirm()
        val joined = join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed }
        return session.state.awaitFirst { it is InviteState.Added } to joined
    }

    private suspend fun momStatus() = CloudViews.status(mom.snapshots.load(emptyMap()), mom.cloudState.current(), true, momClock)

    @Test
    fun theOldPhoneLeavesByItselfOnceTheNewOneHoldsTheRole() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT", tokenId = ByteArray(16) { 9 })
        assertEquals(InviteState.Added("Pixel", ownerTransferred = true) to JoinState.Joined, transferByQr())
        assertEquals(OwnerTransferPhase.PENDING, momTransfer.check(), "the old phone has not seen the new StorageOwner yet")
        assertEquals(OwnerTransferPhase.PENDING, momStatus().ownerTransfer)
        val g = mom.groups.read()!!
        val momId = mom.keys.deviceId
        val newPhone = kid.keys.deviceId
        // As the identity wipe starts the data is still there: hand Mom's log to the new phone, as the leave's session would.
        mom.onKeyWipe = {
            runBlocking {
                val momStore = RoomEntryStore(mom.db, g.groupId)
                kid.store().storeMembership(momStore.membershipEntries())
                kid.store().applyBatch(momStore.liveEntries().filter { it.author == momId })
            }
        }
        mom.store().storeMembership(kid.store().membershipEntries())
        mom.store().applyBatch(kid.store().liveEntries().filter { it.author == newPhone })
        assertEquals(OwnerTransferPhase.DONE, momStatus().ownerTransfer)
        assertEquals(OwnerTransferPhase.DONE, momTransfer.check())
        assertNull(mom.groups.read(), "0 taps: the old phone left the group (spec §7.3)")
        assertNotEquals(momId, mom.keys.deviceId)
        assertNull(mom.cloudState.current().transfer)
        val m = Membership.compute(g.groupId, kid.store().membershipEntries())!!
        assertTrue(momId in m.departed, "the new phone sees the old one gone")
        assertEquals(newPhone, m.storageOwner!!.deviceId)
        assertFalse(OwnerLifecycle.tokenExposed(m), "Q6: the old phone left by itself after its own transfer: the token goes on")
        assertEquals("AT", kid.cloudSecrets.tokens()!!.accessToken)
    }

    @Test
    fun anIncompleteTransferLeavesTheOldPhoneTheOwner() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT")
        // The new phone cannot store the welcome (an identity reset turned up): it never signs the role and says so.
        val (added, joined) = transferByQr(beforeConfirm = { kid.resetMarker.set() })
        assertEquals(JoinState.Failed(PairFailure.IDENTITY), joined)
        assertEquals(InviteState.Added("Pixel", ownerTransferred = false), added)
        assertNull(momTransfer.check(), "PairAck(false): no StorageOwner of the new phone will come, the record goes")
        assertNull(mom.cloudState.current().transfer)
        assertNull(momStatus().ownerTransfer)
        val g = mom.groups.read()!!
        assertEquals(mom.keys.deviceId, Membership.compute(g.groupId, mom.store().membershipEntries())!!.storageOwner!!.deviceId)
        assertTrue(mom.cloudSecrets.tokens()!!.owner)
    }

    @Test
    fun aLostAckKeepsTheTransferRecordForAWeek() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT")
        val session = momPairing.invite(scope, Role.PARENT, "Mom", transferOwner = true)
        val qr = Invite.parse(assertIs<InviteState.Showing>(session.state.awaitFirst { it is InviteState.Showing || it is InviteState.Failed }).uri)
        // A Y3 guest that takes the welcome and hangs up before its PairAck (the link dropped).
        val stream = air.dial(qr.advertTag, 5.seconds)
        val ch = SecureChannel.initiate(stream, kid.keys.dh, PairingProtocol.prologue(qr.groupId, qr.token))
        val proof = kid.keys.sign.sign(PairingProtocol.proofMessage(qr.token, kid.keys.dh.publicKey))
        val request = PairRequest(qr.token.toByteString(), kid.keys.sign.publicKey.toByteString(), "Pixel", proof.toByteString(), protocol_version = 3)
        ch.send(PairMessage.ADAPTER.encode(PairMessage(request = request)))
        assertNotNull(PairMessage.ADAPTER.decode(ch.receive()).pending)
        session.state.awaitFirst { it is InviteState.Confirm }
        session.confirm()
        assertEquals("RT", assertNotNull(PairMessage.ADAPTER.decode(ch.receive()).welcome).refresh_token)
        ch.close()
        assertEquals(InviteState.Added("Pixel", ownerTransferred = false), session.state.awaitFirst { it is InviteState.Added })
        assertEquals(OwnerTransferPhase.PENDING, momTransfer.check())
        momClock += Invite.LIFETIME_MS + 1
        assertEquals(OwnerTransferPhase.PENDING, momTransfer.check(), "a lost PairAck does not end the record with the invite")
        momClock += OwnerTransfer.RECORD_MS
        assertNull(momTransfer.check(), "7 days without the new StorageOwner: the record goes")
        assertNull(mom.cloudState.current().transfer)
        assertEquals(mom.groups.read()!!.groupId, qr.groupId, "nothing else happens: the old phone stays in the group")
        assertTrue(mom.cloudSecrets.tokens()!!.owner)
    }

    @Test
    fun aTransferWelcomeRefusedAfterTheRecordDropsIt() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT")
        val session = momPairing.invite(scope, Role.PARENT, "Mom", transferOwner = true)
        val qr = Invite.parse(assertIs<InviteState.Showing>(session.state.awaitFirst { it is InviteState.Showing || it is InviteState.Failed }).uri)
        val join = kidPairing.join(scope, qr, "Pixel", consent = false)
        session.state.awaitFirst { it is InviteState.Confirm }
        momClock += Invite.LIFETIME_MS + 1 // the admin confirms too late: the host's expiry re-check refuses the welcome
        session.confirm()
        assertEquals(JoinState.Failed(PairFailure.EXPIRED), join.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        session.state.awaitFirst { it is InviteState.Showing } // the host is done with the refusal
        assertNull(mom.cloudState.current().transfer, "no tokens went out: no transfer to wait for")
        assertTrue(mom.cloudSecrets.tokens()!!.owner)
    }

    @Test
    fun aTransferInviteOfAnOwnerWithoutItsRefreshTokenFails() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT")
        mom.cloudSecrets.saveOwner(mom.groups.read()!!.groupId, 1, ByteArray(16) { 1 }, TokenSet("AT", null, mom.now + 86_400_000L))
        val session = momPairing.invite(scope, Role.PARENT, "Mom", transferOwner = true)
        assertEquals(InviteState.Failed(PairFailure.NOT_ADMIN), session.state.awaitFirst { it is InviteState.Showing || it is InviteState.Failed })
    }

    @Test
    fun anExistingParentReWelcomedOnATransferQrGetsNoRole() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT")
        val plain = momPairing.invite(scope, Role.PARENT, "Mom")
        val plainQr = Invite.parse(assertIs<InviteState.Showing>(plain.state.awaitFirst { it is InviteState.Showing || it is InviteState.Failed }).uri)
        val first = kidPairing.join(scope, plainQr, "Pixel", consent = false)
        plain.state.awaitFirst { it is InviteState.Confirm }
        plain.confirm()
        assertEquals(JoinState.Joined, first.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        mom.group.grantAdmin(kid.keys.deviceId) // an admin parent already: only the host's own rule keeps the role back
        kid.groups.delete() // the welcome as if lost: the same keys come back on the transfer QR
        val session = momPairing.invite(scope, Role.PARENT, "Mom", transferOwner = true)
        val qr = Invite.parse(assertIs<InviteState.Showing>(session.state.awaitFirst { it is InviteState.Showing || it is InviteState.Failed }).uri)
        val again = kidPairing.join(scope, qr, "Pixel", consent = false)
        assertEquals(JoinState.Joined, again.state.awaitFirst { it is JoinState.Joined || it is JoinState.Failed })
        assertEquals(InviteState.Added("Pixel", ownerTransferred = false), session.state.awaitFirst { it is InviteState.Added })
        val m = Membership.compute(kid.groups.read()!!.groupId, kid.store().membershipEntries())!!
        assertEquals(mom.keys.deviceId, m.storageOwner!!.deviceId, "no Confirm in this session: no transfer")
        assertNull(kid.cloudSecrets.tokens()?.refreshToken)
        assertNull(mom.cloudState.current().transfer)
    }

    @Test
    fun aGuestOlderThanY3GetsAPlainParentAdd() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT")
        val session = momPairing.invite(scope, Role.PARENT, "Mom", transferOwner = true)
        val qr = Invite.parse(assertIs<InviteState.Showing>(session.state.awaitFirst { it is InviteState.Showing || it is InviteState.Failed }).uri)
        // A 0.8.x guest: no protocol_version in its request.
        val stream = air.dial(qr.advertTag, 5.seconds)
        val ch = SecureChannel.initiate(stream, kid.keys.dh, PairingProtocol.prologue(qr.groupId, qr.token))
        val proof = kid.keys.sign.sign(PairingProtocol.proofMessage(qr.token, kid.keys.dh.publicKey))
        ch.send(PairMessage.ADAPTER.encode(PairMessage(request = PairRequest(qr.token.toByteString(), kid.keys.sign.publicKey.toByteString(), "Pixel", proof.toByteString()))))
        assertNotNull(PairMessage.ADAPTER.decode(ch.receive()).pending)
        session.state.awaitFirst { it is InviteState.Confirm }
        session.confirm()
        val w = assertNotNull(PairMessage.ADAPTER.decode(ch.receive()).welcome)
        ch.close()
        assertEquals("", w.refresh_token)
        assertEquals("AT", w.access_token, "the owner's access as for any parent")
        assertEquals(InviteState.Added("Pixel", ownerTransferred = false), session.state.awaitFirst { it is InviteState.Added })
        val m = Membership.compute(qr.groupId, w.entries.map { DecodedEntry.decode(it.toByteArray()) })!!
        assertFalse(m.active[kid.keys.deviceId]!!.isAdmin)
        assertNull(mom.cloudState.current().transfer)
    }

    @Test
    fun aStorageOwnerArrivingAfterALostAckStillMakesTheOldPhoneLeave() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.ownStorage(token = "AT", tokenId = ByteArray(16) { 9 })
        kid.joinAsAdminOf(mom, name = "Pixel")
        val g = mom.groups.read()!!
        val inForce = Membership.compute(g.groupId, mom.store().membershipEntries())!!.storageOwner!!
        // The welcome went out (recorded), the new phone signed the role, but its PairAck never reached the old phone.
        momTransfer.record(g.groupId, kid.keys.deviceId, inForce)
        val kidGroup = kid.groups.read()!!
        assertNotNull(
            OwnerRecords.claim(
                { build -> kid.pipeline.appendOwn(build = build) }, kid.store(), kid.keys, kid.cloudSecrets.ring(kidGroup),
                ByteArray(16) { 1 }, 2, "AT", kid.now + 86_400_000L, tokenId = inForce.tokenId.toByteArray(), departed = inForce.departed,
            ),
        )
        momClock += 2 * 86_400_000L // well after the invite: the role arrives later over Bluetooth or the cloud
        assertEquals(OwnerTransferPhase.PENDING, momTransfer.check())
        val momId = mom.keys.deviceId
        mom.onKeyWipe = {
            runBlocking {
                val momStore = RoomEntryStore(mom.db, g.groupId)
                kid.store().storeMembership(momStore.membershipEntries())
                kid.store().applyBatch(momStore.liveEntries().filter { it.author == momId })
            }
        }
        mom.store().storeMembership(kid.store().membershipEntries())
        mom.store().applyBatch(kid.store().liveEntries().filter { it.author == kid.keys.deviceId })
        assertEquals(OwnerTransferPhase.DONE, momTransfer.check())
        assertNull(mom.groups.read(), "the old phone left by itself")
        val m = Membership.compute(g.groupId, kid.store().membershipEntries())!!
        assertTrue(momId in m.departed)
        assertFalse(OwnerLifecycle.tokenExposed(m))
    }

    /**
     * Final review, Important 2: Mom removed a phone and signed in again (its old token waits for the revoke), then moves to
     * her new phone by QR before the other kid switched. The new phone takes over the pending revoke; the old one leaves.
     */
    @Test
    fun aTransferHandsThePendingRevokeToTheNewPhone() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid2.joinGroupOf(mom)
        mom.ownStorage(token = "AT", tokenId = ByteArray(16) { 9 })
        val g = mom.groups.read()!!
        val lost = DeviceKeys.generate()
        check(mom.pipeline.appendOwn { a -> listOf(a.addMember(lost.sign.publicKey, lost.dh.publicKey, "Tablet", DeviceRole.CHILD)) })
        check(mom.pipeline.appendOwn { a -> listOf(a.removeMemberEntry(lost.deviceId, 0)) })
        // What the sign-in after the removal leaves (§7.4): generation 2 with a new token, the old one retired.
        val storage = ByteArray(16) { 1 }
        val year = 365 * 86_400_000L
        assertNotNull(OwnerRecords.claim(mom.appendOwn, mom.store(), mom.keys, mom.cloudSecrets.ring(g), storage, 2, "AT2", mom.now + year))
        mom.cloudSecrets.saveOwner(g.groupId, 2, storage, TokenSet("AT2", "RT2", mom.now + year))
        mom.cloudSecrets.retire(g.groupId, 1, "AT")
        assertEquals(InviteState.Added("Pixel", ownerTransferred = true) to JoinState.Joined, transferByQr())
        val handed = kid.cloudSecrets.retired(g.groupId)
        assertEquals(listOf(1L to "AT"), handed.map { it.generation to it.accessToken }, "the pending revoke went with the role")
        // The old phone sees the new owner and leaves by itself, its own copy of the list with it.
        val momId = mom.keys.deviceId
        mom.onKeyWipe = {
            runBlocking {
                val momStore = RoomEntryStore(mom.db, g.groupId)
                kid.store().storeMembership(momStore.membershipEntries())
                kid.store().applyBatch(momStore.liveEntries().filter { it.author == momId })
            }
        }
        mom.store().storeMembership(kid.store().membershipEntries())
        mom.store().applyBatch(kid.store().liveEntries().filter { it.author == kid.keys.deviceId })
        assertEquals(OwnerTransferPhase.DONE, momTransfer.check())
        assertNull(mom.groups.read())
        // The new phone revokes the removed phone's token once the other kid reports the new storage generation.
        val http = FakeOAuthHttp()
        val disks = AccountDisks(mapOf("AT2" to InMemoryDisk()))
        kid.store().storeMembership(kid2.store().membershipEntries())
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk, http).run(CloudTrigger.NOW))
        assertTrue(http.urls.none { it.endsWith("/revoke_token") }, "the other kid has not switched yet")
        kid2.store().storeMembership(kid.store().membershipEntries())
        kid.store().applyBatch(listOf(kid2.beat(3)))
        assertEquals(CloudRun.SYNCED, kid.runner(disks::disk, http).run(CloudTrigger.NOW))
        assertEquals(1, http.urls.count { it.endsWith("/revoke_token") })
        assertEquals("AT", http.forms.last()["access_token"])
        assertTrue(kid.cloudSecrets.retired(g.groupId).isEmpty())
    }
}
