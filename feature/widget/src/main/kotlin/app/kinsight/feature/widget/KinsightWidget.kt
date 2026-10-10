package app.kinsight.feature.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.kinsight.core.data.AppGraph
import app.kinsight.core.designsystem.component.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId

/**
 * The home-screen widget (spec §9.7, stage 5b): a parent's children with today's time and status, or a child's own
 * time and who sees it. It has no periodic work of its own: the alert run (hourly and after each session), the day
 * change ([WidgetDayReceiver]) and leaving the app call [KinsightWidget.update].
 */
class KinsightWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, LIST))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = load(context)
        val nowMs = System.currentTimeMillis()
        provideContent {
            GlanceTheme(colors = ColorProviders(light = dynamicLightColorScheme(context), dark = dynamicDarkColorScheme(context))) {
                WidgetContent(state, nowMs, links)
            }
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

        /** Redraws every placed widget; nothing to do without one. Never throws: a widget must not fail its caller. */
        suspend fun update(context: Context) {
            try {
                KinsightWidget().updateAll(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "widget update failed", e)
            }
        }

        /** No group, a reset, a removed device or any read failure is the empty state, never stale children. */
        internal suspend fun load(context: Context): WidgetState = try {
            val g = AppGraph.get(context)
            WidgetStates.of(g.usage.dashboard.first(), g.prefs.current().noContactHours, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "widget state failed", e)
            WidgetState.Empty
        }
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
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = if (compact) 0.dp else 4.dp)
            .clickable(actionStartActivity(links.openDevice(context, row.id)))
            .semantics {
                contentDescription = context.getString(R.string.wid_child_spoken, row.name, total, status)
                testTag = "wid_child_${row.id}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(GlanceModifier.defaultWeight()) {
            Text(row.name, style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp), maxLines = 1)
            Text(status, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
        Text(total, style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp), maxLines = 1)
    }
}

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
