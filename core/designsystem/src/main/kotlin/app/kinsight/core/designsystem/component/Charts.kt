package app.kinsight.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
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
 * draws a dashed line with [limitLabel] on a chip at the left and raises the scale to it; TalkBack reads the label
 * after [summary]. A [scale] draws 2 or 3 labelled grid lines in a gutter right of the bars; an [average] a dashed
 * line, explained by [averageLabel] under the chart (U5).
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
    scale: ChartScale? = null,
    average: Long? = null,
    averageLabel: String? = null,
) {
    val highlight = MaterialTheme.colorScheme.primary
    val normal = MaterialTheme.colorScheme.secondary
    val bars = values.map { it.coerceAtLeast(0L) }
    val names = chartLabels(labels, bars.size)
    val max = scaleMax(bars.maxOrNull() ?: 0L, limit)
    val grid = rememberGrid(max, scale, height)
    val limitText = rememberLimitText(limitLabel)
    Column(
        modifier.clearAndSetSemantics {
            contentDescription = limitSummary(summary, limitLabel)
            chartLines(limit, average, max)
        },
    ) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val layout = barLayout(size.width, grid.gutterPx, bars.size)
            drawGrid(grid, max, size.height, layout.barsWidth)
            bars.forEachIndexed { i, v ->
                val h = (size.height * v.toFloat() / max).coerceAtLeast(if (v > 0) 2.dp.toPx() else 0f)
                drawRoundRect(
                    color = if (highlightLast && i == bars.lastIndex) highlight else normal,
                    topLeft = Offset(layout.left(i), size.height - h),
                    size = Size(layout.bar, h),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
            limitFraction(average, max)?.let { drawAverage(size.height * (1 - it), layout.barsWidth, grid.averageColor) }
            val today = layout.slotRange(bars.lastIndex.takeIf { highlightLast && it >= 0 })
            limitFraction(limit, max)?.let { drawLimit(size.height * (1 - it), layout.barsWidth, limitText, today) }
        }
        ChartLabels(names, anchor = labelAnchor ?: names.lastIndex, bold = if (highlightLast) names.lastIndex else null, endInset = grid.gutterDp)
        if (average != null && averageLabel != null) AverageNote(averageLabel)
    }
}

/**
 * Stacked vertical bars on Canvas (spec §9.8): bar i is the sum of [stacks][i], segment j drawn bottom-up in
 * [colors][j] (wrapping). A bar with nothing draws a faint baseline so the day still reads as a day. TalkBack reads
 * [summary]; with [onBarClick] every bar for which [barClickable] holds (not a future day) is a semantics node
 * described by [barDescription] (its day and value) with the click action [barActionLabel]. With [scrub] (a month's
 * narrow bars, U9) a tap or a horizontal drag selects the nearest bar: it gets a primary outline, its node is selected,
 * and a 48 dp tooltip with its description and a caret pointing at it opens it. The scrub owns horizontal drags over
 * the chart, so a screen's swipe paging works only outside it (its arrows always work). A new [selectionKey] (e.g. the
 * range's first day) clears the selection. The [highlight]ed bar gets a bold label and a thin underline, and no bar is
 * faded (colour is never the only cue); label thinning counts from [labelAnchor], else from it, else from the last bar. A [limit] (same unit as the segments, e.g. a daily limit) draws a dashed line with [limitLabel] on a
 * chip at the left and raises the scale to it, so the line is always on the chart; TalkBack reads the label after
 * [summary]. [scale], [average] and [averageLabel] work as in [BarChart].
 */
