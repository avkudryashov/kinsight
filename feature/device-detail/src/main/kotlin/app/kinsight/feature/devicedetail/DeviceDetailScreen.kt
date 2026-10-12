package app.kinsight.feature.devicedetail

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.AppList
import app.kinsight.core.data.AppUsage
import app.kinsight.core.data.Categories
import app.kinsight.core.data.DayTotal
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.DeviceDetail
import app.kinsight.core.data.Heatmap
import app.kinsight.core.data.HourTotal
import app.kinsight.core.data.Period
import app.kinsight.core.data.Role
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.data.UsageReadModel
import app.kinsight.core.designsystem.component.AppIcon
import app.kinsight.core.designsystem.component.BarChart
import app.kinsight.core.designsystem.component.CategoryLegend
import app.kinsight.core.designsystem.component.ChartScale
import app.kinsight.core.designsystem.component.EmptyState
import app.kinsight.core.designsystem.component.PeriodHeader
import app.kinsight.core.designsystem.component.StackedBarChart
import app.kinsight.core.designsystem.component.dataMoment
import app.kinsight.core.designsystem.component.categoryColor
import app.kinsight.core.designsystem.component.durationText
import app.kinsight.core.model.DeviceId
import app.kinsight.core.usage.UsageSettings
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import app.kinsight.core.designsystem.component.Heatmap as HeatmapChart

private const val TOP_APPS = 5

/** Events drawn at most; the period's list is uncapped in core/data, so the screen says how many it leaves out. */
private const val MAX_EVENTS = 100

/** A horizontal drag this long (or longer) steps the period, like the arrows. */
private val SWIPE_THRESHOLD = 72.dp

/**
 * [onAllApps] opens the full list of the range on screen; [onOpenApp] (a package name and the range on screen) opens
 * one app on the same period, its rows are clickable only when it is given; [onThresholds] opens the child's limits
 * (offered on a parent's phone only).
 */
