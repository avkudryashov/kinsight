package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.StorageOwnerState
import app.kinsight.core.transport.ble.GroupStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spec §7.3 on the old phone: its transfer invite welcomed [target] with the owner's tokens while the StorageOwner of
 * [generation] with token id [tokenIdHex] was in force. [untilMs]: [OwnerTransfer.RECORD_MS] after the welcome; the record
 * lives until the target's StorageOwner is seen or that time passes without it (a lost PairAck does not end it).
 */
/**
 * [refused]: the new phone answered PairAck(false). Its word alone does not end the record (Y3 parked: the host does not
 * trust it): the screens no longer say the role is moving, but until [untilMs] the owner still does not refresh and still
 * leaves if the target's StorageOwner arrives after all (spec §7.3).
 */
data class OwnerTransferRecord(
    val groupHex: String,
    val target: DeviceId,
    val tokenIdHex: String,
    val generation: Long,
    val untilMs: Long,
    val refused: Boolean = false,
)

/** For the screens (CloudStatus.ownerTransfer): [PENDING] «Перенос роли…»; [DONE] the new phone holds the role, this one leaves. */
enum class OwnerTransferPhase { PENDING, DONE }

object OwnerTransfers {
    /**
     * The phase of [r] for the group [groupId] with membership [m], or null when there is none, it expired or the new
     * phone refused the role ([OwnerTransferRecord.refused]; DONE still wins).
     */
    fun phase(r: OwnerTransferRecord?, groupId: GroupId, m: MembershipState?, nowMs: Long): OwnerTransferPhase? {
        if (!live(r, groupId, m, nowMs)) return null
        if (m != null && completed(r!!, m)) return OwnerTransferPhase.DONE
        return OwnerTransferPhase.PENDING.takeIf { !r!!.refused }
    }

    /** [r] still counts for [groupId]: completed, or not expired (refused or not). The owner does not refresh meanwhile. */
    fun live(r: OwnerTransferRecord?, groupId: GroupId, m: MembershipState?, nowMs: Long): Boolean {
        if (r == null || r.groupHex != groupId.hex) return false
        return (m != null && completed(r, m)) || nowMs <= r.untilMs
    }

    /** The StorageOwner in force names the target, is newer, keeps the same token id (rulings Q3) and the target signed it. */
    fun completed(r: OwnerTransferRecord, m: MembershipState): Boolean {
        val owner = m.storageOwner ?: return false
        if (owner.deviceId != r.target || owner.generation <= r.generation || owner.tokenId.hex() != r.tokenIdHex) return false
        return m.effectiveEntries.any { e -> e.author == r.target && e.body.membership_op?.storage_owner?.generation == owner.generation }
    }

    fun record(groupId: GroupId, target: DeviceId, inForce: StorageOwnerState, untilMs: Long) =
        OwnerTransferRecord(groupId.hex, target, inForce.tokenId.hex(), inForce.generation, untilMs)
}

/**
 * The old phone's side of spec §7.3 «Старый телефон под рукой... 0 нажатий. Старый удаляется из группы»: the transfer
 * invite records its target ([record]); [check], run on every membership change while a record exists, makes
 * this phone leave the group by itself (LEFT_GROUP and the local reset, [leave]) once the target's own StorageOwner is in
 * its membership, whenever it arrives (Bluetooth or cloud, also after a lost PairAck). An incomplete transfer changes
 * nothing: its record expires [RECORD_MS] after the welcome; until then the owner does not refresh its token (CloudRunner),
 * so the handed refresh token stays good. The LEFT_GROUP keeps the
 * token unexposed (OwnerLifecycle.tokenExposed's §7.3 exception), so the new owner goes on with the same token.
 */
class OwnerTransfer(
    private val db: KinsightDatabase,
    private val groups: GroupStore,
    private val keys: suspend () -> DeviceKeys,
    private val state: CloudStateStore,
    /** GroupRepository.leaveAndForget: LEFT_GROUP, a session to carry it, then the identity reset under runLock. */
    private val leave: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val leaving = AtomicBoolean(false)

    /** The welcome to [target] goes out now: the record lives [RECORD_MS] unless the target's StorageOwner comes first. */
    suspend fun record(groupId: GroupId, target: DeviceId, inForce: StorageOwnerState) =
        state.recordTransfer(OwnerTransfers.record(groupId, target, inForce, clock() + RECORD_MS))

    /** The welcome to [target] never went out (the host refused it): nothing to wait for. */
    suspend fun cancel(groupId: GroupId, target: DeviceId) {
        val r = state.current().transfer ?: return
        if (r.groupHex == groupId.hex && r.target == target) state.clearTransfer(r)
    }

    /**
     * The new phone answered that it did not take the role. It holds the tokens, so its word is not taken as final: the
     * record stays until it expires ([OwnerTransferRecord.refused]).
     */
    suspend fun refused(groupId: GroupId, target: DeviceId) {
        val r = state.current().transfer ?: return
        if (r.groupHex == groupId.hex && r.target == target) state.markTransferRefused(r)
    }

    /** Null without a live record; [OwnerTransferPhase.DONE] once the transfer completed (the leave has been run). */
    suspend fun check(): OwnerTransferPhase? {
        val r = state.current().transfer ?: return null
        val group = withContext(io) { groups.read() }
        if (group == null || group.groupId.hex != r.groupHex) {
            state.clearTransfer(r)
            return null
        }
        val m = Membership.compute(group.groupId, RoomEntryStore(db, group.groupId).membershipEntries())
        val phase = OwnerTransfers.phase(r, group.groupId, m, clock())
        if (!OwnerTransfers.live(r, group.groupId, m, clock())) state.clearTransfer(r)
        if (phase != OwnerTransferPhase.DONE || m?.active?.containsKey(keys().deviceId) != true) return phase
        if (!leaving.compareAndSet(false, true)) return phase
        try {
            Log.i(TAG, "the owner role moved to the new phone: leaving the group")
            leave()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "leaving after the owner transfer failed: ${e.javaClass.simpleName}") // retried at the next change
        } finally {
            leaving.set(false)
        }
        return phase
    }

    companion object {
        /** Fix round 2: how long the old phone waits for the target's StorageOwner (7 days, as «Владелец недоступен»). */
        const val RECORD_MS = 7 * 24 * 3_600_000L
        private const val TAG = "KinsightOwnerTransfer"
    }
}
