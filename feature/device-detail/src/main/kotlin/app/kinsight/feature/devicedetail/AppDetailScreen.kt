package app.kinsight.feature.devicedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppDetail
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.Categories
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.Period
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.designsystem.component.AppIcon
import app.kinsight.core.designsystem.component.BarChart
import app.kinsight.core.designsystem.component.ChartScale
import app.kinsight.core.designsystem.component.EmptyState
import app.kinsight.core.designsystem.component.PeriodHeader
import app.kinsight.core.designsystem.component.StackedBarChart
import app.kinsight.core.designsystem.component.categoryColor
import app.kinsight.core.designsystem.component.dialogTestTags
import app.kinsight.core.designsystem.component.durationText
import app.kinsight.core.model.DeviceId
import app.kinsight.core.usage.UsageSettings
import java.time.LocalDate
import java.time.format.TextStyle

/** The app screen of device [id] and [packageName], opening on [range] (the detail screen's period). */
@Composable
fun AppDetailRoute(id: DeviceId, packageName: String, range: DetailRange, onBack: () -> Unit) {
    val graph = AppGraph.get(LocalContext.current)
    val vm: AppDetailViewModel = viewModel(key = "app-${id.hex}-$packageName") {
        AppDetailViewModel(graph.usage, id, packageName, range, createSavedStateHandle(), writeScope = graph.scope)
    }
    val app by vm.detail.collectAsStateWithLifecycle()
    val settings by graph.settingsRepository.usageSettings.collectAsStateWithLifecycle(initialValue = UsageSettings())
    val threshold by vm.threshold.collectAsStateWithLifecycle()
    val changedBy by vm.categoryChangedBy.collectAsStateWithLifecycle()
    app?.let {
        AppDetailScreen(
            it, vm::select, onBack,
            onPrevious = vm::back, onNext = vm::forward, onOpenDay = vm::openDay, onCategory = vm::setCategory,
            detailRetentionDays = settings.detailRetentionDays,
            categoryChangedBy = changedBy,
            threshold = threshold, onThreshold = vm::setThreshold, onRemoveThreshold = vm::removeThreshold,
        )
    } ?: Loading()
}

/**
 * One app of a child (spec §9.3, stage 5a): icon, label and category chip, the place in the top, then the period switch
 * and header like the detail screen, the time of the range with per-day bars (WEEK, MONTH: tap a bar to open that
 * day) or clock hours (DAY, or why there are none). «Change category» is offered only when
 * [AppDetail.canChangeCategory] (a parent, not the `~other` remainder); its dialog's «Default» passes null.
 * «Notify if more than…» (the same parent's phone, a child's app) sets, changes or deletes the app's daily limit
 * [threshold] (spec §9.7, stage 5b).
 */
