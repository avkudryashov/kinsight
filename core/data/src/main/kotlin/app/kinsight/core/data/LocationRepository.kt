package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.crypto.hexToBytes
import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.safeZoneEntry
import app.kinsight.core.sync.LocationRules
import app.kinsight.core.sync.SafeZoneRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId

/** A parent's new ([id] null) or edited zone; centre in degrees × 10^7. */
data class ZoneDraft(val id: String?, val name: String, val latE7: Int, val lonE7: Int, val radiusM: Int) {
    override fun toString() = "ZoneDraft(${id?.take(8)}, r=$radiusM)"
}

enum class ZoneSave { SAVED, UNCHANGED, INVALID, NOT_ALLOWED }

/**
 * The location screens' reads and the zone writes (spec §8, §8.1, §12). Reads derive from the shared snapshot; a write
 * appends one SafeZone entry under [zoneLock] (lock order: this, then the pipeline lock inside [appendOwn]), reading
 * the fresh snapshot and the rank inside the append. Only an active admin parent writes (spec §8.1), and only what the receivers' SafeZoneRules accept.
 */
class LocationRepository(
    private val snapshots: Snapshots,
    private val appendOwn: OwnAppend,
    private val date: () -> LocalDate = LocalDate::now,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val random: SecureRandom = SecureRandom(),
) {
    val zones: Flow<List<ZoneView>> = snapshots.snapshots.map { s -> (s as? GroupSnapshot.Ready)?.let(SafeZones::effective).orEmpty() }.distinctUntilChanged()

    /** This phone is an active admin parent: it may edit the zones (spec §8.1; a child and other parents see them only). */
    val canEdit: Flow<Boolean> = snapshots.snapshots.map { s ->
        (s as? GroupSnapshot.Ready)?.takeUnless { it.removed }?.let { r -> r.membership.active[r.self]?.let { !it.isChild && it.isAdmin } } ?: false
    }.distinctUntilChanged()

    /** Parent phones only: every active child's newest point, for «где сейчас» as a zone centre. */
    val lastPoints: Flow<List<Pair<MemberView, GeoPoint>>> = snapshots.snapshots.map { s ->
        val ready = (s as? GroupSnapshot.Ready)?.takeIf(::viewerIsParent) ?: return@map emptyList()
        SnapshotViews.members(ready, clock()).filter { it.role == Role.CHILD }.mapNotNull { m ->
            ready.locations.filter { it.author == m.id }.sortedBy { it.seq }.mapNotNull(LocationReadModel::point).lastOrNull()?.let { m to it }
        }
    }.distinctUntilChanged()

    fun child(id: DeviceId): Flow<ChildLocation?> = snapshots.snapshots.map { s ->
        (s as? GroupSnapshot.Ready)?.takeIf(::viewerIsParent)?.let { LocationReadModel.childLocation(it, id, date(), zone(), clock()) }
    }.distinctUntilChanged()

    /** L3: a child's phone never shows another child's route or place; only an active parent's does. */
    private fun viewerIsParent(r: GroupSnapshot.Ready) = !r.removed && r.membership.active[r.self]?.isChild == false

    private val zoneLock = Mutex()

    suspend fun save(d: ZoneDraft): ZoneSave = zoneLock.withLock {
        val name = d.name.trim()
        val idOk = d.id == null || HEX32.matches(d.id)
        if (!idOk || !SafeZoneRules.nameOk(name) || d.radiusM !in SafeZoneRules.MIN_RADIUS_M..SafeZoneRules.MAX_RADIUS_M ||
            !LocationRules.coordinatesOk(d.latE7, d.lonE7)
        ) {
            return@withLock ZoneSave.INVALID
        }
        val id = d.id ?: ByteArray(16).also(random::nextBytes).toHex()
        // The winner and its rank are read inside the append (Y4 parked minor): a version that arrived since the screen
        // last read is outranked, never tied.
        var result = ZoneSave.NOT_ALLOWED
        val ok = appendOwn { a ->
            val ready = parentSnapshot() ?: return@appendOwn emptyList()
            val winner = SafeZones.winners(ready.safeZones, ready.membership)[id]
            val w = winner?.body?.safe_zone
            if (w != null && !w.deleted && w.name == name && w.lat_e7 == d.latE7 && w.lon_e7 == d.lonE7 && w.radius_m == d.radiusM) {
                result = ZoneSave.UNCHANGED
                return@appendOwn emptyList()
            }
            result = ZoneSave.SAVED
            listOf(a.safeZoneEntry(id.hexToBytes(), name, d.latE7, d.lonE7, d.radiusM, SafeZones.nextRank(winner)))
        }
        if (!ok) Log.w(TAG, "zone: no group to sign in")
        if (ok) result else ZoneSave.NOT_ALLOWED
    }

    /** A tombstone over the winner (spec §8.1 `deleted`): the zone goes for every phone, its name stays for old events. */
    suspend fun delete(id: String): ZoneSave = zoneLock.withLock {
        var result = ZoneSave.NOT_ALLOWED
        val ok = appendOwn { a ->
            val ready = parentSnapshot() ?: return@appendOwn emptyList()
            val winner = SafeZones.winners(ready.safeZones, ready.membership)[id]
            val w = winner?.body?.safe_zone
            if (w == null || w.deleted) {
                result = ZoneSave.UNCHANGED
                return@appendOwn emptyList()
            }
            result = ZoneSave.SAVED
            listOf(a.safeZoneEntry(id.hexToBytes(), w.name, w.lat_e7, w.lon_e7, w.radius_m, SafeZones.nextRank(winner), deleted = true))
        }
        if (ok) result else ZoneSave.NOT_ALLOWED
    }

    /**
     * A fresh read (the shared flow may lag a just-committed append) when this phone is an active admin parent. Called
     * inside the append (under the pipeline lock): it reads the database only, never the pipeline.
     */
    private suspend fun parentSnapshot(): GroupSnapshot.Ready? {
        val ready = snapshots.load(emptyMap()) as? GroupSnapshot.Ready ?: return null
        val me = ready.membership.active[ready.self] ?: return null
        return ready.takeIf { !it.removed && !me.isChild && me.isAdmin }
    }

    private companion object {
        const val TAG = "KinsightZones"
        val HEX32 = Regex("^[0-9a-f]{32}$")
    }
}
