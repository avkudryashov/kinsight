package app.kinsight.core.designsystem.component

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vertical bars on Canvas (spec §9.8). TalkBack reads [summary] instead of the bars; the highlighted last bar (today)
 * also gets a bold label, so colour is never the only cue.
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
    Column(modifier.clearAndSetSemantics { contentDescription = summary }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
            val slot = size.width / values.size.coerceAtLeast(1)
            val bar = slot * 0.6f
            values.forEachIndexed { i, v ->
                val h = (size.height * v.toFloat() / max).coerceAtLeast(if (v > 0) 2.dp.toPx() else 0f)
                drawRoundRect(
                    color = if (highlightLast && i == values.lastIndex) highlight else normal,
                    topLeft = Offset(i * slot + (slot - bar) / 2, size.height - h),
                    size = Size(bar, h),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { i, label ->
                    Text(
                        label,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        fontWeight = if (highlightLast && i == labels.lastIndex) FontWeight.Bold else null,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
