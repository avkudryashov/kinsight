package app.kinsight.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.Role
import app.kinsight.core.data.SummaryMode
import app.kinsight.core.model.DeviceId

@Composable
fun LimitsNotificationsRoute(onChild: (DeviceId) -> Unit, onBack: () -> Unit) {
    val vm = settingsViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LimitsNotificationsScreen(ui, onChild, vm::setSummaryMode, vm::setNoContactHours, onBack)
}

/**
 * «Ещё → Лимиты и уведомления» (UI refresh §2): each child opens its limits (§7); the summaries, the «no contact»
 * window and Android's notification settings moved here from Settings unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitsNotificationsScreen(
    ui: SettingsUi,
    onChild: (DeviceId) -> Unit,
    onSummaryMode: (SummaryMode) -> Unit,
    onNoContactHours: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val children = (ui.group as? GroupState.Ready)?.members.orEmpty().filter { it.role == Role.CHILD }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_limits)) },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.set_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("lim_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text(stringResource(R.string.lim_children), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
            if (children.isEmpty()) item { Text(stringResource(R.string.lim_no_children)) }
            items(children, key = { it.id.hex }) { c ->
                ListItem(
                    headlineContent = { Text(c.name) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable(onClickLabel = stringResource(R.string.set_open), role = SemanticsRole.Button) { onChild(c.id) }
                        .testTag("lim_child_${c.id.hex}"),
                )
            }
            section(R.string.set_notifications) {
                Text(stringResource(R.string.set_summaries))
                Choices(SummaryMode.entries, ui.prefs.summaryMode, "set_mode", {
                    stringResource(
                        when (it) {
                            SummaryMode.OFF -> R.string.set_summary_off
                            SummaryMode.DAILY -> R.string.set_summary_daily
                            SummaryMode.WEEKLY -> R.string.set_summary_weekly
                        },
                    )
                }, onSummaryMode)
                Text(stringResource(R.string.set_no_contact))
                Choices(RetentionRules.NO_CONTACT, ui.prefs.noContactHours, "set_no_contact", { stringResource(R.string.set_hours, it) }, onNoContactHours)
                TextButton({
                    try {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    } catch (_: ActivityNotFoundException) {
                        // No notification settings page on this phone: nothing to open.
                    }
                }) { Text(stringResource(R.string.set_notification_settings)) }
            }
        }
    }
}
