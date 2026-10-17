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
 * The cloud turned off for the whole family (StorageOwner.off in force, [MembershipState.storageOff]): every phone that
 * learns it, the owner first, lets go of its cloud tokens. Called under the cloud run lock (CloudSecrets.runLock).
 */
internal object CloudOff {
    /** True when [m] says the owner turned the cloud off: no storage owner and an off record in force. */
    fun inForce(m: MembershipState): Boolean = m.storageOwner == null && m.storageOff != null

    /**
     * Drops this phone's tokens of [groupId]: the vault (the owner's own or a handed token) and the retired tokens kept for
     * the revoke, and the cloud state. Its own tokens (an owner vault, the retired ones) are revoked first, once each and
     * best effort: no answer from Yandex does not keep a token (spec §6 «Отзыв»; the cloud is off, no run would try again).
     * A handed token is the owner's, which the owner revokes. True when anything was held.
     */
    suspend fun settle(groupId: GroupId, secrets: CloudSecrets, state: CloudStateStore, config: () -> CloudConfig, oauth: (CloudConfig) -> YandexOAuth): Boolean {
        val dropped = secrets.forgetTokens(groupId) { token -> revokeQuietly(config, oauth, token) }
        if (dropped || state.current() != CloudState()) state.clear()
        if (dropped) Log.i(TAG, "the cloud was turned off: cloud tokens dropped")
        return dropped
    }

    private suspend fun revokeQuietly(config: () -> CloudConfig, oauth: (CloudConfig) -> YandexOAuth, token: String) {
        val c = config()
        if (!c.configured) return
        try {
            val revoked = oauth(c).revoke(token)
            Log.i(TAG, "token revoked on turning the cloud off: $revoked")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Class name only: an OAuth failure message is never trusted not to carry a token.
            Log.w(TAG, "revoke failed while turning the cloud off: ${e.javaClass.simpleName}")
        }
    }

    private const val TAG = "KinsightCloud"
}
