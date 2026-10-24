package app.kinsight.feature.devicedetail

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import app.kinsight.core.data.CategoryTotal
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.DeviceDetail
import app.kinsight.core.data.HourTotal
import app.kinsight.core.data.Period
import app.kinsight.core.data.Role
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.ThresholdEval
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.data.TodayComparison
import app.kinsight.core.data.UsageReadModel
import app.kinsight.core.designsystem.component.AppIcon
import app.kinsight.core.designsystem.component.CategoryStrip
import app.kinsight.core.designsystem.component.ChartScale
import app.kinsight.core.designsystem.component.EmptyState
import app.kinsight.core.designsystem.component.PeriodHeader
import app.kinsight.core.designsystem.component.Pill
import app.kinsight.core.designsystem.component.StackedBarChart
import app.kinsight.core.designsystem.component.StripPart
import app.kinsight.core.designsystem.component.categoryColor
import app.kinsight.core.designsystem.component.dataMoment
import app.kinsight.core.designsystem.component.durationText
import app.kinsight.core.model.DeviceId
import app.kinsight.core.usage.UsageSettings
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Events drawn at most; the period's list is uncapped in core/data, so the screen says how many it leaves out. */
private const val MAX_EVENTS = 100

/** A horizontal drag this long (or longer) steps the period, like the arrows. */
private val SWIPE_THRESHOLD = 72.dp

/**
 * [onAllApps] opens the full list of the range on screen; [onOpenApp] (a package name and the range on screen) opens
 * one app on the same period, its rows are clickable only when it is given.
 */
@Composable
fun DeviceDetailRoute(
    id: DeviceId,
    onAllApps: (DetailRange) -> Unit,
    onBack: () -> Unit,
    onOpenApp: ((String, DetailRange) -> Unit)? = null,
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
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })

/**
 * UI refresh §4: «Сегодня · Неделя · Месяц», arrows and swipe, then on every tab: 1) the figure, unlocks and the comparison,
 * 2) the chart (hours of a day, days of a week or month) in one colour, 3) one category strip, 4) the top three apps with
 * «лимит» marks and «Все приложения»; then the range's events. [thresholds] (a parent's phone only) draws the day limit
 * and the marks.
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
                item(key = "total") { PeriodFigure(detail, today, nowMs, totalLimit) } // block 1
                if (detail.period == Period.DAY) dayHours(detail, today, detailRetentionDays) // block 2
                else item(key = "chart") { DaysChart(detail, today, onOpenDay, limit = totalLimit) }
                if (detail.categories.isNotEmpty()) item(key = "strip") { CategoryBlock(detail.categories) } // block 3
            } else if (detail.period == Period.DAY) {
                item(key = "no_data") { Text(stringResource(R.string.det_no_data_day), style = MaterialTheme.typography.titleLarge) }
            } else {
                item(key = "no_data") { EmptyState(stringResource(R.string.det_no_data_title), stringResource(R.string.det_no_data_body)) }
            }
            // Block 4: the top three apps and the way to every app (with the categories).
            item(key = "apps_title") { SectionTitle(stringResource(R.string.det_top_apps)) }
            if (detail.apps.isEmpty()) item(key = "no_apps") { Text(stringResource(R.string.det_no_apps)) }
            items(detail.apps.take(UsageReadModel.TOP_APPS), key = { "app:${it.packageName}" }) { app ->
                val category = detail.appCategories[app.packageName] ?: AppCategory.OTHER
                AppRow(app, onOpenApp, limited = thresholds?.let { ThresholdEval.limited(it, detail.member.id, app.packageName, category) } == true)
            }
            if (detail.apps.isNotEmpty()) {
                item(key = "all_apps") {
                    TextButton({ onAllApps(detail.range) }, Modifier.testTag("det_all_apps")) {
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
                supportingContent = { Text(dateTime(e.atMs)) }, // the alert's time too
                modifier = Modifier.testTag("det_event_${e.seq}"),
            )
        }
        if (detail.events.size > MAX_EVENTS) {
            item(key = "events_more") { Text(stringResource(R.string.det_events_capped, MAX_EVENTS, detail.events.size)) }
        }
    }
}

/** Block 2 of a DAY: its 24 clock hours in one colour, or why there are none (pruned, or not arrived yet). */
private fun LazyListScope.dayHours(detail: DeviceDetail, today: LocalDate, detailRetentionDays: Int) {
    val hours = detail.hours
    // Without detail: past the viewer's detail retention it was pruned, otherwise it has not arrived yet.
    val horizon = today.minusDays(detailRetentionDays.toLong())
    val pruned = detail.days.any { it.known && it.date.isBefore(horizon) }
    when {
        hours != null -> item(key = "hours") { HoursChart(hours) }
        pruned -> item(key = "hours_unavailable") {
            Text(pluralStringResource(R.plurals.det_hours_unavailable, detailRetentionDays, detailRetentionDays))
        }
        else -> item(key = "hours_not_arrived") { Text(stringResource(R.string.det_hours_not_arrived)) }
    }
}

