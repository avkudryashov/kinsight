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
 *
 * Fast mode («Sync now», one minute) skips the cooldown, but not the order of checks above (in sync and blocked still
 * stop it), and it is bounded per peer (emulator run 2026-10-10: two fast phones dialled each other 30 times in 20 s,
 * and one fast child ran an empty session every second for the whole minute):
 *  - the smaller tag dials at once; the larger tag prepares for [fastYieldMs] first (the peer may be fast too and dial
 *    us) and dials itself only if no session with that peer happened meanwhile (a peer in normal mode sits out its
 *    cooldown, so somebody has to); an unresolved tag tie dials at once;
 *  - at least [fastSpacingMs] between two dials to the same peer and after any session with it;
 *  - after [fastMaxFailures] failed dials in a row the normal cooldown and backoff apply again until [onFastStart];
 *  - a peer whose last session (either side dialled) moved nothing either way is not dialled again while neither its
 *    vector nor ours changed («nothing new»). This is what ends the loop between a child and a parent whose vectors
 *    never match: the child's position for the parent follows the parent's own log (the parent completes it), but a
 *    third member's log that the parent holds and the child never takes keeps them apart. With one parent and one
 *    child they can match, and «in sync» ends it.
 * Normal mode applies «nothing new» too, for at most [maxSilenceMs] after that empty session (audit A-3: a child and
 * a parent at night otherwise ran an empty session every 10 minutes). «Unchanged» does not depend on the window: the
 * advertised vector tag rotates every window (§6.1), so the session's two canonical vectors are stored and the advert
 * is compared with the tag of the stored peer vector for the window the group tag matched.
 * Times: nowWall for tag windows, nowMono (elapsedRealtime) for cooldowns.
 */
class ConnectPolicy(
    private val cooldownMs: Long = COOLDOWN_MS,
    private val failureBackoffMs: Long = FAILURE_BACKOFF_MS,
    private val blockMs: Long = BLOCK_MS,
    private val fastYieldMs: Long = FAST_YIELD_MS,
    private val fastSpacingMs: Long = FAST_SPACING_MS,
    private val fastMaxFailures: Int = FAST_MAX_FAILURES,
    private val maxSilenceMs: Long = MAX_SILENCE_MS,
) {
    private val retryAt = HashMap<PeerKey, Long>()
    private val blockedUntil = HashMap<PeerKey, Long>()
    private val failuresInRow = HashMap<PeerKey, Int>()
    private val lastDial = HashMap<PeerKey, Long>()
    private val lastSession = HashMap<PeerKey, Long>()
    private val yieldSince = HashMap<PeerKey, Long>()

    /** Our vector and the peer's vector (canonical bytes) of the last session that moved nothing, either side dialling. */
    private val noop = HashMap<PeerKey, Pair<ByteArray, ByteArray>>()

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
        val unchanged = noop[peer]?.let { (ours, theirs) ->
            ours.contentEquals(selfVector) &&
                s.payload.vectorTag.contentEquals(DiscoveryTags.vectorTag(discoveryKey, window, theirs))
        } == true
        val ours = self.deviceTagValue
        val theirs = s.payload.deviceTagValue
        val weConnect: Boolean? = when {
            ours != theirs -> ours < theirs
            peerId != null -> selfId < peerId
            else -> null // unresolved tie
        }
        if (fast && (failuresInRow[peer] ?: 0) < fastMaxFailures) {
            if (unchanged) return Decision.Ignore("nothing new")
            val recent = maxOf(lastDial[peer] ?: Long.MIN_VALUE, lastSession[peer] ?: Long.MIN_VALUE)
            if (recent != Long.MIN_VALUE && nowMono - recent < fastSpacingMs) return Decision.Ignore("fast spacing")
            if (weConnect == false) {
                val since = yieldSince[peer]
                if (since == null) {
                    yieldSince[peer] = nowMono
                    return Decision.Prepare(peer)
                }
                if (nowMono - since < fastYieldMs) return Decision.Prepare(peer)
                if ((lastSession[peer] ?: Long.MIN_VALUE) >= since) {
                    yieldSince[peer] = nowMono // the peer connected to us: keep yielding
                    return Decision.Prepare(peer)
                }
            }
            return connect(peer, s, nowMono)
        }
        if ((retryAt[peer] ?: Long.MIN_VALUE) > nowMono) return Decision.Ignore("cooldown")
        // A-3: the last session we dialled moved nothing and neither vector changed since: wait, but at most
        // [maxSilenceMs] after it, so the pair still meets once in a while (peer seen, liveness).
        if (unchanged && nowMono - lastSession.getValue(peer) < maxSilenceMs) {
            return Decision.Ignore("nothing new")
        }
        return when (weConnect) {
            null -> Decision.Ignore("tag tie")
            true -> connect(peer, s, nowMono)
            false -> Decision.Prepare(peer)
        }
    }

    private fun connect(peer: PeerKey, s: Sighting, nowMono: Long): Decision {
        lastDial[peer] = nowMono
        return Decision.Connect(peer, s.address)
    }

    @Synchronized
    fun isBlocked(peer: PeerKey, nowMono: Long): Boolean = (blockedUntil[peer] ?: Long.MIN_VALUE) > nowMono

    /**
     * A finished (or rejected) session: next one with this peer after the cooldown. [empty]: nothing moved either way;
     * [ourVector] and [peerVector] (canonical bytes: as exchanged in the session, plus the authors' completions it made,
     * SessionResult.Completed) are kept when [empty], whoever dialled.
     */
    @Synchronized
    fun onCompleted(
        peer: PeerKey,
        nowMono: Long,
        empty: Boolean = false,
        ourVector: ByteArray? = null,
        peerVector: ByteArray? = null,
    ) {
        later(peer, nowMono + cooldownMs)
        failuresInRow.remove(peer)
        lastSession[peer] = nowMono
        if (empty && ourVector != null && peerVector != null) {
            noop[peer] = ourVector to peerVector
        } else {
            noop.remove(peer)
        }
    }

    /** A dropped link or failed connect: retry soon, but never earlier than an active cooldown allows. */
    @Synchronized
    fun onFailed(peer: PeerKey, nowMono: Long) {
        later(peer, nowMono + failureBackoffMs)
        failuresInRow[peer] = (failuresInRow[peer] ?: 0) + 1
    }

    /** Too many invalid entries (spec §5.5): no session for an hour, fast mode included. */
    @Synchronized
    fun onBlocked(peer: PeerKey, nowMono: Long) {
        blockedUntil[peer] = nowMono + blockMs
        later(peer, nowMono + blockMs)
    }

    /** A new fast window («Sync now» while not fast): failure counts, spacing and yields start over. */
    @Synchronized
    fun onFastStart() {
        failuresInRow.clear()
        lastDial.clear()
        yieldSince.clear()
    }

    private fun later(peer: PeerKey, at: Long) {
        retryAt[peer] = maxOf(retryAt[peer] ?: Long.MIN_VALUE, at)
    }

    companion object {
        const val COOLDOWN_MS = 10 * 60_000L
        const val FAILURE_BACKOFF_MS = 60_000L
        const val BLOCK_MS = 60 * 60_000L
        const val FAST_YIELD_MS = 5_000L
        const val FAST_SPACING_MS = 5_000L
        const val FAST_MAX_FAILURES = 3
        const val MAX_SILENCE_MS = 40 * 60_000L
    }
}