@Composable
fun AppDetailScreen(
    app: AppDetail,
    onPeriod: (Period) -> Unit,
    onBack: () -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onOpenDay: (LocalDate) -> Unit = {},
    onCategory: (AppCategory?) -> Unit = {},
    detailRetentionDays: Int = UsageSettings().detailRetentionDays,
    threshold: ThresholdRule? = null,
    onThreshold: (Long) -> Unit = {},
    onRemoveThreshold: () -> Unit = {},
    categoryChangedBy: String? = null,
) = Screen(appLabel(app.packageName, app.label), onBack) { padding ->
    val today = app.today ?: LocalDate.now()
    val known = app.days.any { it.known }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var limiting by rememberSaveable { mutableStateOf(false) }
    val canLimit = canSetThreshold(app)
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).testTag("app_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "head") { Head(app, categoryChangedBy) }
        if (app.canChangeCategory) {
            item(key = "change") {
                TextButton({ choosing = true }, Modifier.heightIn(min = 48.dp).testTag("app_category")) {
                    Text(stringResource(R.string.app_change_category))
                }
            }
        }
        if (canLimit) {
            item(key = "threshold") {
                TextButton({ limiting = true }, Modifier.heightIn(min = 48.dp).testTag("app_threshold")) {
                    Text(
                        threshold?.let { stringResource(R.string.app_threshold_set, durationText(it.limitMs)) }
                            ?: stringResource(R.string.app_threshold),
                    )
                }
            }
        }
        item(key = "periods") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Period.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = p == app.period,
                        onClick = { onPeriod(p) },
                        shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                        modifier = Modifier.testTag("app_period_${p.name.lowercase()}"),
                    ) { Text(periodLabel(p)) }
                }
            }
        }
        item(key = "header") {
            PeriodHeader(
                title = rangeTitle(app.range, today),
                canBack = app.canGoBack,
                canForward = app.canGoForward,
                onBack = onPrevious,
                onForward = onNext,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            !known && app.period == Period.DAY ->
                item(key = "no_data") { Text(stringResource(R.string.det_no_data_day), style = MaterialTheme.typography.titleLarge) }
            !known -> item(key = "no_data") { EmptyState(stringResource(R.string.det_no_data_title), stringResource(R.string.det_no_data_body)) }
            else -> {
                item(key = "total") {
                    Column {
                        Text(durationText(app.totalMs), style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("app_total"))
                        val rank = app.rank
                        Text(if (rank != null) stringResource(R.string.app_rank, rank) else stringResource(R.string.app_not_used))
                    }
                }
                if (app.period == Period.DAY) {
                    item(key = "hours_title") { SectionTitle(stringResource(R.string.app_hours_title)) }
                    val hours = app.hours
                    // Without detail: past the viewer's detail retention it was pruned, otherwise it has not arrived yet.
                    val pruned = app.range.start.isBefore(today.minusDays(detailRetentionDays.toLong()))
                    when {
                        // The remainder's hours hold other apps than its daily total (per-hour vs per-day limit): no bars.
                        app.packageName == Categories.REMAINDER_PACKAGE -> item(key = "hours_remainder") {
                            Text(stringResource(R.string.app_hours_remainder), Modifier.testTag("app_hours_remainder"))
                        }
                        hours != null -> item(key = "hours") { AppHours(hours) }
                        pruned -> item(key = "hours_unavailable") {
                            Text(
                                pluralStringResource(R.plurals.det_hours_unavailable, detailRetentionDays, detailRetentionDays),
                                Modifier.testTag("app_hours_unavailable"),
                            )
                        }
                        else -> item(key = "hours_not_arrived") {
                            Text(stringResource(R.string.det_hours_not_arrived), Modifier.testTag("app_hours_not_arrived"))
                        }
                    }
                } else if (app.totalMs > 0) {
                    item(key = "days_title") { SectionTitle(stringResource(R.string.app_days_title)) }
                    item(key = "chart") { AppDays(app, today, onOpenDay, limit = threshold?.limitMs) } // U19: the app's limit on its days
                }
            }
        }
    }
    if (limiting && canLimit) {
        ThresholdDialog(
            title = stringResource(R.string.app_threshold_title, appLabel(app.packageName, app.label)),
            fixedTarget = ThresholdTarget.App(app.packageName),
            existing = listOfNotNull(threshold),
            apps = emptyList(),
            onDismiss = { limiting = false },
            onSave = { _, ms ->
                limiting = false
                onThreshold(ms)
            },
            onDelete = threshold?.let {
                {
                    limiting = false
                    onRemoveThreshold()
                }
            },
            deleteId = threshold?.id,
        )
    }
    if (choosing) {
        CategoryDialog(
            label = appLabel(app.packageName, app.label),
            current = app.category.takeIf { app.overridden },
            default = app.defaultCategory,
            onDismiss = { choosing = false },
            onConfirm = {
                choosing = false
                onCategory(it)
            },
        )
    }
}

/** Icon, label and the category chip (a parent's own choice says so). */
@Composable
private fun Head(app: AppDetail, changedBy: String?) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    AppIcon(app.icon, Modifier.size(56.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(appLabel(app.packageName, app.label), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("app_label"))
        val name = categoryName(app.category)
        val chipDescription = stringResource(R.string.app_category_label, name)
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.testTag("app_category_chip").semantics(mergeDescendants = true) { contentDescription = chipDescription },
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(categoryColor(app.category.ordinal), CircleShape))
                Text(name, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (changedBy != null) {
            val who = changedBy.ifBlank { stringResource(R.string.app_category_another_parent) }
            Text(stringResource(R.string.app_category_changed_by, who), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("app_category_author"))
        } else if (app.overridden) {
            Text(stringResource(R.string.app_category_yours), style = MaterialTheme.typography.bodySmall)
        }
        if (app.canChangeCategory) {
            Text(stringResource(R.string.app_category_shared), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("app_category_shared"))
        }
    }
}