@Composable
fun DeviceDetailRoute(
    id: DeviceId,
    onAllApps: (DetailRange) -> Unit,
    onBack: () -> Unit,
    onOpenApp: ((String, DetailRange) -> Unit)? = null,
    onThresholds: () -> Unit = {},
) {
    val graph = AppGraph.get(LocalContext.current)
    val vm: DeviceDetailViewModel = viewModel(key = "detail-${id.hex}") { DeviceDetailViewModel(graph.usage, id, createSavedStateHandle()) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val settings by graph.settingsRepository.usageSettings.collectAsStateWithLifecycle(initialValue = UsageSettings())
    val thresholds by vm.thresholds.collectAsStateWithLifecycle()
    // «may be out of date» must appear on a screen left open, without new data (U3 review Q3).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Ticks only while the screen is in front; a return refreshes «now» at once.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val d = detail
    when {
        d != null -> DeviceDetailScreen(
            d, vm::select, onAllApps, onBack,
            onPrevious = vm::back, onNext = vm::forward, onOpenDay = vm::openDay,
            onOpenApp = onOpenApp?.let { open -> { pkg: String -> open(pkg, d.range) } },
            detailRetentionDays = settings.detailRetentionDays,
            thresholds = thresholds,
            onThresholds = onThresholds,
            nowMs = now,
        )
        loaded -> MissingDevice(onBack)
        else -> Loading()
    }
}

/** The full list of [range]: the light app list flow (UsageRepository.apps), not a whole detail per log change. */
@Composable
fun AllAppsRoute(id: DeviceId, range: DetailRange, onBack: () -> Unit, onOpenApp: ((String) -> Unit)? = null) {
    val graph = AppGraph.get(LocalContext.current)
    val vm: AllAppsViewModel = viewModel(key = "all-apps-${id.hex}-${range.period}-${range.start}") { AllAppsViewModel(graph.usage, id, range) }
    val list by vm.list.collectAsStateWithLifecycle()
    list?.let { AllAppsScreen(it, onBack, onOpenApp) } ?: Loading()
}

@Composable
internal fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

/** U12: the device is not (or no longer) a member here, e.g. an old notification or widget link: say so, offer Back. */
@Composable
internal fun MissingDevice(onBack: () -> Unit) = Screen(stringResource(R.string.det_missing_title), onBack) { padding ->
    EmptyState(
        stringResource(R.string.det_missing_title),
        stringResource(R.string.det_missing_body),
        Modifier.padding(padding).testTag("det_missing"),
    ) { Button(onBack, Modifier.testTag("det_missing_back")) { Text(stringResource(R.string.det_back)) } }
}

@Composable
internal fun periodLabel(p: Period) = stringResource(
    when (p) {
        Period.DAY -> R.string.det_day
        Period.WEEK -> R.string.det_week
        Period.MONTH -> R.string.det_month
    },
)

@Composable
internal fun categoryName(c: AppCategory) = stringResource(
    when (c) {
        AppCategory.GAMES -> R.string.det_cat_games
        AppCategory.SOCIAL -> R.string.det_cat_social
        AppCategory.VIDEO -> R.string.det_cat_video
        AppCategory.AUDIO -> R.string.det_cat_audio
        AppCategory.PHOTOS -> R.string.det_cat_photos
        AppCategory.NEWS -> R.string.det_cat_news
        AppCategory.MAPS -> R.string.det_cat_maps
        AppCategory.PRODUCTIVITY -> R.string.det_cat_productivity
        AppCategory.OTHER -> R.string.det_cat_other
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Screen(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) = Scaffold(
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
internal fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })

/**
 * Spec §9.3: the period switch and its header (arrows, or a horizontal swipe on the content), level 1 (total, days by
 * category: tap a bar to open that day), level 3 (legend and category list), level 2 (top apps), level 4 (hours of a
 * day, heatmap of a week or month, or why there are none) and the range's events. A day without a summary reads
 * «no data» rather than «0 min» (DayTotal.known); a parent's phone has no usage to show at all (Play Stalkerware
 * ruling), only its events. [detailRetentionDays] is how long hourly detail is kept, for the «unavailable» text.
 * [thresholds] (a parent's phone only, else null) adds the «Limits» entry with their count ([onThresholds]) and draws
 * the total's limit: a line on the WEEK and MONTH charts, a line of text under a DAY's total (its chart is clock
 * hours, which follows the total right away).
 */
@Composable
fun DeviceDetailScreen(
    detail: DeviceDetail,
    onPeriod: (Period) -> Unit,
    onAllApps: (DetailRange) -> Unit,
    onBack: () -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onOpenDay: (LocalDate) -> Unit = {},
    onOpenApp: ((String) -> Unit)? = null,
    detailRetentionDays: Int = UsageSettings().detailRetentionDays,
    thresholds: List<ThresholdRule>? = null,
    onThresholds: () -> Unit = {},
    nowMs: Long = System.currentTimeMillis(),
) = Screen(detail.member.name, onBack) { padding ->
    val child = detail.member.role == Role.CHILD
    val totalLimit = thresholds?.firstOrNull { it.target == ThresholdTarget.Total }?.limitMs
    val today = detail.today ?: LocalDate.now()
    val known = detail.days.any { it.known }
    val swipe = Modifier.swipeSteps(detail.canGoBack, detail.canGoForward, onPrevious, onNext)
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).then(swipe).testTag("det_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (child) {
            item(key = "periods") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Period.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = p == detail.period,
                            onClick = { onPeriod(p) },
                            shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                            modifier = Modifier.testTag("det_period_${p.name.lowercase()}"),
                        ) { Text(periodLabel(p)) }
                    }
                }
            }
            item(key = "header") {
                PeriodHeader(
                    title = rangeTitle(detail.range, today),
                    canBack = detail.canGoBack,
                    canForward = detail.canGoForward,
                    onBack = onPrevious,
                    onForward = onNext,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (detail.sharing != Sharing.On) item(key = "sharing") { SharingNote(detail.sharing) }
            if (known) {
                item(key = "total") {
                    Column {
                        Text(durationText(detail.totalMs), style = MaterialTheme.typography.displaySmall)
                        // Only a range that holds the child's today: «Data as of 14:05», with the child's clock when it differs (U3).
                        val updated = detail.updatedMs
                        if (updated != null && !today.isBefore(detail.range.start) && !today.isAfter(detail.range.end)) {
                            val stale = UsageReadModel.isStale(updated, nowMs)
                            Text(
                                stringResource(if (stale) R.string.det_data_stale else R.string.det_data_at, dataMoment(LocalContext.current, updated, nowMs, detail.childZoneId)),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (stale) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("det_data_at"),
                            )
                        }
                        Text(pluralStringResource(R.plurals.det_unlocks, detail.unlocks, detail.unlocks))
                        if (detail.period == Period.DAY && totalLimit != null) {
                            Text(stringResource(R.string.det_limit_line, durationText(totalLimit)), Modifier.testTag("det_limit"))
                        }
                    }
                }
                if (detail.period == Period.DAY) {
                    timeOfDay(detail, today, detailRetentionDays) // U17: a day's chart is its hours, right under the total
                } else {
                    item(key = "chart") { DaysChart(detail, today, onOpenDay, limit = totalLimit) } // U19: the month too
                }
            } else if (detail.period == Period.DAY) {
                item(key = "no_data") { Text(stringResource(R.string.det_no_data_day), style = MaterialTheme.typography.titleLarge) }
            } else {
                item(key = "no_data") { EmptyState(stringResource(R.string.det_no_data_title), stringResource(R.string.det_no_data_body)) }
            }
            if (thresholds != null && detail.sharing != Sharing.Left) {
                item(key = "thresholds") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.det_thresholds)) },
                        supportingContent = {
                            Text(
                                if (thresholds.isEmpty()) {
                                    stringResource(R.string.det_thresholds_none)
                                } else {
                                    pluralStringResource(R.plurals.det_thresholds_count, thresholds.size, thresholds.size)
                                },
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = onThresholds).testTag("det_thresholds"),
                    )
                }
            }
            if (detail.categories.isNotEmpty()) {
                item(key = "categories_title") { SectionTitle(stringResource(R.string.det_categories)) }
                item(key = "legend") { CategoryLegend(detail.categories.map { categoryName(it.category) to categoryColor(it.category.ordinal) }) }
                items(detail.categories, key = { "cat:${it.category}" }) { c ->
                    val share = if (detail.totalMs > 0) (c.totalMs * 100.0 / detail.totalMs).roundToInt() else 0
                    ListItem(
                        headlineContent = { Text(categoryName(c.category)) },
                        trailingContent = { Text(stringResource(R.string.det_category_share, durationText(c.totalMs), share)) },
                        modifier = Modifier.testTag("det_category_${c.category.name}"),
                    )
                }
            }
            item(key = "apps_title") { SectionTitle(stringResource(R.string.det_top_apps)) }
            if (detail.apps.isEmpty()) item(key = "no_apps") { Text(stringResource(R.string.det_no_apps)) }
            items(detail.apps.take(TOP_APPS), key = { "app:${it.packageName}" }) { AppRow(it, onOpenApp) }
            if (detail.apps.size > TOP_APPS) {
                item(key = "all_apps") {
                    TextButton({ onAllApps(detail.range) }, Modifier.testTag("det_all_apps")) {
                        Text(stringResource(R.string.det_all_apps, detail.apps.size))
                    }
                }
            }
            if (known && detail.period != Period.DAY) timeOfDay(detail, today, detailRetentionDays)
        } else {
            item(key = "not_shared") { Text(stringResource(R.string.det_parent_not_shared)) }
        }
        item(key = "events_title") { SectionTitle(stringResource(R.string.det_events)) }
        if (detail.events.isEmpty()) item(key = "no_events") { Text(stringResource(R.string.det_no_events)) }
        items(detail.events.take(MAX_EVENTS), key = { "ev:${it.author.hex}-${it.seq}" }) { e ->
            ListItem(
                headlineContent = { Text(eventText(e)) },
                supportingContent = { Text(dateTime(e.atMs)) }, // the alert's time too
                modifier = Modifier.testTag("det_event_${e.seq}"),
            )
        }
        if (detail.events.size > MAX_EVENTS) {
            item(key = "events_more") { Text(stringResource(R.string.det_events_capped, MAX_EVENTS, detail.events.size)) }
        }
    }
}

