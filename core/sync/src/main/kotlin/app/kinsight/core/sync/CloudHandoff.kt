package app.kinsight.core.sync

import app.kinsight.core.model.Member
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.StorageOwnerState

/**
 * The shared storage access as one device hands it to another (spec §6 «Раздача»). Secret: never log [accessToken].
 * Always well formed: the constructor throws IllegalArgumentException otherwise; [of] is the non-throwing form for input
 * from a peer or from storage.
 */
class HandedAccess(val storageGeneration: Long, val storageId: ByteArray, val accessToken: String, val expiresAtMs: Long) {
    init {
        require(wellFormed(storageGeneration, storageId, accessToken)) { "malformed handed access" }
    }

    /** True for the storage [owner] names: same generation and storage id. */
    fun isFor(owner: StorageOwnerState?): Boolean =
        owner != null && owner.generation == storageGeneration && owner.storageId.toByteArray().contentEquals(storageId)

    override fun toString() = "HandedAccess(generation=$storageGeneration, expiresAtMs=$expiresAtMs)"

    companion object {
        const val MAX_TOKEN_CHARS = 4096

        /** Null unless well formed: generation at least 1, a 16-byte storage id, a token of 1..[MAX_TOKEN_CHARS] chars. */
        fun of(storageGeneration: Long, storageId: ByteArray, accessToken: String, expiresAtMs: Long): HandedAccess? =
            if (wellFormed(storageGeneration, storageId, accessToken)) {
                HandedAccess(storageGeneration, storageId, accessToken, expiresAtMs)
            } else {
                null
            }

        private fun wellFormed(storageGeneration: Long, storageId: ByteArray, accessToken: String): Boolean =
            storageGeneration >= 1 && storageId.size == 16 && accessToken.length in 1..MAX_TOKEN_CHARS
    }
}

/**
 * What pairing hands a new member for the cloud (spec §5, §6 «Раздача»): the newest group key generation the admin holds
 * ([groupKey] null: generation 0, the discovery key), the storage access, present only when the inviting admin is the
 * storage owner (only the owner hands the token), and, only in the owner's transfer invite to its new phone (spec §7.3),
 * the [refreshToken] and the old owner's [retired] tokens still waiting for their revoke. Secret: never logged.
 */
class WelcomeCloud(
    val keyEpoch: Int,
    val groupKey: ByteArray?,
    val access: HandedAccess?,
    val refreshToken: String? = null,
    val retired: List<PendingRevoke> = emptyList(),
) {
    override fun toString() = "WelcomeCloud(keyEpoch=$keyEpoch, access=$access, refresh=${refreshToken != null}, retired=${retired.size})"
}

/**
 * An access token a former holder no longer uses, revoked once every phone reports a storage generation above
 * [generation] (spec §7.1, §7.4). Secret: never log [accessToken].
 */
class PendingRevoke(val generation: Long, val accessToken: String) {
    override fun toString() = "PendingRevoke(generation=$generation)"

    companion object {
        /** At most this many go in a transfer welcome (the old owner keeps no more, RetiredTokenFile.MAX). */
        const val MAX = 8

        /** Their tokens' UTF-8 bytes in one welcome, so its first frame stays below SecureChannel.MAX_PLAINTEXT. */
        const val MAX_BYTES = 16 * 1024

        /** The newest of [all] that fit a welcome: well formed, at most [MAX] and [MAX_BYTES] together. */
        fun fitting(all: List<PendingRevoke>): List<PendingRevoke> {
            val out = ArrayDeque<PendingRevoke>()
            var bytes = 0
            for (r in all.asReversed()) {
                if (out.size == MAX) break
                if (r.generation < 1 || r.accessToken.length !in 1..HandedAccess.MAX_TOKEN_CHARS) continue
                val size = r.accessToken.encodeToByteArray().size
                if (bytes + size > MAX_BYTES) break
                bytes += size
                out.addFirst(r)
            }
            return out.toList()
        }
    }
}

/**
 * The cloud hand-off of one Bluetooth session (spec §6 «Раздача» between devices already paired), protocol 3 only. Called
 * once the peer is authenticated as an active member: [offer] gives what this device hands [peer] (only the storage owner
 * hands anything), [receive] gets what the peer handed, already well formed. The session itself does not check who the
 * sender is: what to trust (for example only access from the membership's StorageOwner) is the implementation's decision.
 * An exception from either call (a local fault such as a vault write) is logged and contained: the session goes on
 * without the hand-off, so a cloud-side problem never stops Bluetooth sync. Cancellation still propagates.
 */
interface SessionHandoff {
    suspend fun offer(peer: Member, membership: MembershipState): HandedAccess?

    suspend fun receive(peer: Member, membership: MembershipState, access: HandedAccess)
}
