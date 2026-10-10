package app.kinsight.feature.devicedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.AppList
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The «All apps» list of one range (spec §9.3): the light app list flow held here, so a rotation re-attaches to the
 * running flow and the last list stays on screen instead of reloading from a spinner.
 */
class AllAppsViewModel internal constructor(apps: Flow<AppList?>) : ViewModel() {
    constructor(usage: UsageRepository, id: DeviceId, range: DetailRange) : this(usage.apps(id, range))

    val list: StateFlow<AppList?> = apps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