/** Level 4: the hours of a DAY, the heatmap of a WEEK or MONTH, or why there are none (pruned, or not arrived yet). */
private fun LazyListScope.timeOfDay(detail: DeviceDetail, today: LocalDate, detailRetentionDays: Int) {
    item(key = "hours_title") { SectionTitle(stringResource(R.string.det_time_of_day)) }
    val hours = detail.hours
    val heatmap = detail.heatmap
    // Without detail: past the viewer's detail retention it was pruned, otherwise it has not arrived yet.
    val horizon = today.minusDays(detailRetentionDays.toLong())
    val pruned = detail.days.any { it.known && it.date.isBefore(horizon) }
    when {
        detail.period == Period.DAY && hours != null -> item(key = "hours") { HoursCharts(hours) }
        detail.period != Period.DAY && heatmap != null -> item(key = "heatmap") { HeatmapSection(heatmap) }
        pruned -> item(key = "hours_unavailable") {
            Text(pluralStringResource(R.plurals.det_hours_unavailable, detailRetentionDays, detailRetentionDays))
        }
        else -> item(key = "hours_not_arrived") { Text(stringResource(R.string.det_hours_not_arrived)) }
    }
}

/**
 * A horizontal drag of at least [SWIPE_THRESHOLD] steps back (towards the start edge: a right swipe in LTR) or forward,
 * within the same bounds as the arrows. Vertical scrolling of the list is not affected.
 */