@Composable
fun StackedBarChart(
    stacks: List<List<Long>>,
    colors: List<Color>,
    labels: List<String>,
    summary: String,
    modifier: Modifier = Modifier,
    onBarClick: ((Int) -> Unit)? = null,
    barDescription: (Int) -> String = { "" },
    barActionLabel: String? = null,
    highlight: Int? = null,
    height: Dp = 160.dp,
    barClickable: (Int) -> Boolean = { true },
    labelAnchor: Int? = null,
    limit: Long? = null,
    limitLabel: String? = null,
    scale: ChartScale? = null,
    average: Long? = null,
    averageLabel: String? = null,
    scrub: Boolean = false,
    selectionKey: Any? = null,
) {
    val bars = stacks.map { stack -> stack.map { it.coerceAtLeast(0L) } }
    val names = chartLabels(labels, bars.size)
    val palette = colors.ifEmpty { listOf(MaterialTheme.colorScheme.secondary) }
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val markColor = MaterialTheme.colorScheme.onSurface
    val marked = highlight?.takeIf { it in bars.indices }
    val max = scaleMax(bars.maxOfOrNull { it.sum() } ?: 0L, limit)
    val grid = rememberGrid(max, scale, height)
    val limitText = rememberLimitText(limitLabel)
    val described = limitSummary(summary, limitLabel)
    var selected by remember(bars.size, selectionKey) { mutableStateOf<Int?>(null) }
    val shown = selected?.takeIf { scrub && onBarClick != null && it in bars.indices && barClickable(it) }
    val selectColor = MaterialTheme.colorScheme.primary
    val outer = if (onBarClick == null) {
        Modifier.clearAndSetSemantics { contentDescription = described; chartLines(limit, average, max) }
    } else {
        Modifier.semantics { contentDescription = described; isTraversalGroup = true; chartLines(limit, average, max) }
    }
    Column(modifier.then(outer)) {
        Box(Modifier.fillMaxWidth().height(height)) {
            Canvas(Modifier.matchParentSize()) {
                val layout = barLayout(size.width, grid.gutterPx, bars.size)
                val bar = layout.bar
                val corner = CornerRadius(4.dp.toPx())
                // Room under the bars for the highlight's underline.
                val mark = 3.dp.toPx()
                val floor = if (marked == null) size.height else size.height - mark - 2.dp.toPx()
                drawGrid(grid, max, floor, layout.barsWidth)
                bars.forEachIndexed { i, stack ->
                    val left = layout.left(i)
                    if (barMarked(i, marked)) drawRoundRect(markColor, Offset(left, size.height - mark), Size(bar, mark), CornerRadius(mark / 2))
                    val total = stack.sum()
                    if (i == shown) drawSelection(left, bar, floor, if (total == 0L) 0f else floor * total.toFloat() / max, selectColor)
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
                limitFraction(average, max)?.let { drawAverage(floor * (1 - it), layout.barsWidth, grid.averageColor) }
                limitFraction(limit, max)?.let { drawLimit(floor * (1 - it), layout.barsWidth, limitText, layout.slotRange(marked)) }
            }
            if (onBarClick != null) {
                val clickable by rememberUpdatedState(barClickable)
                // One node per bar for TalkBack and switch access; a pointer target only where bars are wide enough.
                // The same slots as the bars: the scale gutter on the right is no target.
                Row(Modifier.matchParentSize().padding(end = grid.gutterDp)) {
                    bars.indices.forEach { i ->
                        val slot = Modifier.weight(1f).fillMaxHeight()
                        when {
                            !barClickable(i) -> Box(slot)
                            scrub -> Box(
                                slot.semantics {
                                    contentDescription = barDescription(i)
                                    role = Role.Button
                                    if (i == shown) this.selected = true
                                    onClick(label = barActionLabel) {
                                        onBarClick(i)
                                        true
                                    }
                                },
                            )
                            else -> Box(
                                slot.clickable(onClickLabel = barActionLabel, role = Role.Button) { onBarClick(i) }
                                    .semantics { contentDescription = barDescription(i) },
                            )
                        }
                    }
                }
                if (scrub) {
                    fun pick(x: Float, width: Int) = nearestBar(x, width.toFloat(), bars.size)?.takeIf { clickable(it) }
                    Box(
                        Modifier.matchParentSize().padding(end = grid.gutterDp)
                            .pointerInput(bars.size) { detectTapGestures { o -> selected = pick(o.x, size.width) } }
                            .pointerInput(bars.size) {
                                detectHorizontalDragGestures(onDragStart = { o -> selected = pick(o.x, size.width) }) { change, _ ->
                                    change.consume()
                                    selected = pick(change.position.x, size.width)
                                }
                            },
                    )
                    shown?.let { i ->
                        BarTooltip(i, bars.size, barDescription(i), barActionLabel, Modifier.matchParentSize().padding(end = grid.gutterDp)) {
                            onBarClick(i)
                        }
                    }
                }
            }
        }
        ChartLabels(names, anchor = labelAnchor ?: marked ?: names.lastIndex, bold = marked, endInset = grid.gutterDp)
        if (average != null && averageLabel != null) AverageNote(averageLabel)
    }
}

/** A 2 dp outline around the selected bar from [floor] up [height] (at least a few dp, so an empty day shows too). */
private fun DrawScope.drawSelection(left: Float, bar: Float, floor: Float, height: Float, color: Color) {
    val pad = 2.dp.toPx()
    val top = floor - height.coerceAtLeast(6.dp.toPx()) - pad
    drawRoundRect(
        color,
        Offset(left - pad, top),
        Size(bar + 2 * pad, (floor + pad).coerceAtMost(size.height) - top),
        CornerRadius(4.dp.toPx() + pad),
        style = Stroke(width = 2.dp.toPx()),
    )
}

/** The selected month bar's value over the chart (U9): the 48 dp target that opens its day, with a caret at the bar. */
@Composable
private fun BarTooltip(index: Int, count: Int, text: String, actionLabel: String?, modifier: Modifier, onOpen: () -> Unit) {
    Layout(
        content = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.heightIn(min = 48.dp).clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onOpen)
                    .testTag("chart_tooltip"),
            ) {
                Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
            val caretColor = MaterialTheme.colorScheme.inverseSurface
            Canvas(Modifier.size(12.dp, 6.dp)) {
                drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, caretColor)
            }
        },
        modifier = modifier,
    ) { measurables, c ->
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val p = measurables[0].measure(loose)
        val caret = measurables[1].measure(loose)
        val slot = c.maxWidth.toFloat() / count.coerceAtLeast(1)
        val caretLeft = (slot * (index + 0.5f) - caret.width / 2f).roundToInt().coerceIn(0, (c.maxWidth - caret.width).coerceAtLeast(0))
        layout(c.maxWidth, c.maxHeight) {
            p.place(tooltipLeft(index, slot, p.width, c.maxWidth), 0)
            caret.place(caretLeft, p.height)
        }
    }
}

