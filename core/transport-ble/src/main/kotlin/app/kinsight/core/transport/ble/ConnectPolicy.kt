package app.kinsight.core.transport.ble

import app.kinsight.core.crypto.DiscoveryTags
import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.DeviceId

/** Who a sighting is for cooldowns and blocking: the member resolved from its device tag, else the tag. Never a MAC. */
@JvmInline
value class PeerKey(val value: String) {
    companion object {
        fun of(id: DeviceId) = PeerKey(id.hex)
        fun ofTag(deviceTag: ByteArray) = PeerKey("tag:" + deviceTag.toHex())
    }
}

/** Opaque handle the Android layer connects to (a BluetoothDevice). Its address rotates; it is never an identity. */
interface PeerAddress

data class Sighting(val address: PeerAddress, val payload: AdvertPayload, val rssi: Int)

sealed interface Decision {
    data class Ignore(val reason: String) : Decision

    /** We are the responder: get our entries ready, the peer connects to us. */
    data class Prepare(val peer: PeerKey) : Decision

    data class Connect(val peer: PeerKey, val address: PeerAddress) : Decision
}

/**
 * Whether a sighting leads to a session (spec §8): our group, not us, different vectors, not blocked, not in
 * cooldown; then the smaller advertised device tag connects. Equal tags: the smaller DeviceId connects when the peer
 * is resolved, otherwise nobody until the next window. The vector check compares the peer's vector tag with ours
 * computed for the window its group tag matched (a peer still or already in the neighbouring window stays comparable).
 * Fast mode («Sync now») skips the cooldown and the tag order and always connects (a block or an equal vector still
 * stops it): the peer is usually not in fast mode and would sit out its own cooldown, while a responder never checks
 * one. Two fast initiators meet the busy gate and the failure backoff.
 * Times: nowWall for tag windows, nowMono (elapsedRealtime) for cooldowns.
 */
class ConnectPolicy(
    private val cooldownMs: Long = COOLDOWN_MS,
    private val failureBackoffMs: Long = FAILURE_BACKOFF_MS,
    private val blockMs: Long = BLOCK_MS,
) {
    private val retryAt = HashMap<PeerKey, Long>()
    private val blockedUntil = HashMap<PeerKey, Long>()

    @Synchronized
    fun decide(
        s: Sighting,
        peer: PeerKey,
        peerId: DeviceId?,
        self: AdvertPayload,
        selfVector: ByteArray,
        selfId: DeviceId,
        discoveryKey: ByteArray,
        nowWall: Long,
        nowMono: Long,
        fast: Boolean,
    ): Decision {
        val window = DiscoveryTags.matchingWindow(discoveryKey, s.payload.groupTag, nowWall)
            ?: return Decision.Ignore("other group")
        if (peerId == selfId) return Decision.Ignore("self")
        if (s.payload.vectorTag.contentEquals(DiscoveryTags.vectorTag(discoveryKey, window, selfVector))) {
            return Decision.Ignore("in sync")
        }
        if (isBlocked(peer, nowMono)) return Decision.Ignore("blocked")
        if (fast) return Decision.Connect(peer, s.address)
        if ((retryAt[peer] ?: Long.MIN_VALUE) > nowMono) return Decision.Ignore("cooldown")
        val ours = self.deviceTagValue
        val theirs = s.payload.deviceTagValue
        val weConnect = when {
            ours != theirs -> ours < theirs
            peerId != null -> selfId < peerId
            else -> return Decision.Ignore("tag tie")
        }
        return if (weConnect) Decision.Connect(peer, s.address) else Decision.Prepare(peer)
    }

    @Synchronized
    fun isBlocked(peer: PeerKey, nowMono: Long): Boolean = (blockedUntil[peer] ?: Long.MIN_VALUE) > nowMono

    /** A finished (or rejected) session: next one with this peer after the cooldown. */
    @Synchronized
    fun onCompleted(peer: PeerKey, nowMono: Long) = later(peer, nowMono + cooldownMs)

    /** A dropped link or failed connect: retry soon, but never earlier than an active cooldown allows. */
    @Synchronized
    fun onFailed(peer: PeerKey, nowMono: Long) = later(peer, nowMono + failureBackoffMs)

    /** Too many invalid entries (spec §5.5): no session for an hour, fast mode included. */
    @Synchronized
    fun onBlocked(peer: PeerKey, nowMono: Long) {
        blockedUntil[peer] = nowMono + blockMs
        later(peer, nowMono + blockMs)
    }

    private fun later(peer: PeerKey, at: Long) {
        retryAt[peer] = maxOf(retryAt[peer] ?: Long.MIN_VALUE, at)
    }

    companion object {
        const val COOLDOWN_MS = 10 * 60_000L
        const val FAILURE_BACKOFF_MS = 60_000L
        const val BLOCK_MS = 60 * 60_000L
    }
}
