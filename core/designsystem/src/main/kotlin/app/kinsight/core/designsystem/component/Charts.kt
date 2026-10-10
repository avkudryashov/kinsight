package app.kinsight.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Vertical bars on Canvas (spec §9.8). TalkBack reads [summary] instead of the bars; the highlighted last bar (today)
 * also gets a bold label, so colour is never the only cue. Negative values draw as zero; [labels] are padded or
 * trimmed to the bars. When labels do not fit their slots (narrow screen, large font), only every n-th label is
 * shown, counted back from today so today's label always stays. [labelAnchor] is the bar thinning counts from instead
 * (0 for a clock-hour axis, so hour 0 and every 6th stay); null means the last bar. A [limit] (same unit as [values])
 * draws a dashed line labelled [limitLabel] and raises the scale to it; TalkBack reads the label after [summary].
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    summary: String,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    highlightLast: Boolean = true,
    labelAnchor: Int? = null,
    limit: Long? = null,
    limitLabel: String? = null,
) {
    val highlight = MaterialTheme.colorScheme.primary
    val normal = MaterialTheme.colorScheme.secondary
    val bars = values.map { it.coerceAtLeast(0L) }
    val names = chartLabels(labels, bars.size)
    val limitStyle = limitStyle()
    val measurer = rememberTextMeasurer()
    Column(modifier.clearAndSetSemantics { contentDescription = limitSummary(summary, limitLabel) }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val max = scaleMax(bars.maxOrNull() ?: 0L, limit)
            val slot = size.width / bars.size.coerceAtLeast(1)
            val bar = slot * 0.6f
            bars.forEachIndexed { i, v ->
                val h = (size.height * v.toFloat() / max).coerceAtLeast(if (v > 0) 2.dp.toPx() else 0f)
                drawRoundRect(
                    color = if (highlightLast && i == bars.lastIndex) highlight else normal,
                    topLeft = Offset(i * slot + (slot - bar) / 2, size.height - h),
                    size = Size(bar, h),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
            limitFraction(limit, max)?.let { drawLimit(size.height * (1 - it), limitLabel, measurer, limitStyle) }
        }
        ChartLabels(names, anchor = labelAnchor ?: names.lastIndex, bold = if (highlightLast) names.lastIndex else null)
    }
}

/**
 * Stacked vertical bars on Canvas (spec §9.8): bar i is the sum of [stacks][i], segment j drawn bottom-up in
 * [colors][j] (wrapping). A bar with nothing draws a faint baseline so the day still reads as a day. TalkBack reads
 * [summary]; with [onBarClick] every bar for which [barClickable] holds (not a future day) is also its own semantics
 * node with a click action labelled [barClickLabel]. The [highlight]ed bar gets a bold label and a thin underline,
 * and no bar is faded (colour is never the only cue); label thinning counts from [labelAnchor], else from it, else from
 * the last bar. A [limit] (same unit as the segments, e.g. a daily limit) draws a dashed line labelled [limitLabel] and
 * raises the scale to it, so the line is always on the chart; TalkBack reads the label after [summary].
 */
@Composable
fun StackedBarChart(
    stacks: List<List<Long>>,
    colors: List<Color>,
    labels: List<String>,
    summary: String,
    modifier: Modifier = Modifier,
    onBarClick: ((Int) -> Unit)? = null,
    barClickLabel: (Int) -> String = { "" },
    highlight: Int? = null,
    height: Dp = 160.dp,
    barClickable: (Int) -> Boolean = { true },
    labelAnchor: Int? = null,
    limit: Long? = null,
    limitLabel: String? = null,
) {
    val bars = stacks.map { stack -> stack.map { it.coerceAtLeast(0L) } }
    val names = chartLabels(labels, bars.size)
    val palette = colors.ifEmpty { listOf(MaterialTheme.colorScheme.secondary) }
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val markColor = MaterialTheme.colorScheme.onSurface
    val marked = highlight?.takeIf { it in bars.indices }
    val limitStyle = limitStyle()
    val measurer = rememberTextMeasurer()
    val described = limitSummary(summary, limitLabel)
    val outer = if (onBarClick == null) {
        Modifier.clearAndSetSemantics { contentDescription = described }
    } else {
        Modifier.semantics { contentDescription = described; isTraversalGroup = true }
    }
    Column(modifier.then(outer)) {
        Box(Modifier.fillMaxWidth().height(height)) {
            Canvas(Modifier.matchParentSize()) {
                val max = scaleMax(bars.maxOfOrNull { it.sum() } ?: 0L, limit)
                val slot = size.width / bars.size.coerceAtLeast(1)
                val bar = slot * 0.6f
                val corner = CornerRadius(4.dp.toPx())
                // Room under the bars for the highlight's underline.
                val mark = 3.dp.toPx()
                val floor = if (marked == null) size.height else size.height - mark - 2.dp.toPx()
                bars.forEachIndexed { i, stack ->
                    val left = i * slot + (slot - bar) / 2
                    if (barMarked(i, marked)) drawRoundRect(markColor, Offset(left, size.height - mark), Size(bar, mark), CornerRadius(mark / 2))
                    val total = stack.sum()
                    if (total == 0L) {
                        val line = 2.dp.toPx()
                        drawRoundRect(baseline, Offset(left, floor - line), Size(bar, line), CornerRadius(line / 2))
                        return@forEachIndexed
                    }
                    val alpha = barAlpha(i, marked)
                    val h = (floor * total.toFloat() / max).coerceAtLeast(2.dp.toPx())
                    // Clip the whole bar to a rounded rect so only its top and bottom corners are rounded.
                    clipPath(Path().apply { addRoundRect(RoundRect(left, floor - h, left + bar, floor, corner)) }) {
                        var bottom = floor
                        stack.forEachIndexed { j, v ->
                            if (v == 0L) return@forEachIndexed
                            val seg = h * v.toFloat() / total
                            drawRect(palette[j % palette.size].copy(alpha = alpha), Offset(left, bottom - seg), Size(bar, seg))
                            bottom -= seg
                        }
                    }
                }
                limitFraction(limit, max)?.let { drawLimit(floor * (1 - it), limitLabel, measurer, limitStyle) }
            }
            if (onBarClick != null) {
                Row(Modifier.matchParentSize()) {
                    bars.indices.forEach { i ->
                        val bar = Modifier.weight(1f).fillMaxHeight()
                        if (!barClickable(i)) {
                            Box(bar)
                            return@forEach
                        }
                        Box(
                            bar.clickable(
                                onClickLabel = barClickLabel(i),
                                role = Role.Button,
                            ) { onBarClick(i) }
                                .semantics { barClickLabel(i).takeIf { it.isNotEmpty() }?.let { contentDescription = it } },
                        )
                    }
                }
            }
        }
        ChartLabels(names, anchor = labelAnchor ?: marked ?: names.lastIndex, bold = marked)
    }
}

/**
 * One label per bar under a chart, hidden from TalkBack (the chart's summary covers them). When labels do not fit
 * their slots, only every n-th is shown, counted from [anchor] so its label always stays; n is rounded up to the
 * labels' own spacing (an axis labelled every 6th hour keeps 0, 6, 12, 18). A shown label is centred on its bar and may
 * use the width of the n slots it stands for, so it is not cut to one narrow slot; [bold] marks one label.
 */
@Composable
private fun ChartLabels(names: List<String>, anchor: Int, bold: Int?) {
    if (names.none { it.isNotEmpty() }) return
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        val slotPx = constraints.maxWidth.toFloat() / names.size
        val widest = names.maxOf { measurer.measure(it, style.copy(fontWeight = FontWeight.Bold)).size.width }
        val step = spacedStep(labelStep(widest.toFloat(), slotPx), labelSpacing(names))
        Layout(
            content = {
                names.forEachIndexed { i, label ->
                    Text(
                        if (labelShown(i, anchor, step)) label else "",
                        color = color,
                        style = style,
                        textAlign = TextAlign.Center,
                        fontWeight = if (i == bold) FontWeight.Bold else null,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { measurables, c ->
            val width = c.maxWidth
            val slot = width.toFloat() / names.size
            val room = (slot * step).toInt().coerceIn(0, width)
            val placeables = measurables.map { it.measure(Constraints(maxWidth = room)) }
            layout(width, placeables.maxOf { it.height }) {
                placeables.forEachIndexed { i, p ->
                    val x = (slot * (i + 0.5f) - p.width / 2f).roundToInt().coerceIn(0, (width - p.width).coerceAtLeast(0))
                    p.placeRelative(x, 0)
                }
            }
        }
    }
}

private class LimitStyle(val line: Color, val text: TextStyle)

@Composable
private fun limitStyle() = LimitStyle(
    MaterialTheme.colorScheme.onSurface,
    MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold),
)

/** A dashed line across the chart at [y] with [label] at its end, above it (below when there is no room above). */
private fun DrawScope.drawLimit(y: Float, label: String?, measurer: TextMeasurer, style: LimitStyle) {
    val dash = 4.dp.toPx()
    drawLine(style.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
    if (label.isNullOrEmpty()) return
    val text = measurer.measure(label, style.text, maxLines = 1)
    val gap = 2.dp.toPx()
    val top = if (y - gap - text.size.height >= 0) y - gap - text.size.height else y + gap
    drawText(text, topLeft = Offset((size.width - text.size.width).coerceAtLeast(0f), top))
}

/** The top of a chart's scale: the tallest bar or the [limit], whichever is higher, at least 1. */
internal fun scaleMax(barMax: Long, limit: Long?): Long = maxOf(barMax, limit ?: 0L).coerceAtLeast(1L)

/** The [limit]'s height as a share of [max] (at most the whole chart); null without a positive limit. */
internal fun limitFraction(limit: Long?, max: Long): Float? =
    if (limit == null || limit <= 0L) null else (limit.toFloat() / max.coerceAtLeast(1L)).coerceAtMost(1f)

/** What TalkBack reads for a chart: [summary], then the limit line's [limitLabel] when there is one. */
internal fun limitSummary(summary: String, limitLabel: String?): String =
    if (limitLabel.isNullOrEmpty()) summary else "$summary. $limitLabel"

/**
 * The colour alpha of bar [i]'s segments when bar [marked] is highlighted (null: none): always full. Fading the other
 * bars made a whole week look dimmed when the highlighted day (today) had no data yet (phones, 0.5.0); the highlight
 * is the bold label and the underline ([barMarked]) instead.
 */
@Suppress("UNUSED_PARAMETER")
internal fun barAlpha(i: Int, marked: Int?): Float = 1f

/** Whether bar [i] carries the highlight mark. */
internal fun barMarked(i: Int, marked: Int?): Boolean = marked != null && i == marked

/** Whether bar [i]'s label shows when every [step]-th label is kept, counted from [anchor] in both directions. */
internal fun labelShown(i: Int, anchor: Int, step: Int): Boolean = Math.floorMod(anchor - i, step.coerceAtLeast(1)) == 0

/** [labels] padded with blanks or trimmed to [count] bars. */
internal fun chartLabels(labels: List<String>, count: Int): List<String> = List(count) { labels.getOrElse(it) { "" } }

/** The smallest gap between two labelled bars ([names] pre-spaced by the caller, e.g. every 6th hour); 1 with fewer than two labels. */
internal fun labelSpacing(names: List<String>): Int {
    val labelled = names.indices.filter { names[it].isNotEmpty() }
    return labelled.zipWithNext { a, b -> b - a }.minOrNull()?.coerceAtLeast(1) ?: 1
}

/** [step] rounded up to a multiple of [spacing], so thinning drops whole labels rather than landing on blanks. */
internal fun spacedStep(step: Int, spacing: Int): Int {
    val sp = spacing.coerceAtLeast(1)
    return ((step.coerceAtLeast(1) + sp - 1) / sp) * sp
}

/** Show every n-th label so the widest one ([widestPx]) fits in the n slots it may use. */
internal fun labelStep(widestPx: Float, slotPx: Float): Int =
    if (slotPx <= 0f) 1 else ceil(widestPx / slotPx).toInt().coerceAtLeast(1)
