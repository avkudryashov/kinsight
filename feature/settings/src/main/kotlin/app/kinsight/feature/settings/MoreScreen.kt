package app.kinsight.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.blocking.BlockingState
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.ChildSetup
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.PinSave
import app.kinsight.core.data.Role
import app.kinsight.core.data.SetupItem
import app.kinsight.core.data.UsageReadModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** The settings view model, one per destination of «Ещё» (each reads the same repositories). */
@Composable
internal fun settingsViewModel(): SettingsViewModel {
    val context = LocalContext.current
    return viewModel {
        val g = AppGraph.get(context)
        SettingsViewModel(g.group, g.settingsRepository, g.service, g.reset, g.usage, g.cloudRepository, g.childLock)
    }
}

@Composable
fun MoreRoute(
    appVersion: String,
    onFamily: () -> Unit,
    onLimits: () -> Unit,
    onSync: () -> Unit,
    onTransparency: () -> Unit,
    onAbout: () -> Unit,
    onSetup: () -> Unit,
) {
    val context = LocalContext.current
    val vm = settingsViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    // App blocking spec §5.4: the accessibility step counts only while a parent's rule needs it.
    val enforcing by remember {
        AppGraph.get(context).let { combine(it.snapshots.snapshots, it.settingsRepository.usageSettings, ChildSetup::enforcing) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    fun read() = ChildSetup.read(context, BlockingState.serviceEnabled(context), enforcing)
    var pending by remember { mutableStateOf(read()) }
    LaunchedEffect(enforcing) { pending = read() }
    // Back from Android's settings or from the wizard: the setup row follows at once.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshStatus()
        pending = read()
    }
    MoreScreen(ui, appVersion, pending, onFamily, onLimits, onSync, onTransparency, onAbout, onSetup, onSetPin = vm::setPin)
}

/**
 * «Ещё» (UI refresh §2): short rows with a caption or a status. A parent's: Family, Limits and notifications, Sync (one
 * line, «В порядке» or the problem), the parent PIN (an admin), Who sees my data, About. A child's: an unfinished setup
 * first (§6, only while it consents), Family, Sync, Who sees my data, About. While the group loads, no row only a parent
 * may open is shown (Settings fails closed the same way).
 */
@Composable
fun MoreScreen(
    ui: SettingsUi,
    appVersion: String,
    setupPending: List<SetupItem>,
    onFamily: () -> Unit,
    onLimits: () -> Unit,
    onSync: () -> Unit,
    onTransparency: () -> Unit,
    onAbout: () -> Unit,
    onSetup: () -> Unit,
    onSetPin: (String) -> Unit,
) {
    val role = (ui.group as? GroupState.Ready)?.self?.role
    val parent = role == Role.PARENT
    val child = role == Role.CHILD
    var setPin by rememberSaveable { mutableStateOf(false) }
    val collects = UsageReadModel.collects(ui.ownSharing, localConsent = ui.usage.consentAtMs != null)
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(
        Modifier.fillMaxSize().testTag("more_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(stringResource(R.string.more_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) }
        if (child && collects && setupPending.isNotEmpty()) {
            item {
                val caption = setupPending.map { setupName(it) }.joinToString(", ").replaceFirstChar { it.titlecase(locale) }
                MoreRow(stringResource(R.string.more_setup), caption, "more_setup", onSetup, warning = true)
            }
        }
        item { MoreRow(stringResource(R.string.more_family), stringResource(R.string.more_family_body), "more_family", onFamily) }
        if (parent) item { MoreRow(stringResource(R.string.more_limits), stringResource(R.string.more_limits_body), "more_limits", onLimits) }
        item {
            val line = syncState(ui.service)
            val ok = line == SyncStateLine.RUNNING || line == SyncStateLine.SYNCING
            MoreRow(stringResource(R.string.more_sync), if (ok) stringResource(R.string.more_sync_ok) else syncStateText(ui.service), "more_sync", onSync, warning = !ok)
        }
        if (parent && ui.lock.status.canSetPin) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MoreRow(
                        stringResource(R.string.more_pin),
                        stringResource(if (ui.lock.status.pinSet) R.string.more_pin_set else R.string.more_pin_none),
                        "more_pin",
                        { setPin = true },
                    )
                    when (ui.lock.pinSaved) {
                        PinSave.SAVED -> Text(stringResource(R.string.set_pin_saved), Modifier.padding(horizontal = 16.dp))
                        PinSave.INVALID, PinSave.NOT_ALLOWED, PinSave.FAILED ->
                            Text(stringResource(R.string.set_pin_failed), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                        null -> Unit
                    }
                }
            }
        }
        item { MoreRow(stringResource(R.string.set_transparency), stringResource(R.string.set_transparency_body), "more_transparency", onTransparency) }
        item { MoreRow(stringResource(R.string.more_about), stringResource(R.string.set_version, appVersion), "more_about", onAbout) }
    }
    if (setPin) {
        SetPinDialog(
            onSave = {
                onSetPin(it)
                setPin = false
            },
            onDismiss = { setPin = false },
        )
    }
}

/** One row of «Ещё»: a white card, title and caption; [warning] turns it into an attention card. */
@Composable
private fun MoreRow(title: String, caption: String, tag: String, onClick: () -> Unit, warning: Boolean = false) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        colors = if (warning) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warning) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun setupName(item: SetupItem): String = stringResource(
    when (item) {
        SetupItem.USAGE_ACCESS -> R.string.more_setup_usage
        SetupItem.LOCATION -> R.string.more_setup_location
        SetupItem.LOCATION_BACKGROUND -> R.string.more_setup_background
        SetupItem.BATTERY -> R.string.more_setup_battery
        SetupItem.ACCESSIBILITY -> R.string.more_setup_accessibility
    },
)
