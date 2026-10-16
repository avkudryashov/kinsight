package app.kinsight.core.sync.pairing

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.addMember
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.storageOwnerEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.PairMessage
import app.kinsight.core.model.proto.PairPending
import app.kinsight.core.model.proto.PairReject
import app.kinsight.core.model.proto.PairRequest
import app.kinsight.core.sync.ByteStream
import app.kinsight.core.sync.HandedAccess
import app.kinsight.core.sync.MemoryPipe
import app.kinsight.core.sync.SecureChannel
import app.kinsight.core.sync.WelcomeCloud
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PairingTest {
    private val adminKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(adminKeys, "Mom")
    private val admin = EntryAuthor(genesis.groupId, adminKeys, lastSeq = 1, lastLamport = genesis.entry.lamport)
    private val discoveryKey = Primitives.randomBytes(32)
    private val invite = Invite.create(genesis.groupId, adminKeys, DeviceRole.CHILD, "Mom")
    private val kidKeys = DeviceKeys.generate()
    private var now = 1_000_000L
    private var decided = 0

    private fun host(expiresAt: Long = now + Invite.LIFETIME_MS, dh: DeviceKeys = adminKeys) =
        PairingHost(invite, dh.dh, expiresAt, clock = { now })

    private fun welcomeAs(role: DeviceRole): suspend (Joiner) -> HostDecision = { j ->
        decided++
        val add = admin.addMember(j.signPublicKey, j.dhPublicKey, j.displayName, role)
        HostDecision.Welcome(discoveryKey, listOf(genesis.entry.bytes, add.bytes))
    }

    private suspend fun pair(
        hostSide: suspend (ByteStream) -> HostOutcome,
        guestSide: suspend (ByteStream) -> GuestResult,
    ): Pair<Result<HostOutcome>, Result<GuestResult>> = coroutineScope {
        val (a, b) = MemoryPipe.pair()
        val h = async { runCatching { hostSide(a) } }
        val g = async { runCatching { guestSide(b) } }
        h.await() to g.await()
    }

    private fun guest(i: Invite = invite, keys: DeviceKeys = kidKeys, name: String = "Kid") = PairingGuest(i, keys, name)

    /** A hand-written guest: right prologue, then [request] instead of a well-formed PairRequest. */
    private fun rawGuest(dh: DeviceKeys, request: PairMessage): suspend (ByteStream) -> GuestResult = { stream ->
        val ch = SecureChannel.initiate(stream, dh.dh, PairingProtocol.prologue(invite.groupId, invite.token))
        PairingProtocol.send(ch, request)
        GuestResult.Rejected(PairingProtocol.receive(ch).reject!!.reason).also { ch.close() }
    }

    private fun requestOf(token: ByteArray, signer: DeviceKeys, provenDh: ByteArray, name: String = "Kid"): PairMessage {
        val proof = signer.sign.sign(PairingProtocol.proofMessage(token, provenDh))
        return PairMessage(request = PairRequest(token.toByteString(), signer.sign.publicKey.toByteString(), name, proof.toByteString()))
    }

    @Test
    fun bothSidesDeriveTheSameCodeAndEachHandshakeItsOwn() = runTest {
        var guestCode = ""
        val (h, g) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, { guest().join(it) { code -> guestCode = code } })
        assertIs<GuestResult.Joined>(g.getOrThrow())
        val hostCode = assertIs<HostOutcome.Welcomed>(h.getOrThrow()).joiner.sas
        assertEquals(PairingSas.DIGITS, hostCode.length)
        assertEquals(hostCode, guestCode)

        // Another handshake (fresh ephemerals) shows another code: one seen earlier cannot be replayed. Equal by chance
        // with probability 10^-6.
        var otherCode = ""
        val otherKid = DeviceKeys.generate()
        val (h2, g2) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, { guest(keys = otherKid).join(it) { code -> otherCode = code } })
        assertIs<GuestResult.Joined>(g2.getOrThrow())
        assertEquals(assertIs<HostOutcome.Welcomed>(h2.getOrThrow()).joiner.sas, otherCode)
        assertNotEquals(hostCode, otherCode)
    }

    @Test
    fun guestJoinsAfterTheAdminAccepts() = runTest {
        var pending = false
        val (h, g) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, { guest().join(it) { pending = true } })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertTrue(pending)
        assertContentEquals(discoveryKey, joined.discoveryKey)
        assertTrue(joined.state.active.getValue(kidKeys.deviceId).isChild)
        assertEquals(2, joined.membership.size)
        assertEquals("Kid", assertIs<HostOutcome.Welcomed>(h.getOrThrow()).joiner.displayName)
    }

    @Test
    fun wrongTokenFailsTheHandshake() = runTest {
        val forged = Invite(invite.protocolVersion, invite.groupId, invite.adminSignKey, invite.adminDhKey, Primitives.randomBytes(16), invite.role, invite.adminName)
        val (h, g) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, { guest(forged).join(it) })
        assertTrue(g.isFailure)
        assertTrue(h.isFailure)
        assertEquals(0, decided)
    }

    @Test
    fun requestWithAnotherTokenIsRejected() = runTest {
        val other = Primitives.randomBytes(16)
        val (h, g) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, rawGuest(kidKeys, requestOf(other, kidKeys, kidKeys.dh.publicKey)))
        assertEquals(GuestResult.Rejected(PairReject.Reason.BAD_TOKEN), g.getOrThrow())
        assertEquals(PairReject.Reason.BAD_TOKEN, assertIs<HostOutcome.Rejected>(h.getOrThrow()).reason)
        assertEquals(0, decided)
    }

    @Test
    fun expiredInviteIsRejected() = runTest {
        val (_, g) = pair({ host(expiresAt = now - 1).serve(it, welcomeAs(DeviceRole.CHILD)) }, { guest().join(it) })
        assertEquals(GuestResult.Rejected(PairReject.Reason.EXPIRED), g.getOrThrow())
        assertEquals(0, decided)
    }

    @Test
    fun expiryIsCheckedAgainBeforeTheWelcome() = runTest {
        val h0 = host()
        val (h, g) = pair({
            h0.serve(it) { j -> now += Invite.LIFETIME_MS + 1; welcomeAs(DeviceRole.CHILD)(j) }
        }, { guest().join(it) })
        assertEquals(GuestResult.Rejected(PairReject.Reason.EXPIRED), g.getOrThrow())
        assertEquals(PairReject.Reason.EXPIRED, assertIs<HostOutcome.Rejected>(h.getOrThrow()).reason)
    }

    @Test
    fun noDecisionInTimeIsExpired() = runTest {
        val (h, g) = pair({ host().serve(it) { awaitCancellation() } }, { guest().join(it) })
        assertEquals(GuestResult.Rejected(PairReject.Reason.EXPIRED), g.getOrThrow())
        assertEquals(PairReject.Reason.EXPIRED, assertIs<HostOutcome.Rejected>(h.getOrThrow()).reason)
        assertTrue(testScheduler.currentTime >= PairingProtocol.DECISION_TIMEOUT.inWholeMilliseconds)
    }

    @Test
    fun declineIsReported() = runTest {
        val (h, g) = pair({ host().serve(it) { HostDecision.Reject(PairReject.Reason.DECLINED) } }, { guest().join(it) })
        assertEquals(GuestResult.Rejected(PairReject.Reason.DECLINED), g.getOrThrow())
        assertEquals("Kid", assertIs<HostOutcome.Rejected>(h.getOrThrow()).joiner!!.displayName)
    }

    @Test
    fun guestRefusesAHostThatIsNotTheAdmin() = runTest {
        val (_, g) = pair({ host(dh = DeviceKeys.generate()).serve(it, welcomeAs(DeviceRole.CHILD)) }, { guest().join(it) })
        val e = assertIs<PairingException>(g.exceptionOrNull())
        assertEquals(PairingException.Kind.WRONG_ADMIN, e.kind)
        assertEquals(0, decided)
    }

    @Test
    fun forgedProofIsRejected() = runTest {
        val wrongSigner = DeviceKeys.generate()
        val proof = wrongSigner.sign.sign(PairingProtocol.proofMessage(invite.token, kidKeys.dh.publicKey))
        val request = PairMessage(request = PairRequest(invite.token.toByteString(), kidKeys.sign.publicKey.toByteString(), "Kid", proof.toByteString()))
        val (h, g) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, rawGuest(kidKeys, request))
        assertEquals(GuestResult.Rejected(PairReject.Reason.BAD_PROOF), g.getOrThrow())
        assertIs<HostOutcome.Rejected>(h.getOrThrow())
        assertEquals(0, decided)
    }

    @Test
    fun replayedProofOverAnotherStaticKeyIsRejected() = runTest {
        val attacker = DeviceKeys.generate()
        val replayed = requestOf(invite.token, kidKeys, kidKeys.dh.publicKey) // the kid's genuine proof
        val (h, g) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, rawGuest(attacker, replayed))
        assertEquals(GuestResult.Rejected(PairReject.Reason.BAD_PROOF), g.getOrThrow())
        assertEquals(PairReject.Reason.BAD_PROOF, assertIs<HostOutcome.Rejected>(h.getOrThrow()).reason)
        assertEquals(0, decided)
    }

    @Test
    fun invalidNamesAreRejected() = runTest {
        for (name in listOf("", "  ", "Ki‮d", "Ki\u0000d", "x".repeat(65))) {
            val (h, g) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, { guest(name = name).join(it) })
            assertEquals(GuestResult.Rejected(PairReject.Reason.NAME_INVALID), g.getOrThrow(), name)
            assertEquals(PairReject.Reason.NAME_INVALID, assertIs<HostOutcome.Rejected>(h.getOrThrow()).reason)
        }
        assertEquals(0, decided)
    }

    @Test
    fun firstMessageMustBeARequest() = runTest {
        val (h, g) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, rawGuest(kidKeys, PairMessage(pending = PairPending())))
        assertEquals(GuestResult.Rejected(PairReject.Reason.REASON_UNSPECIFIED), g.getOrThrow())
        val r = assertIs<HostOutcome.Rejected>(h.getOrThrow())
        assertEquals(PairReject.Reason.REASON_UNSPECIFIED, r.reason)
        assertNull(r.joiner)
        assertEquals(0, decided)
    }

    @Test
    fun guestThatStallsAfterTheHandshakeIsDropped() = runTest {
        val (h, _) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, { stream ->
            val ch = SecureChannel.initiate(stream, kidKeys.dh, PairingProtocol.prologue(invite.groupId, invite.token))
            runCatching { ch.receive() } // never sends a request; the host must hang up
            GuestResult.Rejected(PairReject.Reason.REASON_UNSPECIFIED)
        })
        assertIs<IOException>(h.exceptionOrNull())
        val t = testScheduler.currentTime
        assertTrue(t >= PairingProtocol.REQUEST_TIMEOUT.inWholeMilliseconds && t < PairingProtocol.DECISION_TIMEOUT.inWholeMilliseconds, "t=$t")
        assertEquals(0, decided)
    }

    @Test
    fun tokenIsBoundToTheFirstWelcomedKey() = runTest {
        val h0 = host()
        val (h1, _) = pair({ h0.serve(it, welcomeAs(DeviceRole.CHILD)) }, { guest().join(it) })
        assertIs<HostOutcome.Welcomed>(h1.getOrThrow())
        val (h2, g2) = pair({ h0.serve(it, welcomeAs(DeviceRole.CHILD)) }, { guest(keys = DeviceKeys.generate(), name = "Other").join(it) })
        assertEquals(GuestResult.Rejected(PairReject.Reason.BAD_TOKEN), g2.getOrThrow())
        assertEquals(PairReject.Reason.BAD_TOKEN, assertIs<HostOutcome.Rejected>(h2.getOrThrow()).reason)
        assertEquals(1, decided)
        // the same key again still reaches decide: the coordinator answers it with a re-welcome
        val (h3, g3) = pair({ h0.serve(it, welcomeAs(DeviceRole.CHILD)) }, { guest().join(it) })
        assertIs<HostOutcome.Welcomed>(h3.getOrThrow())
        assertIs<GuestResult.Joined>(g3.getOrThrow())
        assertEquals(2, decided)
    }

    @Test
    fun welcomeWithoutTheGuestIsInvalid() = runTest {
        val (_, g) = pair({ host().serve(it) { HostDecision.Welcome(discoveryKey, listOf(genesis.entry.bytes)) } }, { guest().join(it) })
        assertEquals(PairingException.Kind.INVALID_WELCOME, assertIs<PairingException>(g.exceptionOrNull()).kind)
    }

    @Test
    fun welcomeWithAnotherRoleIsInvalid() = runTest {
        val (_, g) = pair({ host().serve(it, welcomeAs(DeviceRole.PARENT)) }, { guest().join(it) })
        assertEquals(PairingException.Kind.INVALID_WELCOME, assertIs<PairingException>(g.exceptionOrNull()).kind)
    }

    @Test
    fun welcomeWhereTheAdminHasAnotherDhKeyIsInvalid() = runTest {
        val otherDh = DeviceKeys.generate()
        val skewed = Invite(invite.protocolVersion, invite.groupId, invite.adminSignKey, otherDh.dh.publicKey, invite.token, invite.role, invite.adminName)
        val (_, g) = pair({
            PairingHost(skewed, otherDh.dh, now + Invite.LIFETIME_MS, clock = { now }).serve(it, welcomeAs(DeviceRole.CHILD))
        }, { guest(skewed).join(it) })
        assertEquals(PairingException.Kind.INVALID_WELCOME, assertIs<PairingException>(g.exceptionOrNull()).kind)
    }

    @Test
    fun largeMembershipIsPaged() = runTest {
        val others = (1..300).map { i ->
            val k = DeviceKeys.generate()
            admin.addMember(k.sign.publicKey, k.dh.publicKey, "m$i".padEnd(60, 'x'), DeviceRole.CHILD).bytes
        }
        assertTrue(others.sumOf { it.size } > SecureChannel.MAX_PLAINTEXT) // one page could not carry it
        val (_, g) = pair({
            host().serve(it) { j ->
                val add = admin.addMember(j.signPublicKey, j.dhPublicKey, j.displayName, DeviceRole.CHILD)
                HostDecision.Welcome(discoveryKey, listOf(genesis.entry.bytes) + others + add.bytes)
            }
        }, { guest().join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertEquals(302, joined.membership.size)
        assertFalse(joined.state.active.isEmpty())
    }

    @Test
    fun lingerDoesNotSwallowCancellation() = runTest {
        val (a, b) = MemoryPipe.pair()
        var after = false
        val hostJob = launch {
            host().serve(a, welcomeAs(DeviceRole.CHILD))
            after = true
        }
        val ch = SecureChannel.initiate(b, kidKeys.dh, PairingProtocol.prologue(invite.groupId, invite.token))
        PairingProtocol.send(ch, PairMessage(pending = PairPending()))
        assertNotNull(PairingProtocol.receive(ch).reject) // the host now lingers; we keep the link open
        runCurrent()
        hostJob.cancel()
        hostJob.join()
        assertFalse(after)
    }

    private fun welcomeWithCloud(cloud: WelcomeCloud, extra: List<ByteArray>): suspend (Joiner) -> HostDecision = { j ->
        val add = admin.addMember(j.signPublicKey, j.dhPublicKey, j.displayName, DeviceRole.CHILD)
        HostDecision.Welcome(discoveryKey, listOf(genesis.entry.bytes) + extra + add.bytes, cloud)
    }

    @Test
    fun theWelcomeCarriesTheStorageAccess() = runTest {
        val storageId = ByteArray(16) { 3 }
        val owner = admin.storageOwnerEntry(adminKeys.deviceId, storageId, 1)
        val (_, g) = pair({ host().serve(it, welcomeWithCloud(WelcomeCloud(0, null, HandedAccess(1, storageId, "AT", 99)), listOf(owner.bytes))) }, { guest().join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertEquals("AT", joined.cloud!!.access!!.accessToken)
        assertEquals(0, joined.cloud!!.keyEpoch)
        assertNull(joined.cloud!!.groupKey)
    }

    @Test
    fun accessForAnotherStorageIsDropped() = runTest {
        val owner = admin.storageOwnerEntry(adminKeys.deviceId, ByteArray(16) { 3 }, 1)
        val (_, g) = pair({ host().serve(it, welcomeWithCloud(WelcomeCloud(0, null, HandedAccess(1, ByteArray(16) { 4 }, "AT", 99)), listOf(owner.bytes))) }, { guest().join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertNull(joined.cloud!!.access)
    }

    @Test
    fun accessFromAnAdminThatDoesNotOwnTheStorageIsDropped() = runTest {
        // R3: only the storage owner hands the token; here the owner is another parent, not the inviting admin.
        val dadKeys = DeviceKeys.generate()
        val storageId = ByteArray(16) { 3 }
        val addDad = admin.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad", DeviceRole.PARENT)
        val grant = admin.grantAdminEntry(dadKeys.deviceId)
        val owner = admin.storageOwnerEntry(dadKeys.deviceId, storageId, 1)
        val cloud = WelcomeCloud(0, null, HandedAccess(1, storageId, "AT", 99))
        val (_, g) = pair({ host().serve(it, welcomeWithCloud(cloud, listOf(addDad.bytes, grant.bytes, owner.bytes))) }, { guest().join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertEquals(dadKeys.deviceId, joined.state.storageOwner?.deviceId, "the fixture names Dad as the owner")
        assertNull(joined.cloud!!.access)
    }

    @Test
    fun aGroupKeyOfAGenerationTheMembershipDoesNotHaveIsDropped() = runTest {
        val (_, g) = pair({ host().serve(it, welcomeWithCloud(WelcomeCloud(1, ByteArray(32) { 5 }, null), emptyList())) }, { guest().join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertEquals(0, joined.cloud!!.keyEpoch, "generation 1 does not exist in this membership")
        assertNull(joined.cloud!!.groupKey)
    }

    /** Final review Minor 3: an owner without the newest generation still hands its token; only the key is dropped. */
    @Test
    fun theOwnersAccessStaysWhenItsKeyIsNotTheNewest() = runTest {
        val storageId = ByteArray(16) { 3 }
        val owner = admin.storageOwnerEntry(adminKeys.deviceId, storageId, 1)
        val cloud = WelcomeCloud(1, ByteArray(32) { 5 }, HandedAccess(1, storageId, "AT", 99))
        val (_, g) = pair({ host().serve(it, welcomeWithCloud(cloud, listOf(owner.bytes))) }, { guest().join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertEquals("AT", joined.cloud!!.access!!.accessToken)
        assertEquals(0, joined.cloud!!.keyEpoch)
        assertNull(joined.cloud!!.groupKey)
    }

    @Test
    fun aWelcomeWithoutCloudFieldsJoinsOnGenerationZero() = runTest {
        val (_, g) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, { guest().join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertEquals(0, joined.cloud!!.keyEpoch)
        assertNull(joined.cloud!!.groupKey)
        assertNull(joined.cloud!!.access)
    }

    @Test
    fun aFullFirstPageStillFitsTheCloudFields() = runTest {
        // Enough membership for a second page, and the largest token HandedAccess allows, in 3-byte UTF-8 chars.
        val others = (0 until 400).map { n ->
            val k = DeviceKeys.generate()
            admin.addMember(k.sign.publicKey, k.dh.publicKey, "Kid $n".padEnd(60, 'x'), DeviceRole.CHILD).bytes
        }
        assertTrue(others.sumOf { it.size } > PairingProtocol.PAGE_BYTES, "the fixture must page")
        val storageId = ByteArray(16) { 3 }
        val owner = admin.storageOwnerEntry(adminKeys.deviceId, storageId, 1)
        val token = "\u0436".repeat(HandedAccess.MAX_TOKEN_CHARS)
        val cloud = WelcomeCloud(0, null, HandedAccess(1, storageId, token, 99))
        val (_, g) = pair({ host().serve(it, welcomeWithCloud(cloud, listOf(owner.bytes) + others)) }, { guest().join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertEquals(token, joined.cloud!!.access!!.accessToken)
    }

    private val parentInvite = Invite.create(genesis.groupId, adminKeys, DeviceRole.PARENT, "Mom")

    private fun parentHost() = PairingHost(parentInvite, adminKeys.dh, now + Invite.LIFETIME_MS, clock = { now })

    /** The owner's transfer welcome (spec §7.3): its storage, the joiner as a parent, made an admin when [makeAdmin]. */
    private fun transfer(makeAdmin: Boolean, cloud: WelcomeCloud, extra: List<ByteArray> = emptyList()): suspend (Joiner) -> HostDecision = { j ->
        val owner = admin.storageOwnerEntry(adminKeys.deviceId, ByteArray(16) { 3 }, 1)
        val add = admin.addMember(j.signPublicKey, j.dhPublicKey, j.displayName, DeviceRole.PARENT)
        val grant = if (makeAdmin) listOf(admin.grantAdminEntry(j.id).bytes) else emptyList()
        HostDecision.Welcome(discoveryKey, listOf(genesis.entry.bytes, owner.bytes) + extra + add.bytes + grant, cloud)
    }

    private val handed = WelcomeCloud(0, null, HandedAccess(1, ByteArray(16) { 3 }, "AT", 99), refreshToken = "RT")

    @Test
    fun aTransferWelcomeHandsTheRefreshTokenToTheNewAdmin() = runTest {
        val (_, g) = pair({ parentHost().serve(it, transfer(makeAdmin = true, handed)) }, { guest(parentInvite, name = "Pixel").join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertEquals("RT", joined.cloud?.refreshToken)
        assertEquals("AT", joined.cloud?.access?.accessToken)
        assertFalse("RT" in joined.cloud.toString())
    }

    @Test
    fun onlyATransferWelcomeCarriesTheRefreshToken() = runTest {
        // Review Focus 5: a phone the welcome did not make an admin never keeps the refresh token.
        val (_, g) = pair({ parentHost().serve(it, transfer(makeAdmin = false, handed)) }, { guest(parentInvite, name = "Pixel").join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertNull(joined.cloud?.refreshToken)
        assertEquals("AT", joined.cloud?.access?.accessToken, "the access itself is the owner's, as before")
    }

    @Test
    fun aRefreshTokenWithoutTheOwnersAccessIsDropped() = runTest {
        val otherStorage = WelcomeCloud(0, null, HandedAccess(1, ByteArray(16) { 4 }, "AT", 99), refreshToken = "RT")
        val (_, g) = pair({ parentHost().serve(it, transfer(makeAdmin = true, otherStorage)) }, { guest(parentInvite, name = "Pixel").join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertNull(joined.cloud?.access)
        assertNull(joined.cloud?.refreshToken)
    }

    @Test
    fun aRefreshTokenWithoutAnyAccessIsNotSent() = runTest {
        val noAccess = WelcomeCloud(0, null, null, refreshToken = "RT")
        val (_, g) = pair({ parentHost().serve(it, transfer(makeAdmin = true, noAccess)) }, { guest(parentInvite, name = "Pixel").join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertNull(joined.cloud?.refreshToken)
    }

    @Test
    fun aFullFirstPageStillFitsBothTokens() = runTest {
        // The largest access and refresh tokens, each in 3-byte UTF-8 chars, over a membership that pages: the first
        // frame must stay under SecureChannel.MAX_PLAINTEXT (the channel refuses a larger one).
        val others = (0 until 400).map { n ->
            val k = DeviceKeys.generate()
            admin.addMember(k.sign.publicKey, k.dh.publicKey, "Kid $n".padEnd(60, 'x'), DeviceRole.CHILD).bytes
        }
        assertTrue(others.sumOf { it.size } > PairingProtocol.PAGE_BYTES, "the fixture must page")
        val access = "\u20ac".repeat(HandedAccess.MAX_TOKEN_CHARS)
        val refresh = "\u20ad".repeat(HandedAccess.MAX_TOKEN_CHARS)
        assertEquals(3 * HandedAccess.MAX_TOKEN_CHARS, refresh.encodeToByteArray().size)
        val cloud = WelcomeCloud(0, null, HandedAccess(1, ByteArray(16) { 3 }, access, 99), refreshToken = refresh)
        val (_, g) = pair({ parentHost().serve(it, transfer(makeAdmin = true, cloud, others)) }, { guest(parentInvite, name = "Pixel").join(it) })
        val joined = assertIs<GuestResult.Joined>(g.getOrThrow())
        assertEquals(access, joined.cloud!!.access!!.accessToken)
        assertEquals(refresh, joined.cloud!!.refreshToken)
    }
}
