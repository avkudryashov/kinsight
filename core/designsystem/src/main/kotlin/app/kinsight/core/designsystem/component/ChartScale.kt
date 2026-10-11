package app.kinsight.core.designsystem.component

import android.content.res.Resources
import androidx.compose.ui.geometry.Offset
import java.text.NumberFormat

/** What a chart's values are, so its scale can be labelled (U5, audit 2026-10-11). */
enum class ChartScale { DURATION, COUNT }

private val DURATION_STEPS = listOf(5L, 10, 15, 30, 60, 120, 180, 240, 360, 480, 720).map { it * 60_000L }
private val COUNT_STEPS = listOf(1L, 2, 5, 10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000)

/**
 * The 2 or 3 grid lines of a chart whose top is [max]: multiples of the first «nice» step that gives 2 or 3 lines at
 * or under [max]; with none, the largest step that gives one line; nothing for an empty scale or one under the
 * smallest step.
 */
internal fun gridTicks(max: Long, scale: ChartScale): List<Long> {
    if (max <= 0) return emptyList()
    val steps = if (scale == ChartScale.DURATION) DURATION_STEPS else COUNT_STEPS
    val step = steps.firstOrNull { max / it in 2..3 } ?: steps.lastOrNull { max / it >= 1 } ?: return emptyList()
    return (1..max / step).map { it * step }
}

/** A tick's label: «1 h», «30 min» or a number in the locale's digits. */
internal fun tickText(value: Long, scale: ChartScale, resources: Resources): String = when (scale) {
    ChartScale.DURATION -> formatDuration(resources, value)
    ChartScale.COUNT -> NumberFormat.getIntegerInstance().format(value)
}

/** Horizontal layout of bars: [count] slots over the width left of the scale gutter ([barsWidth]), bars 60 % of a slot. */
internal class BarLayout(val slot: Float, val bar: Float, val barsWidth: Float) {
    fun left(i: Int): Float = i * slot + (slot - bar) / 2
}

internal fun barLayout(widthPx: Float, gutterPx: Float, count: Int): BarLayout {
    val barsWidth = (widthPx - gutterPx).coerceAtLeast(0f)
    val slot = barsWidth / count.coerceAtLeast(1)
    return BarLayout(slot, slot * 0.6f, barsWidth)
}

/** A tick label's top: centred on its line at [y], kept inside a chart [height] tall. */
internal fun tickLabelTop(y: Float, labelHeight: Float, height: Float): Float =
    (y - labelHeight / 2).coerceIn(0f, (height - labelHeight).coerceAtLeast(0f))

/** The limit chip's top-left (U18): at the left edge, above its line, or below it when there is no room above. */
internal fun limitChipOffset(y: Float, chipHeight: Float, gap: Float): Offset =
    Offset(gap, if (y - gap - chipHeight >= 0) y - gap - chipHeight else y + gap)
