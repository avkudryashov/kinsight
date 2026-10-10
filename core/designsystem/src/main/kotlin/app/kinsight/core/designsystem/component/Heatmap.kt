package app.kinsight.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val DAYS = 7
private const val HOURS = 24
private const val HOUR_LABEL_STEP = 6

/**
 * Day-of-week by hour grid on Canvas (spec §9.8): [cells] are 7 rows (days in the caller's order, named by
 * [rowLabels]) of 24 hours. Intensity follows the value from the empty surface tone to the primary colour, so it reads
 * as lightness, not hue; TalkBack reads [summary] instead of the grid. Rows grow with the label font (up to 200 %).
 * [hourLabels] name the hour ticks 0, 6, 12 and 18 in the caller's locale format ("12 AM" … or "0" …); by default
 * they are the bare 24-hour numbers.
 */
@Composable
fun Heatmap(
    cells: Array<LongArray>,
    rowLabels: List<String>,
    summary: String,
    modifier: Modifier = Modifier,
    hourLabels: List<String>? = null,
) {
    val ticks = heatHourLabels(hourLabels)
    val grid = heatCells(cells)
    val max = grid.maxOf { row -> row.max() }
    val names = chartLabels(rowLabels, DAYS)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val full = MaterialTheme.colorScheme.primary
    val style = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rowHeight = if (style.lineHeight.isSp) with(density) { style.lineHeight.toDp() } else 16.dp
    val labelWidth = with(density) { (names.maxOf { measurer.measure(it, style).size.width }).toDp() } + 8.dp
    Column(modifier.clearAndSetSemantics { contentDescription = summary }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        grid.forEachIndexed { d, row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    names[d],
                    Modifier.width(labelWidth),
                    color = labelColor,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
                Canvas(Modifier.weight(1f).heightIn(min = 16.dp).height(rowHeight)) {
                    val slot = size.width / HOURS
                    val gap = 1.dp.toPx()
                    row.forEachIndexed { h, v ->
                        drawRoundRect(
                            color = lerp(empty, full, heatIntensity(v, max)),
                            topLeft = Offset(h * slot + gap / 2, 0f),
                            size = Size(slot - gap, size.height),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(labelWidth))
            ticks.forEach { label ->
                Text(label, Modifier.weight(1f), color = labelColor, style = style, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** The hour axis ticks (every [HOUR_LABEL_STEP] hours): [labels] padded with blanks or trimmed, else "0", "6", "12", "18". */
internal fun heatHourLabels(labels: List<String>?): List<String> =
    labels?.let { chartLabels(it, HOURS / HOUR_LABEL_STEP) } ?: (0 until HOURS step HOUR_LABEL_STEP).map { "$it" }

/** [cells] padded or trimmed to 7 rows of 24 hours, negative values as zero. */
internal fun heatCells(cells: Array<LongArray>): Array<LongArray> =
    Array(DAYS) { d -> LongArray(HOURS) { h -> cells.getOrNull(d)?.getOrNull(h)?.coerceAtLeast(0L) ?: 0L } }

/** Cell intensity 0..1: [value] relative to the grid's [max]; nothing or negative is 0. */
internal fun heatIntensity(value: Long, max: Long): Float =
    if (max <= 0L || value <= 0L) 0f else (value.toDouble() / max).toFloat().coerceIn(0f, 1f)
