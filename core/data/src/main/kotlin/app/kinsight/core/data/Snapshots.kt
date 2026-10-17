package app.kinsight.core.data

import android.util.Log
import androidx.annotation.VisibleForTesting
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
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
        /** Live CategoryOverride entries (stage 5c), every author's latest per package; read through [CategoryOverrides]. */
        val categoryOverrides: List<DecodedEntry> = emptyList(),
        /** Live StorageHandover entries (spec §7.1), read through OwnerLifecycle. */
        val handovers: List<DecodedEntry> = emptyList(),
        /** LOCATION entries (spec §8), at most 48 h of them; read through LocationReadModel. */
        val locations: List<DecodedEntry> = emptyList(),
        /** Live SafeZone entries (spec §8.1); read through SafeZones. */
        val safeZones: List<DecodedEntry> = emptyList(),
        /** Live ParentPin entries (spec §10); read through [ParentPins]. */
        val parentPins: List<DecodedEntry> = emptyList(),
    ) : GroupSnapshot {
        val removed: Boolean get() = self !in membership.active
    }
}

/**
 * Reloads the snapshot on every committed change of the entry log, on [invalidate] (group file or identity changed
 * outside Room) and when «last seen» changes. Loading runs on [io] (Room, the keystore and the group file); a newer
 * trigger cancels an older load.
 *
 * [snapshots] is one shared load for every collector: the dashboard, detail, app and thresholds
 * flows, the group state and the widget derive from it on [scope] (the app scope), instead of each doing its own Room
 * read and decode per change. It runs while someone collects and [stopTimeoutMs] longer (a screen rotation keeps it);
 * when it stops, its last snapshot is dropped, so a later subscriber starts from a fresh load, never a stale one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
/** See [Snapshots.zoneWriteState]. */
class ZoneWriteState(val self: DeviceId, val membership: MembershipState, val safeZones: List<DecodedEntry>)

class Snapshots(
    private val db: KinsightDatabase,
    private val groups: GroupStore,
    private val keys: suspend () -> DeviceKeys,
    private val transport: TransportStateStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val stopTimeoutMs: Long = STOP_TIMEOUT_MS,
    private val retryBaseMs: Long = RETRY_BASE_MS,
) {
    private val version = MutableStateFlow(0)

    /** What [snapshots] loads with; tests make it fail. */
    @VisibleForTesting
    internal var loader: suspend (Map<DeviceId, Long>) -> GroupSnapshot = { load(it) }

    fun invalidate() {
        version.update { it + 1 }
    }

    /** Loads that threw in a row (key and group-file errors are snapshots, not throws); 0 after a good load. */
    private val failures = AtomicInteger()
    private var retry: Job? = null

    /**
     * A load that throws (a locked or busy database, say) is transient: it is logged, nothing is emitted (collectors keep
     * the last good snapshot, or Loading before the first) and a retry is scheduled with backoff ([retryBaseMs], then
     * twice as long). Only [MAX_FAILURES] failures in a row are a [GroupSnapshot.StorageError], the destructive
     * «reset identity» root. The shared flow lives on either way: an exception would end it for every collector.
     */
    val snapshots: Flow<GroupSnapshot> =
        combine(db.entriesChanged(), version, transport.peerSeenFlow) { _, _, seen -> seen }
            .mapLatest { seen ->
                try {
                    loader(seen).also { failures.set(0) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val n = failures.incrementAndGet()
                    Log.w(TAG, "snapshot load failed ($n in a row)", e)
                    if (n >= MAX_FAILURES) {
                        failures.set(0)
                        GroupSnapshot.StorageError("${e::class.simpleName}: ${e.message}")
                    } else {
                        scheduleRetry(retryBaseMs shl (n - 1))
                        null
                    }
                }
            }
            .filterNotNull()
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMs, replayExpirationMillis = 0), replay = 1)

    private fun scheduleRetry(afterMs: Long) = synchronized(this) {
        retry?.cancel()
        retry = scope.launch {
            delay(afterMs)
            invalidate()
        }
    }

    /**
     * What a zone write needs and nothing more (sweep review Minor 4): this device, the membership and the SafeZone
     * entries. Read inside the pipeline lock by LocationRepository, so it never loads the whole snapshot there. Null
     * without a group or with an unusable identity or group file.
     */
    suspend fun zoneWriteState(): ZoneWriteState? = withContext(io) {
        val self = try {
            keys().deviceId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext null
        }
        val group = try {
            groups.read()
        } catch (e: GroupStoreException) {
            null
        } ?: return@withContext null
        val store = RoomEntryStore(db, group.groupId)
        val membership = Membership.compute(group.groupId, store.membershipEntries()) ?: return@withContext null
        ZoneWriteState(self, membership, store.safeZones())
    }

    /** [author]'s UsageDetail entries for [range] (level 4); read on demand, the snapshot itself carries none. */
    suspend fun details(groupId: GroupId, author: DeviceId, range: DetailRange): List<DecodedEntry> = details(groupId, author, range.start, range.end)

    /** [author]'s UsageDetail entries of [from]..[to] (also the dashboard's comparison, U2). */
    suspend fun details(groupId: GroupId, author: DeviceId, from: LocalDate, to: LocalDate): List<DecodedEntry> = withContext(io) {
        RoomEntryStore(db, groupId).details(author, from, to)
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
            categoryOverrides = store.categoryOverrides(),
            handovers = store.storageHandovers(),
            locations = store.locations(),
            safeZones = store.safeZones(),
            parentPins = store.parentPins(),
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val RETRY_BASE_MS = 1_000L
        const val MAX_FAILURES = 3
        const val TAG = "KinsightSnapshots"
    }
}
