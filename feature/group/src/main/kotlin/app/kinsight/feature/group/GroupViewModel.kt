package app.kinsight.feature.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.GroupRepository
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.LeaveError
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the group screen needs from [GroupRepository]; a seam for tests. */
internal interface GroupActions {
    val state: Flow<GroupState>
    val leaving: StateFlow<Boolean>
    val leaveError: StateFlow<LeaveError?>

    suspend fun grantAdmin(id: DeviceId)

    suspend fun remove(id: DeviceId)

    fun leaveAndForget()

    fun dismissLeaveError()
}

private class RepositoryActions(private val group: GroupRepository) : GroupActions {
    override val state = group.state
    override val leaving = group.leaving
    override val leaveError = group.leaveError

    override suspend fun grantAdmin(id: DeviceId) = group.grantAdmin(id)

    override suspend fun remove(id: DeviceId) = group.remove(id)

    override fun leaveAndForget() {
        group.leaveAndForget()
    }

    override fun dismissLeaveError() = group.dismissLeaveError()
}

/** Something the group screen tried and could not do; shown in a dialog until dismissed. */
sealed interface GroupError {
    data class Leave(val reason: LeaveError) : GroupError

    data object GrantFailed : GroupError

    data object RemoveFailed : GroupError
}

/**
 * The group screen's state and actions. Leaving runs on the app scope inside [GroupRepository.leaveAndForget]
 * (preflight C5), so it outlives this view model; its failures arrive through [GroupRepository.leaveError].
 */
class GroupViewModel internal constructor(private val actions: GroupActions) : ViewModel() {
    constructor(group: GroupRepository) : this(RepositoryActions(group))

    val state: StateFlow<GroupState> = actions.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupState.Loading)

    val leaving: StateFlow<Boolean> = actions.leaving

    private val actionError = MutableStateFlow<GroupError?>(null)

    /** A failed admin action first, then a failed leave. */
    val error: StateFlow<GroupError?> = combine(actionError, actions.leaveError) { a, l -> a ?: l?.let(GroupError::Leave) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Throws without a group (GroupRepository): shown as [GroupError.GrantFailed], never a crash. */
    fun grantAdmin(id: DeviceId) = attempt(GroupError.GrantFailed) { actions.grantAdmin(id) }

    fun remove(id: DeviceId) = attempt(GroupError.RemoveFailed) { actions.remove(id) }

    fun leave() = actions.leaveAndForget()

    fun dismissError() {
        if (actionError.value != null) actionError.value = null else actions.dismissLeaveError()
    }

    private fun attempt(onFailure: GroupError, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                actionError.value = onFailure
            }
        }
    }
}
