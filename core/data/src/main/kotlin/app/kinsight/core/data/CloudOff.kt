package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.MembershipState
import app.kinsight.core.transport.cloud.YandexOAuth
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** What the owner's «Выключить синхронизацию через Яндекс Диск» ended in. */
enum class CloudOffResult { OFF, NOT_OWNER, FAILED }

/**
 * The cloud is off for the family on this phone (CloudOff.settle ran): the app stops the cloud work and refreshes the
 * location engine; [revokePending]: tokens wait in the retired list for the revoke job, due from [revokeAtMs] at the
 * latest (earlier once every member's Heartbeat reports the off generation).
 */
data class CloudOffNote(val revokeAtMs: Long, val revokePending: Boolean)

/** One pass of the revoke job (CloudRunner.revokePending). */
enum class RevokeRun { NOTHING, WAITING, REVOKED, RETRY }

/**
 * The cloud turned off for the whole family (StorageOwner.off in force, [MembershipState.storageOff]): every phone that
 * learns it, the owner first, lets go of its cloud tokens. Called under the cloud run lock (CloudSecrets.runLock).
 */
internal object CloudOff {
    /**
     * The owner's token is revoked after this long at the latest, even when some member never confirmed the off: a phone
     * that meets the family only through the Disk learns it at its next run while the token still works.
     */
    const val REVOKE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    /** True when [m] says the owner turned the cloud off: no storage owner and an off record in force. */
    fun inForce(m: MembershipState): Boolean = m.storageOwner == null && m.storageOff != null

    /**
     * Lets go of this phone's cloud access for the off record in force in [m]: the vault goes (an owner's own access token
     * moves to the retired list for the revoke; a handed token is the owner's to revoke), and the cloud state says off,
     * with the revoke deadline [nowMs] + [REVOKE_AFTER_MS] when this off is new here. Nothing is revoked now. Idempotent.
     */
    suspend fun settle(groupId: GroupId, m: MembershipState, secrets: CloudSecrets, state: CloudStateStore, nowMs: Long): CloudOffNote {
        val off = requireNotNull(m.storageOff)
        if (secrets.dropForOff(groupId)) Log.i(TAG, "the cloud was turned off: cloud access dropped")
        val known = state.current().offRevoke?.takeIf { it.groupHex == groupId.hex && it.generation == off.generation }
        val until = known?.untilMs ?: (nowMs + REVOKE_AFTER_MS).also { state.turnedOff(groupId.hex, off.generation, it) }
        return CloudOffNote(until, secrets.retired(groupId).isNotEmpty())
    }

    /**
     * Revokes every retired token of [groupId] once; an answer of any kind drops it (a token Yandex no longer knows is
     * dead anyway), no answer keeps it for the next try. A retired token equal to [inUse] is only dropped: Yandex hands the
     * same token again to a phone that signs in anew, and revoking it would kill the access just taken. True when none is left.
     */
    suspend fun revokeAll(
        groupId: GroupId,
        secrets: CloudSecrets,
        config: () -> CloudConfig,
        oauth: (CloudConfig) -> YandexOAuth,
        inUse: String? = null,
    ): Boolean {
        val c = config()
        if (!c.configured) return true
        var left = false
        for (r in secrets.retired(groupId)) {
            if (inUse != null && r.accessToken == inUse) {
                secrets.dropRetired(r.accessToken)
                Log.i(TAG, "token of generation ${r.generation} is the one just signed in with: kept, not revoked")
                continue
            }
            try {
                val revoked = oauth(c).revoke(r.accessToken)
                Log.i(TAG, "token of generation ${r.generation} revoked after the cloud was turned off: $revoked")
                secrets.dropRetired(r.accessToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Class name only: an OAuth failure message is never trusted not to carry a token.
                Log.w(TAG, "revoke failed, tried again: ${e.javaClass.simpleName}")
                left = true
            }
        }
        return !left
    }

    private const val TAG = "KinsightCloud"
}
