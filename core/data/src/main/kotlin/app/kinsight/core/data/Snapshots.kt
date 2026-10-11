package app.kinsight.core.data

import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.entriesChanged
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.transport.ble.GroupStore
import app.kinsight.core.transport.ble.GroupStoreException
import app.kinsight.core.transport.ble.TransportStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Everything the screens derive from, read in one go. */
sealed interface GroupSnapshot {
    /** The identity is unusable (spec §6.2): the only way on is «reset identity and re-pair». */
    data class KeyError(val message: String) : GroupSnapshot

    data object NoGroup : GroupSnapshot

    /** The group file or the database is damaged; same way out as a key error. */
    data class StorageError(val message: String) : GroupSnapshot

    class Ready(
        val groupId: GroupId,
        val self: DeviceId,
        val membership: MembershipState,
        val summaries: List<DecodedEntry>,
        val catalog: List<DecodedEntry>,
        val events: List<DecodedEntry>,
        val heartbeats: List<DecodedEntry>,
        val peerSeen: Map<DeviceId, Long>,
    ) : GroupSnapshot {
        val removed: Boolean get() = self !in membership.active
    }
}

/**
 * Reloads the snapshot on every committed change of the entry log, on [invalidate] (group file or identity changed
 * outside Room) and when «last seen» changes. Loading runs on [io] (Room, the keystore and the group file); a newer
 * trigger cancels an older load.
 *
 * [snapshots] is one shared load for every collector (final fix item 4): the dashboard, detail, app and thresholds
 * flows, the group state and the widget derive from it on [scope] (the app scope), instead of each doing its own Room
 * read and decode per change. It runs while someone collects and [stopTimeoutMs] longer (a screen rotation keeps it);
 * when it stops, its last snapshot is dropped, so a later subscriber starts from a fresh load, never a stale one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Snapshots(
    private val db: KinsightDatabase,
    private val groups: GroupStore,
    private val keys: suspend () -> DeviceKeys,
    private val transport: TransportStateStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val stopTimeoutMs: Long = STOP_TIMEOUT_MS,
) {
    private val version = MutableStateFlow(0)

    fun invalidate() {
        version.update { it + 1 }
    }

    /**
     * A failing load is a [GroupSnapshot.StorageError] for that change: the shared flow lives on (an exception would
     * end it for every collector and reach the app scope), and the next change loads again.
     */
    val snapshots: Flow<GroupSnapshot> =
        combine(db.entriesChanged(), version, transport.peerSeenFlow) { _, _, seen -> seen }
            .mapLatest { seen ->
                try {
                    load(seen)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    GroupSnapshot.StorageError("${e::class.simpleName}: ${e.message}")
                }
            }
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMs, replayExpirationMillis = 0), replay = 1)

    /** [author]'s UsageDetail entries for [range] (level 4); read on demand, the snapshot itself carries none. */
    suspend fun details(groupId: GroupId, author: DeviceId, range: DetailRange): List<DecodedEntry> = withContext(io) {
        RoomEntryStore(db, groupId).details(author, range.start, range.end)
    }

    suspend fun load(peerSeen: Map<DeviceId, Long>): GroupSnapshot = withContext(io) {
        val self = try {
            keys().deviceId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext GroupSnapshot.KeyError("${e::class.simpleName}: ${e.message}")
        }
        val group = try {
            groups.read()
        } catch (e: GroupStoreException) {
            return@withContext GroupSnapshot.StorageError(e.message ?: "group file")
        } ?: return@withContext GroupSnapshot.NoGroup
        val store = RoomEntryStore(db, group.groupId)
        val membership = Membership.compute(group.groupId, store.membershipEntries())
            ?: return@withContext GroupSnapshot.StorageError("no valid group genesis in the database")
        GroupSnapshot.Ready(
            groupId = group.groupId,
            self = self,
            membership = membership,
            summaries = store.summaries(),
            catalog = store.catalog(),
            events = store.stateEvents(),
            heartbeats = store.heartbeats(),
            peerSeen = peerSeen,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
