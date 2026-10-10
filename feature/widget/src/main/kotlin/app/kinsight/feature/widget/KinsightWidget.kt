package app.kinsight.feature.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.ResetStatus
import app.kinsight.core.designsystem.component.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId

/**
 * The home-screen widget (spec §9.7, stage 5b): a parent's children with today's time and status, or a child's own
 * time and who sees it. It has no periodic work of its own: the alert run (hourly and after each session), the day
 * change ([WidgetDayReceiver]) and leaving the app call [KinsightWidget.update].
 */
class KinsightWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, LIST))

    /**
     * The data is read inside the composition: a live session only recomposes on updateAll, so a state read once here
     * would stay on screen. The first frame is read before the content, so the widget never draws a placeholder.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val frames = frames(context)
        val initial = frames.first()
        provideContent {
            val frame by frames.collectAsState(initial)
            // Plain GlanceTheme: the system's dynamic colours (API 31+), light and dark, follow wallpaper changes live.
            GlanceTheme { WidgetContent(frame.state, frame.nowMs, links) }
        }
    }

    companion object {
        /** 2×1: one line (the first child, or the child's own total). */
        val COMPACT = DpSize(100.dp, 40.dp)

        /** 4×2 and larger: the list. */
        val LIST = DpSize(200.dp, 90.dp)

        private const val TAG = "KinsightWidget"

        /** How a tap opens the app; set by the app at start (a feature cannot see MainActivity). */
        @Volatile
        var links: WidgetLinks = WidgetLinks()

        /** Bumped by [update]: a live session re-reads the data and the clock (a new day, a wipe). */
        private val refresh = MutableStateFlow(0L)

        /** Redraws every placed widget; nothing to do without one. Never throws: a widget must not fail its caller. */
        suspend fun update(context: Context) {
            refresh.update { it + 1 }
            try {
                KinsightWidget().updateAll(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "widget update failed", e)
            }
        }

        /** A local wipe (reset, leave) has just ended, done or failed: the widget must drop the group now. */
        fun wipeEnded(previous: ResetStatus, current: ResetStatus): Boolean =
            previous is ResetStatus.Running && current !is ResetStatus.Running

        private fun frames(context: Context): Flow<WidgetFrame> = widgetFrames(
            refresh = refresh,
            dashboard = { AppGraph.get(context).usage.dashboard },
            noContactHours = flow { emitAll(AppGraph.get(context).prefs.prefs.map { it.noContactHours }) }
                .catch { emit(Prefs().noContactHours) },
            clock = System::currentTimeMillis,
            onError = { Log.w(TAG, "widget state failed", it) },
        )
    }
}

/** [open]: the app as the launcher opens it; [openDevice]: the detail screen of a child (hex id). */
class WidgetLinks(
    val open: (Context) -> Intent = { c -> c.packageManager.getLaunchIntentForPackage(c.packageName) ?: Intent() },
    val openDevice: (Context, String) -> Intent = { c, _ -> open(c) },
)

