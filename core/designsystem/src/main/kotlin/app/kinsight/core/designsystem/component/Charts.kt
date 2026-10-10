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
 * (0 for a clock-hour axis, so hour 0 and every 6th stay); null means the last bar.
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
) {
    val highlight = MaterialTheme.colorScheme.primary
    val normal = MaterialTheme.colorScheme.secondary
    val bars = values.map { it.coerceAtLeast(0L) }
    val names = chartLabels(labels, bars.size)
    Column(modifier.clearAndSetSemantics { contentDescription = summary }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val max = (bars.maxOrNull() ?: 0L).coerceAtLeast(1L)
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
        }
        ChartLabels(names, anchor = labelAnchor ?: names.lastIndex, bold = if (highlightLast) names.lastIndex else null)
    }
}

/**
 * Stacked vertical bars on Canvas (spec §9.8): bar i is the sum of [stacks][i], segment j drawn bottom-up in
 * [colors][j] (wrapping). A bar with nothing draws a faint baseline so the day still reads as a day. TalkBack reads
 * [summary]; with [onBarClick] every bar for which [barClickable] holds (not a future day) is also its own semantics
 * node with a click action labelled [barClickLabel]. The [highlight]ed bar keeps full colour and a bold label while
 * the others fade, so colour is never the only cue; label thinning counts from [labelAnchor], else from it, else from
 * the last bar.
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
) {
    val bars = stacks.map { stack -> stack.map { it.coerceAtLeast(0L) } }
    val names = chartLabels(labels, bars.size)
    val palette = colors.ifEmpty { listOf(MaterialTheme.colorScheme.secondary) }
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val marked = highlight?.takeIf { it in bars.indices }
    val outer = if (onBarClick == null) {
        Modifier.clearAndSetSemantics { contentDescription = summary }
    } else {
        Modifier.semantics { contentDescription = summary; isTraversalGroup = true }
    }
    Column(modifier.then(outer)) {
        Box(Modifier.fillMaxWidth().height(height)) {
            Canvas(Modifier.matchParentSize()) {
                val max = (bars.maxOfOrNull { it.sum() } ?: 0L).coerceAtLeast(1L)
                val slot = size.width / bars.size.coerceAtLeast(1)
                val bar = slot * 0.6f
                val corner = CornerRadius(4.dp.toPx())
                bars.forEachIndexed { i, stack ->
                    val left = i * slot + (slot - bar) / 2
                    val total = stack.sum()
                    if (total == 0L) {
                        val line = 2.dp.toPx()
                        drawRoundRect(baseline, Offset(left, size.height - line), Size(bar, line), CornerRadius(line / 2))
                        return@forEachIndexed
                    }
                    val alpha = if (marked == null || marked == i) 1f else 0.45f
                    val h = (size.height * total.toFloat() / max).coerceAtLeast(2.dp.toPx())
                    // Clip the whole bar to a rounded rect so only its top and bottom corners are rounded.
                    clipPath(Path().apply { addRoundRect(RoundRect(left, size.height - h, left + bar, size.height, corner)) }) {
                        var bottom = size.height
                        stack.forEachIndexed { j, v ->
                            if (v == 0L) return@forEachIndexed
                            val seg = h * v.toFloat() / total
                            drawRect(palette[j % palette.size].copy(alpha = alpha), Offset(left, bottom - seg), Size(bar, seg))
                            bottom -= seg
                        }
                    }
                }
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
