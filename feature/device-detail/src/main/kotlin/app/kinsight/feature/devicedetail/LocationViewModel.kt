package app.kinsight.feature.devicedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.ChildLocation
import app.kinsight.core.data.LocationRepository
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

internal interface LocationSource {
    fun child(): Flow<ChildLocation?>
    fun groupReady(): Flow<Boolean> = flowOf(true)
}

private class RepositoryLocationSource(private val location: LocationRepository, private val usage: UsageRepository, private val id: DeviceId) : LocationSource {
    override fun child() = location.child(id)
    override fun groupReady() = usage.groupReady
}

/** The screen's view and whether a null one means «no such device»: one value, so they never disagree for a frame. */
data class LocationState(val child: ChildLocation? = null, val loaded: Boolean = false)

class LocationViewModel internal constructor(source: LocationSource) : ViewModel() {
    constructor(location: LocationRepository, usage: UsageRepository, id: DeviceId) : this(RepositoryLocationSource(location, usage, id))

    /**
     * One collection of the child's view; [LocationState.loaded] once the first view arrived while the group is
     * readable (a null child is then «no such device»).
     */
    val state: StateFlow<LocationState> = combine(
        source.child().map { LocationState(it, loaded = true) }.onStart { emit(LocationState()) },
        source.groupReady(),
    ) { s, ready -> s.copy(loaded = s.loaded && ready) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationState())
}