@Composable
internal fun WidgetContent(state: WidgetState, nowMs: Long, links: WidgetLinks) {
    val context = LocalContext.current
    val compact = LocalSize.current.height < KinsightWidget.LIST.height || LocalSize.current.width < KinsightWidget.LIST.width
    Column(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground).padding(12.dp)
            .clickable(actionStartActivity(links.open(context))),
        verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
    ) {
        when (state) {
            WidgetState.Empty -> Empty()
            is WidgetState.Child -> Child(state, compact)
            is WidgetState.Parent -> if (state.children.isEmpty()) {
                Line(context.getString(R.string.wid_no_children), "wid_no_children", bold = true)
                Line(context.getString(R.string.wid_open_app), "wid_open")
            } else if (compact) {
                ChildLine(state.children.first(), nowMs, links, compact = true)
            } else {
                Line(context.getString(R.string.wid_today), "wid_title", bold = true)
                LazyColumn(GlanceModifier.fillMaxWidth()) {
                    items(state.children, itemId = { it.id.hashCode().toLong() }) { ChildLine(it, nowMs, links, compact = false) }
                    if (state.more > 0) {
                        item {
                            Line(context.resources.getQuantityString(R.plurals.wid_more, state.more, state.more), "wid_more")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty() {
    val context = LocalContext.current
    Line(context.getString(R.string.wid_app_name), "wid_empty", bold = true)
    Line(context.getString(R.string.wid_open_app), "wid_open")
}

@Composable
private fun Child(state: WidgetState.Child, compact: Boolean) {
    val context = LocalContext.current
    val total = todayText(context, state.hasData, state.todayMs)
    val seen = if (state.parents.isEmpty()) {
        context.getString(R.string.wid_no_parents)
    } else {
        context.getString(R.string.wid_seen_by, state.parents.joinToString(", "))
    }
    Text(
        text = total,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (compact) 20.sp else 28.sp, fontWeight = FontWeight.Bold),
        modifier = GlanceModifier.semantics {
            contentDescription = context.getString(R.string.wid_own_today_spoken, total)
            testTag = "wid_self_total"
        },
    )
    if (!compact) Line(seen, "wid_parents")
}

@Composable
private fun ChildLine(row: ChildRow, nowMs: Long, links: WidgetLinks, compact: Boolean) {
    val context = LocalContext.current
    val total = todayText(context, row.hasData, row.todayMs)
    val status = statusText(context, row.status, nowMs)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = if (compact) 0.dp else 2.dp)
            .clickable(actionStartActivity(links.openDevice(context, row.id)))
            .semantics {
                contentDescription = context.getString(R.string.wid_child_spoken, row.name, total, status)
                testTag = "wid_child_${row.id}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Glance has no heightIn: a zero-width 48 dp spacer makes the row's touch target at least 48 dp (spec §9.8)
        // and lets it grow with the font scale.
        Spacer(GlanceModifier.width(0.dp).height(MIN_TOUCH))
        Column(GlanceModifier.defaultWeight()) {
            Text(row.name, style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp), maxLines = 1)
            Text(status, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
        Text(total, style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp), maxLines = 1)
    }
}

private val MIN_TOUCH = 48.dp

@Composable
private fun Line(text: String, tag: String, bold: Boolean = false) {
    Text(
        text = text,
        style = TextStyle(
            color = if (bold) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = if (bold) 16.sp else 13.sp,
        ),
        modifier = GlanceModifier.semantics { testTag = tag },
    )
}

private fun todayText(context: Context, hasData: Boolean, ms: Long): String =
    if (hasData) formatDuration(context.resources, ms) else context.getString(R.string.wid_no_data_today)

/**
 * The card's status in words (colour is never the only cue). Times are absolute: the widget is redrawn only hourly and
 * after sessions, so «5 minutes ago» would go stale on the home screen.
 */
internal fun statusText(context: Context, status: ChildStatus, nowMs: Long): String = when (status) {
    is ChildStatus.Paused -> context.getString(R.string.wid_paused, moment(context, status.sinceMs, nowMs))
    is ChildStatus.NoContact -> context.getString(R.string.wid_no_contact, moment(context, status.seenMs, nowMs))
    is ChildStatus.Updated -> context.getString(R.string.wid_updated, moment(context, status.atMs, nowMs))
    ChildStatus.NotSharing -> context.getString(R.string.wid_not_sharing)
    ChildStatus.NoData -> context.getString(R.string.wid_no_data)
}

/** «14:05» today, «10 Oct, 14:05» before. */
private fun moment(context: Context, ms: Long, nowMs: Long): String {
    val zone = ZoneId.systemDefault()
    val sameDay = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val flags = if (sameDay) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_TIME
    }
    return DateUtils.formatDateTime(context, ms, flags)
}
