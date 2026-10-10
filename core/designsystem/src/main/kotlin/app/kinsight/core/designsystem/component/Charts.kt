package app.kinsight.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * Vertical bars on Canvas (spec §9.8). TalkBack reads [summary] instead of the bars; the highlighted last bar (today)
 * also gets a bold label, so colour is never the only cue. Negative values draw as zero; [labels] are padded or
 * trimmed to the bars. When labels do not fit their slots (narrow screen, large font), only every n-th label is
 * shown, counted back from today so today's label always stays.
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    summary: String,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    highlightLast: Boolean = true,
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
        if (names.any { it.isNotEmpty() }) {
            val style = MaterialTheme.typography.labelSmall
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            val measurer = rememberTextMeasurer()
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val slotPx = constraints.maxWidth.toFloat() / names.size
                val widest = names.maxOf { measurer.measure(it, style.copy(fontWeight = FontWeight.Bold)).size.width }
                val step = labelStep(widest.toFloat(), slotPx)
                Row(Modifier.fillMaxWidth()) {
                    names.forEachIndexed { i, label ->
                        Text(
                            if ((names.lastIndex - i) % step == 0) label else "",
                            Modifier.weight(1f),
                            color = color,
                            style = style,
                            textAlign = TextAlign.Center,
                            fontWeight = if (highlightLast && i == names.lastIndex) FontWeight.Bold else null,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** [labels] padded with blanks or trimmed to [count] bars. */
internal fun chartLabels(labels: List<String>, count: Int): List<String> = List(count) { labels.getOrElse(it) { "" } }

/** Show every n-th label so the widest one ([widestPx]) fits in the n slots it may use. */
internal fun labelStep(widestPx: Float, slotPx: Float): Int =
    if (slotPx <= 0f) 1 else ceil(widestPx / slotPx).toInt().coerceAtLeast(1)