/**
 * One label per bar under a chart, hidden from TalkBack (the chart's summary covers them). When labels do not fit
 * their slots, only every n-th is shown, counted from [anchor] so its label always stays; n is rounded up to the
 * labels' own spacing (an axis labelled every 6th hour keeps 0, 6, 12, 18). A shown label is centred on its bar and may
 * use the width of the n slots it stands for, so it is not cut to one narrow slot; [bold] marks one label.
 */
@Composable
private fun ChartLabels(names: List<String>, anchor: Int, bold: Int?, endInset: Dp = 0.dp) {
    if (names.none { it.isNotEmpty() }) return
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(end = endInset).clearAndSetSemantics {}) {
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

/** The limit's label measured once per text and theme (U16), with its line and chip colours. */
private class LimitText(val text: TextLayoutResult?, val line: Color, val chip: Color)

@Composable
private fun rememberLimitText(label: String?): LimitText {
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val line = MaterialTheme.colorScheme.onSurface
    val chip = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    return remember(label, style, line, chip) {
        LimitText(label?.takeIf { it.isNotEmpty() }?.let { measurer.measure(it, style, maxLines = 1) }, line, chip)
    }
}

/**
 * A dashed line across the bars at [y]; its label on a surface chip at the left edge, above the line (U18), moved
 * off the highlighted bar's slot [avoid] (see [limitChipOffset]).
 */
private fun DrawScope.drawLimit(y: Float, barsWidth: Float, limit: LimitText, avoid: ClosedFloatingPointRange<Float>?) {
    val dash = 4.dp.toPx()
    drawLine(limit.line, Offset(0f, y), Offset(barsWidth, y), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
    val text = limit.text ?: return
    val pad = 4.dp.toPx()
    val chipHeight = text.size.height + pad
    val chipWidth = text.size.width + 2 * pad
    val at = limitChipOffset(y, chipWidth, chipHeight, 2.dp.toPx(), barsWidth, avoid)
    drawRoundRect(limit.chip, at, Size(chipWidth, chipHeight), CornerRadius(chipHeight / 2))
    drawText(text, topLeft = Offset(at.x + pad, at.y + pad / 2))
}

/** A chart's scale (U5): tick values, their measured labels, the label gutter and the line colours. */
private class ChartGrid(
    val ticks: List<Long>,
    val labels: List<TextLayoutResult>,
    val gutterPx: Float,
    val gutterDp: Dp,
    val lineColor: Color,
    val averageColor: Color,
)

/** The grid of a chart [height] tall: [gridTicks], thinned by [fitTicks] so labels never touch (200 % font). */
@Composable
private fun rememberGrid(max: Long, scale: ChartScale?, height: Dp): ChartGrid {
    val resources = LocalResources.current
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val line = MaterialTheme.colorScheme.outlineVariant
    val average = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    return remember(max, scale, height, style, line, average, resources, density) {
        val all = scale?.let { s -> gridTicks(max, s).associateWith { measurer.measure(tickText(it, s, resources), style, maxLines = 1) } }.orEmpty()
        val labelHeight = all.values.maxOfOrNull { it.size.height }?.toFloat() ?: 0f
        val ticks = fitTicks(all.keys.toList(), max, with(density) { height.toPx() }, labelHeight)
        val labels = ticks.map { all.getValue(it) }
        val gutter = labels.maxOfOrNull { it.size.width }?.let { it + with(density) { 4.dp.toPx() } } ?: 0f
        ChartGrid(ticks, labels, gutter, with(density) { gutter.toDp() }, line, average)
    }
}

/** Thin grid lines across the bars and their labels in the gutter right of them, each centred on its line. */
private fun DrawScope.drawGrid(grid: ChartGrid, max: Long, floor: Float, barsWidth: Float) {
    grid.ticks.forEachIndexed { i, v ->
        val y = floor * (1 - v.toFloat() / max.coerceAtLeast(1L))
        drawLine(grid.lineColor, Offset(0f, y), Offset(barsWidth, y), strokeWidth = 1.dp.toPx())
        val label = grid.labels[i]
        drawText(label, topLeft = Offset(barsWidth + 4.dp.toPx(), tickLabelTop(y, label.size.height.toFloat(), size.height)))
    }
}

/** The average as a finer dashed line than the limit's. */
private fun DrawScope.drawAverage(y: Float, barsWidth: Float, color: Color) {
    val dash = 2.dp.toPx()
    drawLine(color, Offset(0f, y), Offset(barsWidth, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * dash, 2 * dash)))
}

/** What the dashed average line is, under the chart; hidden from TalkBack, which has it in the chart's summary already. */
@Composable
private fun AverageNote(text: String) =
    Text(text, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** The limit a chart draws as a line; for tests only (TalkBack reads the limit's label in the summary). */
val ChartLimitLine = SemanticsPropertyKey<Long>("ChartLimitLine")

/** The average a chart draws as a dashed line; for tests only (the summary states the average for TalkBack). */
val ChartAverageLine = SemanticsPropertyKey<Long>("ChartAverageLine")

/** Records the [limit] and [average] lines a chart topped at [max] actually draws. */
private fun SemanticsPropertyReceiver.chartLines(limit: Long?, average: Long?, max: Long) {
    if (limit != null && limitFraction(limit, max) != null) this[ChartLimitLine] = limit
    if (average != null && limitFraction(average, max) != null) this[ChartAverageLine] = average
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
