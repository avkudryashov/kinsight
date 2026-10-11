package app.kinsight.core.sync.pairing

import app.kinsight.core.crypto.DhKey
import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipLimits
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.PairMessage
import app.kinsight.core.model.proto.PairPending
import app.kinsight.core.model.proto.PairReject
import app.kinsight.core.model.proto.PairRequest
import app.kinsight.core.model.proto.PairWelcome
import app.kinsight.core.sync.ByteStream
import app.kinsight.core.sync.SecureChannel
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A pairing attempt that failed outside the protocol's own refusals: [Kind.WRONG_ADMIN] (guest: the host's static key
 * is not the QR's admin key), [Kind.MALFORMED] (either side: an undecodable or unexpected message) and
 * [Kind.INVALID_WELCOME] (guest: the welcome is not a valid membership with this device under the QR's role).
 */
class PairingException(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind { WRONG_ADMIN, MALFORMED, INVALID_WELCOME }
}

/** A device asking to join; [dhPublicKey] is its Noise static key, proven by the handshake; [sas] the channel's code. */
class Joiner(val signPublicKey: ByteArray, val dhPublicKey: ByteArray, val displayName: String, val sas: String = "") {
    val id: DeviceId = DeviceId.fromSignPublicKey(signPublicKey)
}

sealed interface HostDecision {
    /** [membership]: SignedEntry bytes of the group's effective membership, the guest's AddMember included. */
    class Welcome(val discoveryKey: ByteArray, val membership: List<ByteArray>) : HostDecision

    data class Reject(val reason: PairReject.Reason) : HostDecision
}

sealed interface HostOutcome {
    class Welcomed(val joiner: Joiner) : HostOutcome

    class Rejected(val reason: PairReject.Reason, val joiner: Joiner?) : HostOutcome
}

sealed interface GuestResult {
    class Joined(val discoveryKey: ByteArray, val membership: List<DecodedEntry>, val state: MembershipState) : GuestResult

    data class Rejected(val reason: PairReject.Reason) : GuestResult
}

object PairingProtocol {
    /** Entries per PairWelcome page, below SecureChannel.MAX_PLAINTEXT with room for framing. */
    const val PAGE_BYTES = 60_000
    val DECISION_TIMEOUT: Duration = 3.minutes
    val REQUEST_TIMEOUT: Duration = 20.seconds
    private val PROOF_LABEL = "kinsight-pair-v1".encodeToByteArray()

    /** Never equal to SecureChannel.prologue: a pairing handshake cannot be mistaken for a sync session. */
    private val PROLOGUE_LABEL = "kinsight-pair-prologue".encodeToByteArray()

    /**
     * Never equal to SecureChannel.prologue: a pairing handshake cannot be mistaken for a sync session. The token's
     * MAC makes a device without the QR fail the handshake itself (bounded by SecureChannel.HANDSHAKE_TIMEOUT).
     */
    fun prologue(groupId: GroupId, token: ByteArray): ByteArray =
        "kinsight-pair".encodeToByteArray() + groupId.bytes() + ByteBuffer.allocate(4).putInt(SecureChannel.PROTOCOL_FAMILY).array() +
            Primitives.hmacSha256(token, PROLOGUE_LABEL)

    fun proofMessage(token: ByteArray, dhPublicKey: ByteArray): ByteArray = PROOF_LABEL + token + dhPublicKey

    internal suspend fun send(ch: SecureChannel, m: PairMessage) = ch.send(PairMessage.ADAPTER.encode(m))

    internal suspend fun receive(ch: SecureChannel): PairMessage {
        val bytes = ch.receive()
        return try {
            PairMessage.ADAPTER.decode(bytes)
        } catch (e: Exception) {
            throw PairingException(PairingException.Kind.MALFORMED, "malformed pairing message", e)
        }
    }

    /** Lets the peer read our last frame before the link closes: wait (bounded) for it to hang up first. */
    internal suspend fun linger(ch: SecureChannel) {
        withTimeoutOrNull(5.seconds) {
            try {
                while (true) ch.receive()
            } catch (e: CancellationException) {
                throw e // our own timeout ends the wait; an outer cancellation must propagate
            } catch (_: Exception) {
                // the peer hung up: done
            }
        }
    }
}

/**
 * The admin's side of one pairing attempt (spec §6.4): responder of a Noise XX handshake with the pairing prologue,
 * checks expiry, token, proof of the guest's signing key and its name, answers PairPending, asks [decide] and sends
 * the welcome (paged) or the refusal. The stream is always closed on return; link errors propagate, and a guest that
 * sends no request within [PairingProtocol.REQUEST_TIMEOUT] after the handshake gets an IOException.
 *
 * One instance serves one invite and may serve several connections. Once a joiner has been welcomed, the token is bound
 * to its X25519 key: any other key presenting the token is refused with BAD_TOKEN before [decide] is asked, while the
 * same key again still reaches [decide], which must answer it with a re-welcome (no new AddMember). Expiry is checked on
 * the request and again right before a welcome is sent. The joiner carries the channel's SAS ([PairingSas]) for the
 * admin's confirmation.
 */
class PairingHost(
    private val invite: Invite,
    private val dhKey: DhKey,
    private val expiresAtMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val decisionTimeout: Duration = PairingProtocol.DECISION_TIMEOUT,
) {
    private val boundKey = AtomicReference<ByteArray?>(null)

    suspend fun serve(stream: ByteStream, decide: suspend (Joiner) -> HostDecision): HostOutcome {
        try {
            val ch = SecureChannel.accept(stream, dhKey, PairingProtocol.prologue(invite.groupId, invite.token))
            val first = withTimeoutOrNull(PairingProtocol.REQUEST_TIMEOUT) { PairingProtocol.receive(ch) }
                ?: throw IOException("no pairing request within ${PairingProtocol.REQUEST_TIMEOUT}")
            val request = first.request ?: return reject(ch, PairReject.Reason.REASON_UNSPECIFIED, null)
            val joiner = Joiner(request.sign_public_key.toByteArray(), ch.remoteStaticKey, request.display_name, PairingSas.code(ch.handshakeHash))
            problem(request, ch.remoteStaticKey)?.let { return reject(ch, it, joiner) }
            PairingProtocol.send(ch, PairMessage(pending = PairPending()))
            return when (val d = withTimeoutOrNull(decisionTimeout) { decide(joiner) } ?: HostDecision.Reject(PairReject.Reason.EXPIRED)) {
                is HostDecision.Reject -> reject(ch, d.reason, joiner)
                is HostDecision.Welcome -> {
                    if (clock() > expiresAtMs) return reject(ch, PairReject.Reason.EXPIRED, joiner)
                    if (!bind(joiner.dhPublicKey)) return reject(ch, PairReject.Reason.BAD_TOKEN, joiner)
                    sendWelcome(ch, d)
                    PairingProtocol.linger(ch)
                    HostOutcome.Welcomed(joiner)
                }
            }
        } finally {
            stream.close()
        }
    }

    private fun problem(r: PairRequest, guestDh: ByteArray): PairReject.Reason? {
        val token = r.token.toByteArray()
        val sign = r.sign_public_key.toByteArray()
        return when {
            clock() > expiresAtMs -> PairReject.Reason.EXPIRED
            token.size != Invite.TOKEN_BYTES || !MessageDigest.isEqual(token, invite.token) -> PairReject.Reason.BAD_TOKEN
            boundKey.get()?.contentEquals(guestDh) == false -> PairReject.Reason.BAD_TOKEN
            sign.size != 32 || !verifies(sign, PairingProtocol.proofMessage(invite.token, guestDh), r.proof.toByteArray()) ->
                PairReject.Reason.BAD_PROOF
            r.display_name.isBlank() || !MembershipLimits.nameOk(r.display_name) || PairingNames.hasUnsafe(r.display_name) ->
                PairReject.Reason.NAME_INVALID
            else -> null
        }
    }

    /** Binds the token to [dh] on the first welcome; true when it is (now) bound to [dh]. */
    private fun bind(dh: ByteArray): Boolean =
        boundKey.compareAndSet(null, dh.copyOf()) || boundKey.get()!!.contentEquals(dh)

    private fun verifies(key: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        runCatching { Ed25519.verify(key, message, signature) }.getOrDefault(false)

    private suspend fun reject(ch: SecureChannel, reason: PairReject.Reason, joiner: Joiner?): HostOutcome {
        PairingProtocol.send(ch, PairMessage(reject = PairReject(reason)))
        PairingProtocol.linger(ch)
        return HostOutcome.Rejected(reason, joiner)
    }

    private suspend fun sendWelcome(ch: SecureChannel, w: HostDecision.Welcome) {
        val pages = mutableListOf<List<ByteArray>>()
        var page = mutableListOf<ByteArray>()
        var size = 0
        for (e in w.membership) {
            if (page.isNotEmpty() && size + e.size > PairingProtocol.PAGE_BYTES) {
                pages += page
                page = mutableListOf()
                size = 0
            }
            page += e
            size += e.size + 8 // field tag and length per entry
        }
        pages += page
        pages.forEachIndexed { i, p ->
            val welcome = PairWelcome(
                discovery_key = if (i == 0) w.discoveryKey.toByteString() else ByteString.EMPTY,
                entries = p.map { it.toByteString() },
                more = i < pages.lastIndex,
            )
            PairingProtocol.send(ch, PairMessage(welcome = welcome))
        }
    }
}

/**
 * The new device's side: initiator of the handshake, refuses any host whose static key is not the QR's admin key,
 * proves its signing key, waits (bounded) for the admin's decision and checks the welcome: a valid membership of the
 * QR's group with this device active under its own keys and the QR's role, the inviting admin active under the QR's
 * keys. Each wait for the admin (PairPending, then the decision) is bounded by [decisionTimeout]. The stream is always
 * closed on return.
 */
class PairingGuest(
    private val invite: Invite,
    private val keys: DeviceKeys,
    private val displayName: String,
    private val decisionTimeout: Duration = PairingProtocol.DECISION_TIMEOUT + 30.seconds,
) {
    /** [onPending] gets this channel's SAS ([PairingSas]) once the admin answered «wait»: the screen shows it to compare. */
    suspend fun join(stream: ByteStream, onPending: (sas: String) -> Unit = {}): GuestResult {
        try {
            val ch = SecureChannel.initiate(stream, keys.dh, PairingProtocol.prologue(invite.groupId, invite.token))
            if (!ch.remoteStaticKey.contentEquals(invite.adminDhKey)) {
                throw PairingException(PairingException.Kind.WRONG_ADMIN, "the device is not the admin from the QR code")
            }
            val proof = keys.sign.sign(PairingProtocol.proofMessage(invite.token, keys.dh.publicKey))
            val request = PairRequest(
                token = invite.token.toByteString(),
                sign_public_key = keys.sign.publicKey.toByteString(),
                display_name = displayName,
                proof = proof.toByteString(),
            )
            PairingProtocol.send(ch, PairMessage(request = request))
            val first = withTimeoutOrNull(decisionTimeout) { PairingProtocol.receive(ch) }
                ?: throw IOException("no answer from the admin within $decisionTimeout")
            first.reject?.let { return GuestResult.Rejected(it.reason) }
            if (first.pending == null) throw PairingException(PairingException.Kind.MALFORMED, "expected PairPending")
            onPending(PairingSas.code(ch.handshakeHash))
            val result = withTimeoutOrNull(decisionTimeout) { readDecision(ch) }
                ?: throw IOException("no decision from the admin within $decisionTimeout")
            ch.close()
            return result
        } finally {
            stream.close()
        }
    }

    private suspend fun readDecision(ch: SecureChannel): GuestResult {
        var key: ByteArray? = null
        val raw = mutableListOf<ByteArray>()
        while (true) {
            val m = PairingProtocol.receive(ch)
            m.reject?.let { return GuestResult.Rejected(it.reason) }
            val w = m.welcome ?: throw PairingException(PairingException.Kind.MALFORMED, "expected PairWelcome")
            if (key == null) key = w.discovery_key.toByteArray()
            w.entries.mapTo(raw) { it.toByteArray() }
            if (!w.more) break
        }
        return validate(key!!, raw)
    }

    private fun validate(discoveryKey: ByteArray, raw: List<ByteArray>): GuestResult.Joined {
        fun invalid(why: String): Nothing = throw PairingException(PairingException.Kind.INVALID_WELCOME, why)
        if (discoveryKey.size != 32) invalid("discovery key must be 32 bytes")
        val entries = raw.map { runCatching { DecodedEntry.decode(it) }.getOrElse { invalid("undecodable entry") } }
        val state = Membership.compute(invite.groupId, entries) ?: invalid("no valid genesis for the invited group")
        val me = state.active[keys.deviceId] ?: invalid("this device was not added")
        if (!me.dhPublicKey.toByteArray().contentEquals(keys.dh.publicKey)) invalid("added with another X25519 key")
        if (me.role != invite.role) invalid("added as ${me.role}, invited as ${invite.role}")
        val inviter = state.active[invite.adminId]
        if (inviter?.isAdmin != true) invalid("the inviting admin is not an active admin")
        if (!inviter.dhPublicKey.toByteArray().contentEquals(invite.adminDhKey)) invalid("the admin's X25519 key differs from the QR")
        return GuestResult.Joined(discoveryKey, entries, state)
    }
}