@Composable
private fun Modifier.swipeSteps(canBack: Boolean, canForward: Boolean, onBack: () -> Unit, onForward: () -> Unit): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val back by rememberUpdatedState(if (canBack) onBack else null)
    val forward by rememberUpdatedState(if (canForward) onForward else null)
    return pointerInput(rtl) {
        val threshold = SWIPE_THRESHOLD.toPx()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                val towardsStart = if (rtl) total < 0 else total > 0
                if (abs(total) >= threshold) (if (towardsStart) back else forward)?.invoke()
            },
        ) { change, dx ->
            change.consume()
            total += dx
        }
    }
}

/** [onOpenApp] (a package name) makes the rows open one app, as on the detail screen. */
@Composable
fun AllAppsScreen(list: AppList, onBack: () -> Unit, onOpenApp: ((String) -> Unit)? = null) =
    Screen(stringResource(R.string.det_all_apps_title, list.member.name, rangeTitle(list.range, list.today ?: LocalDate.now())), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("det_apps"), contentPadding = PaddingValues(16.dp)) {
            if (list.apps.isEmpty()) item { Text(stringResource(R.string.det_no_apps)) }
            items(list.apps, key = { it.packageName }) { AppRow(it, onOpenApp) }
        }
    }

@Composable
private fun dateTime(ms: Long): String =
    DateUtils.formatDateTime(LocalContext.current, ms, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

/** An app's name; the summary's `~other` remainder bucket reads «Other apps». */
@Composable
internal fun appLabel(packageName: String, label: String): String =
    if (packageName == Categories.REMAINDER_PACKAGE) stringResource(R.string.det_other_apps) else label

@Composable
private fun AppRow(app: AppUsage, onOpen: ((String) -> Unit)?) {
    val openLabel = stringResource(R.string.det_open_app)
    ListItem(
        leadingContent = { AppIcon(app.icon) },
        headlineContent = { Text(appLabel(app.packageName, app.label)) },
        trailingContent = { Text(durationText(app.totalMs)) },
        modifier = Modifier.testTag("det_app_${app.packageName}")
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = openLabel) { onOpen(app.packageName) } else Modifier),
    )
}

