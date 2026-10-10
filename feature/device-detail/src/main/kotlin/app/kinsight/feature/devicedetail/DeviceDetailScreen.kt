package app.kinsight.feature.devicedetail

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.AppUsage
import app.kinsight.core.data.DayTotal
import app.kinsight.core.data.DeviceDetail
import app.kinsight.core.data.Period
import app.kinsight.core.data.Role
import app.kinsight.core.data.Sharing
import app.kinsight.core.designsystem.component.AppIcon
import app.kinsight.core.designsystem.component.BarChart
import app.kinsight.core.designsystem.component.EmptyState
import app.kinsight.core.designsystem.component.durationText
import app.kinsight.core.model.DeviceId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

private const val TOP_APPS = 5

/** Events drawn at most; the period's list is uncapped in core/data, so the screen says how many it leaves out. */
private const val MAX_EVENTS = 100

@Composable
fun DeviceDetailRoute(id: DeviceId, onAllApps: (Period) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: DeviceDetailViewModel = viewModel(key = "detail-${id.hex}") { DeviceDetailViewModel(AppGraph.get(context).usage, id) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    detail?.let { DeviceDetailScreen(it, vm::select, onAllApps, onBack) } ?: Loading()
}

@Composable
fun AllAppsRoute(id: DeviceId, period: Period, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: DeviceDetailViewModel = viewModel(key = "apps-${id.hex}-$period") { DeviceDetailViewModel(AppGraph.get(context).usage, id, period) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    detail?.let { AllAppsScreen(it, onBack) } ?: Loading()
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun periodLabel(p: Period) = stringResource(
    when (p) {
        Period.DAY -> R.string.det_day
        Period.WEEK -> R.string.det_week
        Period.MONTH -> R.string.det_month
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) = Scaffold(
    topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.det_back)) }
            },
        )
    },
    content = content,
)

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })

/**
 * Spec §9.3 levels 1 (total and days) and 2 (top apps); levels 3 and 4 come as new sections later. A period without
 * any summary says «no data» rather than «0 min» (DayTotal.hasData); a parent's phone has no usage to show at all
 * (Play Stalkerware ruling), only its events.
 */
@Composable
fun DeviceDetailScreen(detail: DeviceDetail, onPeriod: (Period) -> Unit, onAllApps: (Period) -> Unit, onBack: () -> Unit) =
    Screen(detail.member.name, onBack) { padding ->
        val child = detail.member.role == Role.CHILD
        val hasData = detail.days.any { it.hasData }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("det_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (child) {
                item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Period.entries.forEachIndexed { i, p ->
                            SegmentedButton(
                                selected = p == detail.period,
                                onClick = { onPeriod(p) },
                                shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                            ) { Text(periodLabel(p)) }
                        }
                    }
                }
                if (detail.sharing != Sharing.On) item(key = "sharing") { SharingNote(detail.sharing) }
                if (hasData) {
                    item(key = "total") {
                        Column {
                            Text(durationText(detail.totalMs), style = MaterialTheme.typography.displaySmall)
                            Text(pluralStringResource(R.plurals.det_unlocks, detail.unlocks, detail.unlocks))
                        }
                    }
                    if (detail.period != Period.DAY) item(key = "chart") { DaysChart(detail.days, detail.period) }
                } else {
                    item(key = "no_data") { EmptyState(stringResource(R.string.det_no_data_title), stringResource(R.string.det_no_data_body)) }
                }
                item(key = "apps_title") { SectionTitle(stringResource(R.string.det_top_apps)) }
                if (detail.apps.isEmpty()) item(key = "no_apps") { Text(stringResource(R.string.det_no_apps)) }
                items(detail.apps.take(TOP_APPS), key = { "app:${it.packageName}" }) { AppRow(it) }
                if (detail.apps.size > TOP_APPS) {
                    item(key = "all_apps") {
                        TextButton({ onAllApps(detail.period) }, Modifier.testTag("det_all_apps")) {
                            Text(stringResource(R.string.det_all_apps, detail.apps.size))
                        }
                    }
                }
            } else {
                item(key = "not_shared") { Text(stringResource(R.string.det_parent_not_shared)) }
            }
            item(key = "events_title") { SectionTitle(stringResource(R.string.det_events)) }
            if (detail.events.isEmpty()) item(key = "no_events") { Text(stringResource(R.string.det_no_events)) }
            items(detail.events.take(MAX_EVENTS), key = { "ev:${it.author.hex}-${it.seq}" }) { e ->
                ListItem(
                    headlineContent = { Text(eventText(e)) },
                    supportingContent = { Text(dateTime(e.createdMs)) },
                    modifier = Modifier.testTag("det_event_${e.seq}"),
                )
            }
            if (detail.events.size > MAX_EVENTS) {
                item(key = "events_more") { Text(stringResource(R.string.det_events_capped, MAX_EVENTS, detail.events.size)) }
            }
        }
    }

@Composable
fun AllAppsScreen(detail: DeviceDetail, onBack: () -> Unit) =
    Screen(stringResource(R.string.det_all_apps_title, detail.member.name, periodLabel(detail.period)), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("det_apps"), contentPadding = PaddingValues(16.dp)) {
            if (detail.apps.isEmpty()) item { Text(stringResource(R.string.det_no_apps)) }
            items(detail.apps, key = { it.packageName }) { AppRow(it) }
        }
    }

@Composable
private fun dateTime(ms: Long): String =
    DateUtils.formatDateTime(LocalContext.current, ms, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

@Composable
private fun AppRow(app: AppUsage) = ListItem(
    leadingContent = { AppIcon(app.icon) },
    headlineContent = { Text(app.label) },
    trailingContent = { Text(durationText(app.totalMs)) },
)

/** Average and busiest day count only days with a summary, as on the dashboard card. */
@Composable
private fun DaysChart(days: List<DayTotal>, period: Period) {
    val locale = LocalConfiguration.current.locales[0]
    val labels = when (period) {
        Period.WEEK -> days.map { it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) }
        else -> days.mapIndexed { i, d -> if (i % 5 == 4 || i == days.lastIndex) d.date.dayOfMonth.toString() else "" }
    }
    val withData = days.filter { it.hasData }
    val busiest = withData.maxByOrNull { it.totalMs } ?: return
    val busiestName = when (period) {
        Period.WEEK -> busiest.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> busiest.date.format(DateTimeFormatter.ofPattern("d MMMM", locale))
    }
    val summary = stringResource(
        R.string.det_chart_summary,
        durationText(withData.sumOf { it.totalMs } / withData.size),
        busiestName,
        durationText(busiest.totalMs),
    )
    BarChart(days.map { it.totalMs }, labels, summary)
}

@Composable
private fun SharingNote(s: Sharing) {
    val note = when (s) {
        is Sharing.Paused -> stringResource(R.string.det_paused_note, dateTime(s.sinceMs))
        Sharing.Never -> stringResource(R.string.det_never_note)
        Sharing.Left -> stringResource(R.string.det_left_note)
        Sharing.On -> return
    }
    Text(note, color = MaterialTheme.colorScheme.tertiary)
}
