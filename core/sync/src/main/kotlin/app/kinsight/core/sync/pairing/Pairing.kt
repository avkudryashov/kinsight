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
import app.kinsight.core.model.proto.PairAck
import app.kinsight.core.model.proto.PairMessage
import app.kinsight.core.model.proto.PairPending
import app.kinsight.core.model.proto.PairReject
import app.kinsight.core.model.proto.PairRequest
import app.kinsight.core.model.proto.PairWelcome
import app.kinsight.core.sync.ByteStream
import app.kinsight.core.sync.HandedAccess
import app.kinsight.core.sync.PROTOCOL_VERSION
import app.kinsight.core.sync.PendingRevoke
import app.kinsight.core.sync.SecureChannel
import app.kinsight.core.sync.WelcomeCloud
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

/**
 * A device asking to join; [dhPublicKey] is its Noise static key, proven by the handshake; [sas] the channel's code;
 * [protocolVersion] the highest sync protocol it speaks (0: older than Y3, it cannot take the owner role, spec §7.3).
 */
class Joiner(val signPublicKey: ByteArray, val dhPublicKey: ByteArray, val displayName: String, val sas: String = "", val protocolVersion: Int = 0) {
    val id: DeviceId = DeviceId.fromSignPublicKey(signPublicKey)
}

sealed interface HostDecision {
    /**
     * [membership]: SignedEntry bytes of the group's effective membership, the guest's AddMember included. [cloud]: what
     * the first page hands for the cloud; its access only when this admin is the storage owner (the guest drops it otherwise).
     * Its refresh token only in the owner's transfer invite (spec §7.3); it is sent only together with the access.
     */
    class Welcome(val discoveryKey: ByteArray, val membership: List<ByteArray>, val cloud: WelcomeCloud? = null) : HostDecision

    data class Reject(val reason: PairReject.Reason) : HostDecision
}

sealed interface HostOutcome {
    /**
     * [ownerRoleTaken]: the welcome carried the owner's refresh token (spec §7.3) and the guest acknowledged that it
     * stored the welcome and signed the StorageOwner. False for every other welcome, and when no acknowledgement came.
     * [ownerRoleRefused]: the guest answered such a welcome with PairAck(false): it did not take the role (no answer is
     * neither, the role may still arrive).
     */
    class Welcomed(val joiner: Joiner, val ownerRoleTaken: Boolean = false, val ownerRoleRefused: Boolean = false) : HostOutcome

    class Rejected(val reason: PairReject.Reason, val joiner: Joiner?) : HostOutcome
}

sealed interface GuestResult {
    /** [cloud]: null when the welcome's cloud part does not fit [state] (joining does not depend on it). */
    class Joined(
        val discoveryKey: ByteArray,
        val membership: List<DecodedEntry>,
        val state: MembershipState,
        val cloud: WelcomeCloud? = null,
    ) : GuestResult

    data class Rejected(val reason: PairReject.Reason) : GuestResult
}

object PairingProtocol {
    /** Entries per PairWelcome page, below SecureChannel.MAX_PLAINTEXT with room for framing. */
    const val PAGE_BYTES = 60_000
    val DECISION_TIMEOUT: Duration = 3.minutes
    val REQUEST_TIMEOUT: Duration = 20.seconds

