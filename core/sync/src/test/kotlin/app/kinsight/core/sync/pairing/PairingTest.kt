package app.kinsight.core.sync.pairing

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.PairMessage
import app.kinsight.core.model.proto.PairReject
import app.kinsight.core.model.proto.PairRequest
import app.kinsight.core.sync.ByteStream
import app.kinsight.core.sync.MemoryPipe
import app.kinsight.core.sync.SecureChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    private fun guest(i: Invite = invite) = PairingGuest(i, kidKeys, "Kid")

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
    fun wrongTokenIsRejected() = runTest {
        val forged = Invite(invite.protocolVersion, invite.groupId, invite.adminSignKey, invite.adminDhKey, Primitives.randomBytes(16), invite.role, invite.adminName)
        val (h, g) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, { guest(forged).join(it) })
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
        val (h, g) = pair({ host().serve(it, welcomeAs(DeviceRole.CHILD)) }, { stream ->
            val ch = SecureChannel.initiate(stream, kidKeys.dh, PairingProtocol.prologue(invite.groupId))
            val wrongSigner = DeviceKeys.generate().sign
            val proof = wrongSigner.sign(PairingProtocol.proofMessage(invite.token, kidKeys.dh.publicKey))
            PairingProtocol.send(ch, PairMessage(request = PairRequest(invite.token.toByteString(), kidKeys.sign.publicKey.toByteString(), "Kid", proof.toByteString())))
            GuestResult.Rejected(PairingProtocol.receive(ch).reject!!.reason).also { ch.close() }
        })
        assertEquals(GuestResult.Rejected(PairReject.Reason.BAD_PROOF), g.getOrThrow())
        assertIs<HostOutcome.Rejected>(h.getOrThrow())
        assertEquals(0, decided)
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
}