@Composable
internal fun locale(): Locale = LocalConfiguration.current.locales[0]

/**
 * Levels 1 and 3: one stacked bar per day by category (colour order = AppCategory order), today highlighted; a tap
 * opens that day. Average and busiest day count only days with a summary, as on the dashboard card.
 */
@Composable
private fun DaysChart(detail: DeviceDetail, today: LocalDate, onOpenDay: (LocalDate) -> Unit, limit: Long? = null) {
    val locale = locale()
    val days = detail.days
    // A month is labelled every 5th day from the 1st, thinned from the 1st as well.
    val labels = when (detail.period) {
        Period.WEEK -> days.map { it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) }
        else -> days.mapIndexed { i, d -> if (i % 5 == 0) d.date.dayOfMonth.toString() else "" }
    }
    val withData = days.filter { it.known }
    val busiest = withData.maxByOrNull { it.totalMs } ?: return
    val average = withData.sumOf { it.totalMs } / withData.size
    val busiestName = when (detail.period) {
        Period.WEEK -> busiest.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> dayMonth(busiest.date, locale)
    }
    val daysSummary = stringResource(
        R.string.det_chart_summary,
        durationText(average),
        busiestName,
        durationText(busiest.totalMs),
    )
    val top = detail.categories.take(2).map { stringResource(R.string.det_category_item, categoryName(it.category), durationText(it.totalMs)) }
    val summary = if (top.isEmpty()) daysSummary else "$daysSummary. " + stringResource(R.string.det_categories_summary, top.joinToString(", "))
    val stacks = days.mapIndexed { i, d -> stackOf(detail.dayCategories.getOrNull(i), d) }
    val barTexts = rememberBarTexts(days, today, locale)
    StackedBarChart(
        stacks = stacks,
        colors = AppCategory.entries.map { categoryColor(it.ordinal) },
        labels = labels,
        summary = summary,
        modifier = Modifier.testTag("det_chart"),
        onBarClick = { i -> onOpenDay(days[i].date) },
        barDescription = { i -> barTexts[i] },
        barActionLabel = stringResource(R.string.det_open_day_action),
        scrub = detail.period == Period.MONTH,
        selectionKey = days.firstOrNull()?.date,
        highlight = days.indexOfFirst { it.date == today }.takeIf { it >= 0 },
        barClickable = { i -> !days[i].date.isAfter(today) },
        labelAnchor = if (detail.period == Period.MONTH) 0 else null,
        limit = limit,
        limitLabel = limit?.let { stringResource(R.string.det_limit_line, durationText(it)) },
        scale = ChartScale.DURATION,
        average = average,
        averageLabel = stringResource(R.string.det_chart_average, durationText(average)),
    )
}

/** One bar's segments in AppCategory order; a known day without a breakdown is all Other. */
private fun stackOf(cats: Map<AppCategory, Long>?, day: DayTotal): List<Long> {
    val byCat = cats?.takeIf { it.isNotEmpty() } ?: if (day.known && day.totalMs > 0) mapOf(AppCategory.OTHER to day.totalMs) else emptyMap()
    return AppCategory.entries.map { byCat[it] ?: 0L }
}

