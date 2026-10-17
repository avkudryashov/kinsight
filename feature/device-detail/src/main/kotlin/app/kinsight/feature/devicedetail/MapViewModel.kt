package app.kinsight.feature.devicedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.ChildLocation
import app.kinsight.core.data.CloudRepository
import app.kinsight.core.data.LocationRepository
import app.kinsight.core.data.MapBlocks
import app.kinsight.core.data.ServiceRepository
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.maps.StreetMaps
import app.kinsight.core.transport.ble.SyncService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal interface MapSource {
    fun children(): Flow<List<ChildLocation>>

    fun cloudOn(): Flow<Boolean>

    fun groupReady(): Flow<Boolean> = flowOf(true)

    /** This phone is an active admin parent: it may edit the zones. */
    fun canEditZones(): Flow<Boolean> = flowOf(false)

    /** When this phone's last sync finished (ServiceStatus.lastSyncMs). */
    fun lastSyncMs(): Flow<Long?> = flowOf(null)

    /** The Overview pull's «Sync now» (ServiceRepository.syncNow): no other network path. */
    fun syncNow() {}

    /** Offline map spec §4: the street map blocks on this phone and whether one is downloading. */
    fun streets(): Flow<StreetMaps> = flowOf(StreetMaps.NONE)
}

private class RepositoryMapSource(
    private val location: LocationRepository,
    private val cloud: CloudRepository,
    private val usage: UsageRepository,
    private val service: ServiceRepository,
    private val maps: MapBlocks,
) : MapSource {
    override fun children() = location.children

    override fun cloudOn() = cloud.status.map { it.on }.distinctUntilChanged()

    override fun groupReady() = usage.groupReady

    override fun canEditZones() = location.canEdit

    override fun lastSyncMs() = service.status.map { it.lastSyncMs }.distinctUntilChanged()

    override fun syncNow() {
        service.syncNow()
    }

    override fun streets() = maps.streets
}

/**
 * The Карта tab: every child's location view, whether the Disk sync is on, and whether the group is readable yet.
 * [canEditZones]: «Добавьте зону «Дом»» opens the zones screen; [searching]: the opening's sync has not finished yet.
 */
data class MapState(
    val children: List<ChildLocation> = emptyList(),
    val cloudOn: Boolean = false,
    val loaded: Boolean = false,
    val canEditZones: Boolean = false,
    val searching: Boolean = false,
    /** Offline map spec §4: which street map blocks are on the phone. */
    val streets: StreetMaps = StreetMaps.NONE,
)

class MapViewModel internal constructor(private val source: MapSource, private val clock: () -> Long = System::currentTimeMillis) : ViewModel() {
    constructor(location: LocationRepository, cloud: CloudRepository, usage: UsageRepository, service: ServiceRepository, maps: MapBlocks) :
        this(RepositoryMapSource(location, cloud, usage, service, maps))

    /** When the tab's opening asked for a sync; null once the fast window is over. */
    private val searchSince = MutableStateFlow<Long?>(null)

    private val search = combine(searchSince, source.lastSyncMs()) { since, last -> since != null && (last == null || last <= since) }

    private val base = combine(source.children(), source.cloudOn(), source.groupReady(), source.canEditZones()) { c, on, ready, admin ->
        MapState(c, on, loaded = ready, canEditZones = admin)
    }

    val state: StateFlow<MapState> = combine(base, search, source.streets()) { s, searching, streets -> s.copy(searching = searching, streets = streets) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapState())

    /**
     * Task 12: the tab opened, so ask for the Overview's «Sync now» once. While that request's fast window lasts a
     * second call (the same opening composed again, a rotation) asks for nothing; the window ends like the Overview's.
     */
    fun onOpen() {
        if (searchSince.value != null) return
        val since = clock()
        searchSince.value = since
        source.syncNow()
        viewModelScope.launch {
            delay(SyncService.FAST_MS)
            if (searchSince.value == since) searchSince.value = null
        }
    }
}
