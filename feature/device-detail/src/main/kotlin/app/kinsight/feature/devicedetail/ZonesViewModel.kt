package app.kinsight.feature.devicedetail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.GeoPoint
import app.kinsight.core.data.LocationRepository
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.ZoneDraft
import app.kinsight.core.data.ZoneSave
import app.kinsight.core.data.ZoneView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal interface ZoneSource {
    fun zones(): Flow<List<ZoneView>>
    fun canEdit(): Flow<Boolean>
    fun lastPoints(): Flow<List<Pair<MemberView, GeoPoint>>>
    suspend fun save(d: ZoneDraft): ZoneSave
    suspend fun delete(id: String): ZoneSave
}

private class RepositoryZoneSource(private val location: LocationRepository) : ZoneSource {
    override fun zones() = location.zones
    override fun canEdit() = location.canEdit
    override fun lastPoints() = location.lastPoints
    override suspend fun save(d: ZoneDraft) = location.save(d)
    override suspend fun delete(id: String) = location.delete(id)
}

/** A refused or failed zone write: [delete] tells a delete from a save, so the screen names the right one. */
data class ZoneFailure(val result: ZoneSave, val delete: Boolean = false)

/** [children]: each child's newest point, offered as a centre. */
data class ZonesState(val zones: List<ZoneView>, val canEdit: Boolean, val children: List<Pair<MemberView, GeoPoint>>)

class ZonesViewModel internal constructor(private val source: ZoneSource) : ViewModel() {
    constructor(location: LocationRepository) : this(RepositoryZoneSource(location))

    val state: StateFlow<ZonesState?> = combine(source.zones(), source.canEdit(), source.lastPoints(), ::ZonesState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _failed = MutableStateFlow<ZoneFailure?>(null)

    /** The last refused or failed write (shown until dismissed or the next attempt). */
    val failed: StateFlow<ZoneFailure?> = _failed.asStateFlow()

    fun save(d: ZoneDraft, onDone: () -> Unit) = write(delete = false, { source.save(d) }, onDone)

    fun delete(id: String) = write(delete = true, { source.delete(id) }, {})

    fun dismissError() {
        _failed.value = null
    }

    private fun write(delete: Boolean, op: suspend () -> ZoneSave, onDone: () -> Unit) {
        _failed.value = null
        viewModelScope.launch {
            val r = try {
                op()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "zone write failed: ${e.javaClass.simpleName}")
                ZoneSave.INVALID
            }
            if (r == ZoneSave.SAVED || r == ZoneSave.UNCHANGED) onDone() else _failed.value = ZoneFailure(r, delete)
        }
    }

    private companion object {
        const val TAG = "KinsightZones"
    }
}
