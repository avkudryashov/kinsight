package app.kinsight.core.designsystem.component

import android.content.res.Resources
import androidx.compose.ui.geometry.Offset
import java.text.NumberFormat

/** What a chart's values are, so its scale can be labelled (U5, audit 2026-10-11). */
enum class ChartScale { DURATION, COUNT }

private const val HOUR_MS = 3_600_000L
private val DURATION_STEPS = listOf(1L, 2, 5, 10, 15, 30, 60, 120, 180, 240, 360, 480, 720).map { it * 60_000L }
private val COUNT_STEPS = listOf(1L, 2, 5, 10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000)

/**
 * The 2 or 3 grid lines of a chart whose top is [max]: multiples of the first «nice» step that gives 2 or 3 lines at
 * or under [max]; with none, the largest step that gives one line; nothing for an empty scale or one under the
 * smallest step. A duration tick above one hour is a whole hour, so no label reads «1 h 30 min» and the gutter stays
 * narrow (90..119 min get one line at 1 h).
 */
internal fun gridTicks(max: Long, scale: ChartScale): List<Long> {
    if (max <= 0) return emptyList()
    val steps = if (scale == ChartScale.DURATION) {
        DURATION_STEPS.filter { it % HOUR_MS == 0L || max / it * it <= HOUR_MS }
    } else {
        COUNT_STEPS
    }
    val step = steps.firstOrNull { max / it in 2..3 } ?: steps.lastOrNull { max / it >= 1 } ?: return emptyList()
    return (1..max / step).map { it * step }
}

/**
 * [ticks] (evenly spaced from the first) thinned to every second one until adjacent lines on a chart [heightPx] tall
 * are at least a label and a quarter ([labelHeightPx], larger at 200 % font) and a quarter of the height apart.
 */
internal fun fitTicks(ticks: List<Long>, max: Long, heightPx: Float, labelHeightPx: Float): List<Long> {
    val room = maxOf(labelHeightPx * 1.25f, heightPx / 4)
    var kept = ticks
    while (kept.size > 1 && heightPx * kept[0] / max.coerceAtLeast(1L) < room) kept = kept.filterIndexed { i, _ -> i % 2 == 1 }
    return kept
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

/** Bar [i]'s whole slot, the stretch a chip must keep clear of; null without a bar. */
internal fun BarLayout.slotRange(i: Int?): ClosedFloatingPointRange<Float>? = i?.let { (it * slot)..((it + 1) * slot) }

/**
 * The limit chip's top-left (U18): above its line (below it when there is no room above), at the left edge of the
 * bars, or at their right end when the left would cover the highlighted bar's slot [avoid]; below the line when
 * both ends would.
 */
internal fun limitChipOffset(
    y: Float,
    chipWidth: Float,
    chipHeight: Float,
    gap: Float,
    barsWidth: Float,
    avoid: ClosedFloatingPointRange<Float>? = null,
): Offset {
    val above = if (y - gap - chipHeight >= 0) y - gap - chipHeight else y + gap
    fun covers(x: Float) = avoid != null && x < avoid.endInclusive && x + chipWidth > avoid.start
    val left = gap
    val right = (barsWidth - chipWidth - gap).coerceAtLeast(gap)
    return when {
        !covers(left) -> Offset(left, above)
        !covers(right) -> Offset(right, above)
        else -> Offset(left, y + gap)
    }
}