    /** How long the host waits for the guest's PairAck after a welcome with the refresh token (the guest stores it first). */
    val ACK_TIMEOUT: Duration = 60.seconds
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
            val joiner = Joiner(
                request.sign_public_key.toByteArray(), ch.remoteStaticKey, request.display_name, PairingSas.code(ch.handshakeHash),
                request.protocol_version,
            )
            problem(request, ch.remoteStaticKey)?.let { return reject(ch, it, joiner) }
            PairingProtocol.send(ch, PairMessage(pending = PairPending()))
            return when (val d = withTimeoutOrNull(decisionTimeout) { decide(joiner) } ?: HostDecision.Reject(PairReject.Reason.EXPIRED)) {
                is HostDecision.Reject -> reject(ch, d.reason, joiner)
                is HostDecision.Welcome -> {
                    if (clock() > expiresAtMs) return reject(ch, PairReject.Reason.EXPIRED, joiner)
                    if (!bind(joiner.dhPublicKey)) return reject(ch, PairReject.Reason.BAD_TOKEN, joiner)
                    sendWelcome(ch, d)
                    val ack = if (refreshOf(d.cloud) != null) awaitAck(ch) else null
                    PairingProtocol.linger(ch)
                    HostOutcome.Welcomed(joiner, ownerRoleTaken = ack == true, ownerRoleRefused = ack == false)
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

    private companion object {
        /** Tags, lengths and the varint fields of a PairWelcome page beyond its entries. */
        const val FIELD_OVERHEAD = 64

        /** Per retired token: its tag and length, and its generation's tag and varint. */
        const val RETIRED_OVERHEAD = 16
    }

    /**
     * Spec §7.3: the guest's PairAck to the transfer welcome in time, true when it took the role; null without one (no
     * answer in time, a hang-up or another message: the role was not confirmed, yet it may still have been signed).
     */
    private suspend fun awaitAck(ch: SecureChannel): Boolean? = try {
        withTimeoutOrNull(PairingProtocol.ACK_TIMEOUT) { PairingProtocol.receive(ch).ack?.owner_role_taken }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Spec §7.3: the refresh token goes only with the access it belongs to, and only when well formed. */
    private fun refreshOf(c: WelcomeCloud?): String? =
        c?.refreshToken?.takeIf { c.access != null && it.length in 1..HandedAccess.MAX_TOKEN_CHARS }

    private suspend fun sendWelcome(ch: SecureChannel, w: HostDecision.Welcome) {
        val pages = mutableListOf<List<ByteArray>>()
        var page = mutableListOf<ByteArray>()
        var size = 0
        // The first page also carries the discovery key and the cloud fields (an access token and, in a transfer, a
        // refresh token of up to 4096 chars each, so up to 12 KB of UTF-8 each, a char being at most 3 bytes, and the
        // retired tokens, up to PendingRevoke.MAX_BYTES): its entries get that much less room, which keeps every frame
        // below SecureChannel.MAX_PLAINTEXT.
        val refresh = refreshOf(w.cloud)
        // Spec §7.1, §7.4: the old owner's pending revokes go with the refresh token only.
        val retired = if (refresh == null) emptyList() else PendingRevoke.fitting(w.cloud!!.retired)
        val firstExtra = w.discoveryKey.size + (w.cloud?.let { c ->
            (c.groupKey?.size ?: 0) + (c.access?.let { it.storageId.size + it.accessToken.encodeToByteArray().size } ?: 0)
        } ?: 0) + (refresh?.encodeToByteArray()?.size ?: 0) +
            retired.sumOf { it.accessToken.encodeToByteArray().size + RETIRED_OVERHEAD } + FIELD_OVERHEAD
        for (e in w.membership) {
            val budget = if (pages.isEmpty()) PairingProtocol.PAGE_BYTES - firstExtra else PairingProtocol.PAGE_BYTES
            if (page.isNotEmpty() && size + e.size > budget) {
                pages += page
                page = mutableListOf()
                size = 0
            }
            page += e
            size += e.size + 8 // field tag and length per entry
        }
        pages += page
        pages.forEachIndexed { i, p ->
            val first = i == 0
            val cloud = w.cloud?.takeIf { first }
            val access = cloud?.access
            val welcome = PairWelcome(
                discovery_key = if (first) w.discoveryKey.toByteString() else ByteString.EMPTY,
                entries = p.map { it.toByteString() },
                more = i < pages.lastIndex,
                key_epoch = cloud?.takeIf { it.groupKey != null }?.keyEpoch ?: 0,
                group_key = cloud?.groupKey?.toByteString() ?: ByteString.EMPTY,
                storage_generation = access?.storageGeneration ?: 0,
                storage_id = access?.storageId?.toByteString() ?: ByteString.EMPTY,
                access_token = access?.accessToken.orEmpty(),
                access_expires_at_ms = access?.expiresAtMs ?: 0,
                refresh_token = refresh?.takeIf { first }.orEmpty(),
                retired_tokens = if (first) retired.map { it.accessToken } else emptyList(),
                retired_generations = if (first) retired.map { it.generation } else emptyList(),
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
    /**
     * [onPending] gets this channel's SAS ([PairingSas]) once the admin answered «wait»: the screen shows it to compare.
     * [onOwnerHandover]: only for a welcome whose refresh token this guest kept (spec §7.3), called with the result
     * while the channel is still open; it stores the welcome and takes the role, and its answer (true: the StorageOwner
     * is signed) goes back to the admin as PairAck (a welcome that offered one the guest did not keep is answered no).
     * After a call the caller must not store the result again.
     */
    suspend fun join(
        stream: ByteStream,
        onOwnerHandover: suspend (GuestResult.Joined) -> Boolean = { false },
        onPending: (sas: String) -> Unit = {},
    ): GuestResult {
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
                protocol_version = PROTOCOL_VERSION,
            )
            PairingProtocol.send(ch, PairMessage(request = request))
            val first = withTimeoutOrNull(decisionTimeout) { PairingProtocol.receive(ch) }
                ?: throw IOException("no answer from the admin within $decisionTimeout")
            first.reject?.let { return GuestResult.Rejected(it.reason) }
            if (first.pending == null) throw PairingException(PairingException.Kind.MALFORMED, "expected PairPending")
            onPending(PairingSas.code(ch.handshakeHash))
            val (result, offered) = withTimeoutOrNull(decisionTimeout) { readDecision(ch) }
                ?: throw IOException("no decision from the admin within $decisionTimeout")
            // Spec §7.3: a welcome that offered the refresh token is always answered, so the admin never reports a
            // transfer that did not happen (and does not wait out its timeout).
            if (offered && result is GuestResult.Joined) {
                var taken = false
                try {
                    // Only a refresh token this guest kept (cloudOf) can make it the owner; otherwise the answer is no.
                    if (result.cloud?.refreshToken != null) taken = onOwnerHandover(result)
                } finally {
                    acknowledge(ch, taken)
                }
            }
            ch.close()
            return result
        } finally {
            stream.close()
        }
    }

    /** Best effort: a link that dropped already tells the admin nothing was confirmed. */
    private suspend fun acknowledge(ch: SecureChannel, taken: Boolean) {
        try {
            PairingProtocol.send(ch, PairMessage(ack = PairAck(owner_role_taken = taken)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the admin hung up
        }
    }

    /** The decision, and whether the welcome offered the refresh token (it is answered with PairAck then). */
    private suspend fun readDecision(ch: SecureChannel): Pair<GuestResult, Boolean> {
        var first: PairWelcome? = null
        val raw = mutableListOf<ByteArray>()
        while (true) {
            val m = PairingProtocol.receive(ch)
            m.reject?.let { return GuestResult.Rejected(it.reason) to false }
            val w = m.welcome ?: throw PairingException(PairingException.Kind.MALFORMED, "expected PairWelcome")
            if (first == null) first = w
            w.entries.mapTo(raw) { it.toByteArray() }
            if (!w.more) break
        }
        return validate(first!!, raw) to first.refresh_token.isNotEmpty()
    }

    private fun validate(first: PairWelcome, raw: List<ByteArray>): GuestResult.Joined {
        fun invalid(why: String): Nothing = throw PairingException(PairingException.Kind.INVALID_WELCOME, why)
        val discoveryKey = first.discovery_key.toByteArray()
        if (discoveryKey.size != 32) invalid("discovery key must be 32 bytes")
        val entries = raw.map { runCatching { DecodedEntry.decode(it) }.getOrElse { invalid("undecodable entry") } }
        val state = Membership.compute(invite.groupId, entries) ?: invalid("no valid genesis for the invited group")
        val me = state.active[keys.deviceId] ?: invalid("this device was not added")
        if (!me.dhPublicKey.toByteArray().contentEquals(keys.dh.publicKey)) invalid("added with another X25519 key")
        if (me.role != invite.role) invalid("added as ${me.role}, invited as ${invite.role}")
        val inviter = state.active[invite.adminId]
        if (inviter?.isAdmin != true) invalid("the inviting admin is not an active admin")
        if (!inviter.dhPublicKey.toByteArray().contentEquals(invite.adminDhKey)) invalid("the admin's X25519 key differs from the QR")
        return GuestResult.Joined(discoveryKey, entries, state, cloudOf(first, state))
    }

    /**
     * The cloud part of the welcome. The admin hands its newest generation: a generation that is not the membership's
     * newest is dropped, never trusted, and the joiner starts from generation 0 (the admin's next run rotates for it).
     * Access is kept only when it names the membership's storage and the inviting admin is that storage's owner (only the
     * owner hands the token), whether or not the key was kept. Joining does not depend on it (the device then waits for
     * the access over Bluetooth). The refresh token (spec §7.3, the owner's new phone) is kept only with that access and
     * only when the welcome made this phone an admin; the old owner's pending revokes only with the refresh token, and
     * none when they are malformed (a count that differs from their generations').
     */
    private fun cloudOf(first: PairWelcome, state: MembershipState): WelcomeCloud {
        val epoch = first.key_epoch
        val key = first.group_key.toByteArray()
        val groupKey = key.takeIf { epoch > 0 && epoch == (state.discoveryRotation?.epoch ?: 0) && key.size == 32 }
        val access = HandedAccess.of(first.storage_generation, first.storage_id.toByteArray(), first.access_token, first.access_expires_at_ms)
            ?.takeIf { it.isFor(state.storageOwner) && state.storageOwner?.deviceId == invite.adminId }
        // Spec §7.3: the refresh token comes only with the owner's access, to a phone this welcome made an admin.
        val refresh = first.refresh_token.takeIf {
            it.isNotEmpty() && it.length <= HandedAccess.MAX_TOKEN_CHARS && access != null && state.active[keys.deviceId]?.isAdmin == true
        }
        val retired = if (refresh == null || first.retired_tokens.size != first.retired_generations.size) {
            emptyList()
        } else {
            val all = first.retired_tokens.zip(first.retired_generations) { t, g -> PendingRevoke(g, t) }
            PendingRevoke.fitting(all).takeIf { it.size == all.size }.orEmpty().filter { it.accessToken != access!!.accessToken }
        }
        return WelcomeCloud(if (groupKey == null) 0 else epoch, groupKey, access, refresh, retired)
    }
}