/** Level 4 of a day: 24 clock hours by category, then unlocks per hour. */
@Composable
private fun HoursCharts(hours: List<HourTotal>) {
    val locale = locale()
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val names = (0..23).map { hourLabel(it, locale, is24) }
    val labels = names.mapIndexed { h, n -> if (h % 6 == 0) n else "" }
    val busiest = hours.maxByOrNull { it.totalMs }?.takeIf { it.totalMs > 0 }
    val summary = busiest?.let { stringResource(R.string.det_hours_summary, hourTime(it.hour, locale, is24), durationText(it.totalMs)) }
        ?: stringResource(R.string.det_hours_none)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StackedBarChart(
            stacks = hours.map { h -> AppCategory.entries.map { h.byCategory[it] ?: 0L } },
            colors = AppCategory.entries.map { categoryColor(it.ordinal) },
            labels = labels,
            summary = summary,
            modifier = Modifier.testTag("det_hours"),
            labelAnchor = 0,
            scale = ChartScale.DURATION,
        )
        Text(stringResource(R.string.det_unlocks_by_hour), style = MaterialTheme.typography.labelLarge)
        val mostUnlocks = hours.maxByOrNull { it.unlocks }?.takeIf { it.unlocks > 0 }
        val unlockSummary = mostUnlocks?.let { stringResource(R.string.det_unlocks_summary, hourTime(it.hour, locale, is24), it.unlocks) }
            ?: stringResource(R.string.det_unlocks_none)
        Box(Modifier.testTag("det_hour_unlocks")) { BarChart(hours.map { it.unlocks.toLong() }, labels, unlockSummary, highlightLast = false, labelAnchor = 0, scale = ChartScale.COUNT) }
    }
}

/** Level 4 of a week or month: weekday × hour, rows from the data's first day of week (the range's WeekFields). */
@Composable
private fun HeatmapSection(heatmap: Heatmap) {
    val locale = locale()
    val first = heatmap.firstDayOfWeek
    val weekdays = (0L..6).map { first.plus(it) }
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    // The busiest cell; an all-empty grid says so rather than naming the first cell.
    var best: Pair<Int, Int>? = null
    var bestMs = 0L
    heatmap.cells.take(7).forEachIndexed { d, row ->
        row.take(24).forEachIndexed { h, v ->
            if (v > bestMs) {
                bestMs = v
                best = d to h
            }
        }
    }
    val summary = best?.let { (d, h) ->
        stringResource(R.string.det_heatmap_summary, weekdays[d].getDisplayName(TextStyle.FULL, locale), hourTime(h, locale, is24))
    } ?: stringResource(R.string.det_hours_none)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HeatmapChart(
            cells = heatmap.cells,
            rowLabels = weekdays.map { it.getDisplayName(TextStyle.SHORT, locale) },
            summary = summary,
            modifier = Modifier.fillMaxWidth().testTag("det_heatmap"),
            hourLabels = hourLabels(locale, is24),
        )
        Text(
            pluralStringResource(R.plurals.det_heatmap_coverage, heatmap.rangeDays, heatmap.coveredDays, heatmap.rangeDays),
            style = MaterialTheme.typography.bodySmall,
        )
        // As on a DAY: unlocks per clock hour, here the mean over the covered days.
        val names = (0..23).map { hourLabel(it, locale, is24) }
        val labels = names.mapIndexed { h, n -> if (h % 6 == 0) n else "" }
        val unlocks = (0..23).map { heatmap.unlocks.getOrElse(it) { 0 }.coerceAtLeast(0) }
        val most = unlocks.indices.maxByOrNull { unlocks[it] }?.takeIf { unlocks[it] > 0 }
        val unlockSummary = most?.let { stringResource(R.string.det_unlocks_summary, hourTime(it, locale, is24), unlocks[it]) }
            ?: stringResource(R.string.det_unlocks_none)
        Text(stringResource(R.string.det_unlocks_by_hour_average), style = MaterialTheme.typography.labelLarge)
        Box(Modifier.testTag("det_heatmap_unlocks")) {
            BarChart(unlocks.map { it.toLong() }, labels, unlockSummary, highlightLast = false, labelAnchor = 0, scale = ChartScale.COUNT)
        }
    }
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