/** Single choice of [AppCategory] plus «Default ([default])» (null); starts on the parent's current choice, else «Default». */
@Composable
private fun CategoryDialog(
    label: String,
    current: AppCategory?,
    default: AppCategory,
    onDismiss: () -> Unit,
    onConfirm: (AppCategory?) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dialogTestTags(), // its own window: the root flag does not reach it
        title = { Text(stringResource(R.string.app_category_title, label)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                Option(stringResource(R.string.app_category_default_of, categoryName(default)), selected == null, "app_category_option_default") { selected = null }
                AppCategory.entries.forEach { c ->
                    Option(categoryName(c), selected == c, "app_category_option_${c.name}") { selected = c }
                }
            }
        },
        confirmButton = {
            TextButton({ onConfirm(selected) }, Modifier.testTag("app_category_confirm")) { Text(stringResource(R.string.app_category_save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.app_category_cancel)) } },
    )
}

@Composable
private fun Option(text: String, selected: Boolean, tag: String, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, onClick = onClick, role = Role.RadioButton).testTag(tag),
    verticalAlignment = Alignment.CenterVertically,
) {
    RadioButton(selected = selected, onClick = null)
    Text(text, Modifier.padding(start = 12.dp))
}

/**
 * The app's time per day of a WEEK or MONTH in its category's colour, today highlighted; a tap opens that day. A
 * [limit] (the app's daily limit) draws its line.
 */
@Composable
private fun AppDays(app: AppDetail, today: LocalDate, onOpenDay: (LocalDate) -> Unit, limit: Long? = null) {
    val locale = locale()
    val days = app.days
    val labels = when (app.period) {
        Period.WEEK -> days.map { it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) }
        else -> days.mapIndexed { i, d -> if (i % 5 == 0) d.date.dayOfMonth.toString() else "" } // from the 1st, as the detail
    }
    val withData = days.filter { it.known }
    val busiest = withData.maxByOrNull { it.totalMs } ?: return
    val average = withData.sumOf { it.totalMs } / withData.size
    val busiestName = when (app.period) {
        Period.WEEK -> busiest.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> dayMonth(busiest.date, locale)
    }
    val summary = stringResource(
        R.string.det_chart_summary,
        durationText(average),
        busiestName,
        durationText(busiest.totalMs),
    )
    val barTexts = rememberBarTexts(days, today, locale)
    StackedBarChart(
        stacks = days.map { listOf(it.totalMs) },
        colors = listOf(categoryColor(app.category.ordinal)),
        labels = labels,
        summary = summary,
        modifier = Modifier.testTag("app_chart"),
        onBarClick = { i -> onOpenDay(days[i].date) },
        barDescription = { i -> barTexts[i] },
        barActionLabel = stringResource(R.string.det_open_day_action),
        scrub = app.period == Period.MONTH,
        selectionKey = days.firstOrNull()?.date,
        highlight = days.indexOfFirst { it.date == today }.takeIf { it >= 0 },
        barClickable = { i -> !days[i].date.isAfter(today) },
        labelAnchor = if (app.period == Period.MONTH) 0 else null,
        scale = ChartScale.DURATION,
        average = average,
        averageLabel = stringResource(R.string.det_chart_average, durationText(average)),
        limit = limit,
        limitLabel = limit?.let { stringResource(R.string.det_limit_line, durationText(it)) },
    )
}

/** The app's 24 clock hours of a DAY. */
@Composable
private fun AppHours(hours: List<Long>) {
    val locale = locale()
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val labels = (0..23).map { if (it % 6 == 0) hourLabel(it, locale, is24) else "" }
    val busiest = hours.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }
    val summary = busiest?.let { stringResource(R.string.det_hours_summary, hourTime(it.index, locale, is24), durationText(it.value)) }
        ?: stringResource(R.string.det_hours_none)
    Box(Modifier.testTag("app_hours")) { BarChart(hours, labels, summary, highlightLast = false, labelAnchor = 0, scale = ChartScale.DURATION) }
}