/** Block 1 (UI refresh §4): the period's time, its unlocks, the comparison; a DAY's total limit under them. */
@Composable
private fun PeriodFigure(detail: DeviceDetail, today: LocalDate, nowMs: Long, totalLimit: Long?) {
    Column(Modifier.testTag("det_total"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        periodVersus(detail)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("det_versus"))
        }
        if (detail.period == Period.DAY && totalLimit != null) {
            Text(stringResource(R.string.det_limit_line, durationText(totalLimit)), Modifier.testTag("det_limit"))
        }
    }
}

/** A DAY against its usual (TodayComparison), a WEEK or MONTH as its mean per day with data. */
@Composable
private fun periodVersus(detail: DeviceDetail): String? {
    if (detail.period != Period.DAY) {
        val known = detail.days.filter { it.known }
        if (known.isEmpty()) return null
        return stringResource(R.string.det_avg_per_day, durationText(known.sumOf { it.totalMs } / known.size))
    }
    return when (val c = detail.dayComparison) {
        is TodayComparison.ByThisTime -> versus(detail.totalMs, c.usualMs, R.string.det_vs_hour_same, R.string.det_vs_hour_more, R.string.det_vs_hour_less)
        is TodayComparison.FullDay -> versus(detail.totalMs, c.averageMs, R.string.det_vs_same, R.string.det_vs_more, R.string.det_vs_less)
        null -> null
    }
}

/** Text, never colour alone: within [UsageReadModel.aboutTheSame] it reads «about the same». */
@Composable
private fun versus(totalMs: Long, usualMs: Long, same: Int, more: Int, less: Int): String {
    val diff = totalMs - usualMs
    return when {
        UsageReadModel.aboutTheSame(totalMs, usualMs) -> stringResource(same)
        diff > 0 -> stringResource(more, durationText(diff))
        else -> stringResource(less, durationText(-diff))
    }
}

/** Block 3: one strip, up to four categories and Other, names and times under it. */
@Composable
private fun CategoryBlock(categories: List<CategoryTotal>) = CategoryStrip(
    stripParts(categories).map { StripPart(categoryName(it.category), durationText(it.totalMs), it.totalMs, categoryColor(it.category.ordinal)) },
    Modifier.fillMaxWidth().testTag("det_strip"),
)

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
            if (list.categories.isNotEmpty()) {
                item(key = "categories_title") { SectionTitle(stringResource(R.string.det_categories)) }
                val total = list.categories.sumOf { it.totalMs }
                items(list.categories, key = { "cat:${it.category}" }) { c ->
                    val share = if (total > 0) (c.totalMs * 100.0 / total).roundToInt() else 0
                    ListItem(
                        leadingContent = { Box(Modifier.size(12.dp).background(categoryColor(c.category.ordinal), CircleShape)) },
                        headlineContent = { Text(categoryName(c.category)) },
                        trailingContent = { Text(stringResource(R.string.det_category_share, durationText(c.totalMs), share)) },
                        modifier = Modifier.testTag("det_category_${c.category.name}"),
                    )
                }
            }
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
private fun AppRow(app: AppUsage, onOpen: ((String) -> Unit)?, limited: Boolean = false) {
    val openLabel = stringResource(R.string.det_open_app)
    ListItem(
        leadingContent = { AppIcon(app.icon) },
        headlineContent = { Text(appLabel(app.packageName, app.label)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (limited) Pill(stringResource(R.string.det_limit_pill), Modifier.testTag("det_limit_pill"))
                Text(durationText(app.totalMs))
            }
        },
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
    val stacks = days.map { listOf(if (it.known) it.totalMs else 0L) }
    val barTexts = rememberBarTexts(days, today, locale)
    StackedBarChart(
        stacks = stacks,
        colors = listOf(MaterialTheme.colorScheme.primary),
        labels = labels,
        summary = daysSummary,
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

/** Block 2 of a day: 24 clock hours, one colour (no unlock chart: block 1 says the day's unlocks). */
@Composable
private fun HoursChart(hours: List<HourTotal>) {
    val locale = locale()
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val names = (0..23).map { hourLabel(it, locale, is24) }
    val labels = names.mapIndexed { h, n -> if (h % 6 == 0) n else "" }
    val busiest = hours.maxByOrNull { it.totalMs }?.takeIf { it.totalMs > 0 }
    val summary = busiest?.let { stringResource(R.string.det_hours_summary, hourTime(it.hour, locale, is24), durationText(it.totalMs)) }
        ?: stringResource(R.string.det_hours_none)
    StackedBarChart(
        stacks = hours.map { listOf(it.totalMs) },
        colors = listOf(MaterialTheme.colorScheme.primary),
        labels = labels,
        summary = summary,
        modifier = Modifier.testTag("det_hours"),
        labelAnchor = 0,
        scale = ChartScale.DURATION,
    )
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
