package app.kinsight.feature.devicedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.ChildLocation
import app.kinsight.core.data.CloudRepository
import app.kinsight.core.data.LocationRepository
import app.kinsight.core.data.UsageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal interface MapSource {
    fun children(): Flow<List<ChildLocation>>

    fun cloudOn(): Flow<Boolean>

    fun groupReady(): Flow<Boolean> = flowOf(true)
}

private class RepositoryMapSource(
    private val location: LocationRepository,
    private val cloud: CloudRepository,
    private val usage: UsageRepository,
) : MapSource {
    override fun children() = location.children

    override fun cloudOn() = cloud.status.map { it.on }.distinctUntilChanged()

    override fun groupReady() = usage.groupReady
}

/** The Карта tab: every child's location view, whether the Disk sync is on, and whether the group is readable yet. */
data class MapState(val children: List<ChildLocation> = emptyList(), val cloudOn: Boolean = false, val loaded: Boolean = false)

class MapViewModel internal constructor(source: MapSource) : ViewModel() {
    constructor(location: LocationRepository, cloud: CloudRepository, usage: UsageRepository) : this(RepositoryMapSource(location, cloud, usage))

    val state: StateFlow<MapState> = combine(source.children(), source.cloudOn(), source.groupReady()) { c, on, ready ->
        MapState(c, on, loaded = ready)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapState())
}
