package app.kinsight.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/** One part of a [CategoryStrip]: its name, its time as text, its size and its colour. */
data class StripPart(val label: String, val valueText: String, val value: Long, val color: Color)

/**
 * One horizontal bar split by [parts] with their names and times under it (UI refresh §4, block 3), replacing the
 * legend and the category list. TalkBack reads the parts once from the bar; the names under it repeat them in text.
 */
@Composable
fun CategoryStrip(parts: List<StripPart>, modifier: Modifier = Modifier) {
    val shown = parts.filter { it.value > 0 }
    val total = shown.sumOf { it.value }
    if (total <= 0) return
    val description = shown.joinToString(", ") { "${it.label} ${it.valueText}" }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            shown.forEach { p -> Box(Modifier.weight(p.value.toFloat() / total).fillMaxHeight().background(p.color)) }
        }
        CategoryLegend(shown.map { "${it.label} ${it.valueText}" to it.color })
    }
}
