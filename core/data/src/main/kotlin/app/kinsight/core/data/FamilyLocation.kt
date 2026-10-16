package app.kinsight.core.data

import app.kinsight.core.crypto.hexToBytes
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.location.LocationHost
import app.kinsight.core.location.Report
import app.kinsight.core.location.Zone
import app.kinsight.core.location.ZoneEvent
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.inZoneEntry
import app.kinsight.core.model.locationEntry
import app.kinsight.core.model.zoneEventEntry
import app.kinsight.core.transport.ble.GroupStore
import app.kinsight.core.usage.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The family side of the child's tracking (core/location's [LocationHost], spec §8): this phone tracks only as an active
 * CHILD that consented and shares, in a group with a storage owner (the cloud is on); the zones are the family's
 * effective ones ([SafeZones]); events and reports are signed through the one [appendOwn] (the pipeline lock); [upload]
 * asks for a cloud run at once. [followNeeded] is the parent's side of spec §9.
 *
 * Rulings L6/L8: nothing here takes the cloud runLock (the engine is called after a run, and a run may be in flight while
 * an alert fires), and [upload] only enqueues work, it never runs the cloud itself.
 */
class FamilyLocation(
    private val db: KinsightDatabase,
    private val groups: GroupStore,
    private val keys: suspend () -> DeviceKeys,
    private val settings: SettingsStore,
    private val appendOwn: OwnAppend,
    private val snapshots: Snapshots,
    private val upload: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LocationHost {
    override suspend fun zones(): List<Zone>? = withContext(io) {
        val (store, m) = tracking() ?: return@withContext null
        SafeZones.effective(store.safeZones(), m).map { Zone(it.id, it.latE7, it.lonE7, it.radiusM) }
    }

    /**
     * The gate is checked again inside the append, under the pipeline lock that a pause (UsagePipeline.setSharing) also
     * takes: a pause or a lost gate while the engine waited for its fix signs nothing (false).
     */
    override suspend fun record(events: List<ZoneEvent>, report: Report?): Boolean {
        var open = false
        val appended = appendOwn { a ->
            open = withContext(io) { tracking() } != null
            if (!open) return@appendOwn emptyList()
            events.map { a.zoneEventEntry(it.zoneId.hexToBytes(), it.entered, it.atMs) } + listOfNotNull(
                when (report) {
                    is Report.InZone -> a.inZoneEntry(report.zoneId.hexToBytes(), report.atMs)
                    is Report.Point -> report.fix.let { f -> a.locationEntry(f.latE7, f.lonE7, f.accuracyM, f.timeMs, f.provider) }
                    null -> null
                },
            )
        }
        return appended && open
    }

    /** This phone's group store and membership while it may track (an active consenting, sharing CHILD; cloud on), else null. */
    private suspend fun tracking(): Pair<RoomEntryStore, MembershipState>? {
        val group = groups.read() ?: return null
        val store = RoomEntryStore(db, group.groupId)
        val m = Membership.compute(group.groupId, store.membershipEntries()) ?: return null
        val me = m.active[keys().deviceId] ?: return null
        val s = settings.current()
        if (!me.isChild || m.storageOwner == null || s.consentAtMs == null || !s.sharing) return null
        return store to m
    }

    override fun upload() = upload.invoke()

    /** Spec §9: on a parent's phone, a child is outside its zones and its word is fresh (LocationReadModel.followNeeded). */
    suspend fun followNeeded(): Boolean {
        val ready = snapshots.load(emptyMap()) as? GroupSnapshot.Ready ?: return false
        return LocationReadModel.followNeeded(ready, clock())
    }
}
