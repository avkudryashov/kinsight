package app.kinsight.feature.devicedetail

import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.CategoryTotal

/**
 * Block 3 of the report (UI refresh §4): the [max] largest named categories, then one Other part holding the rest
 * and the OTHER bucket itself. Sums to the input; the order is largest first.
 */
internal fun stripParts(categories: List<CategoryTotal>, max: Int = 4): List<CategoryTotal> {
    val shown = categories.filter { it.category != AppCategory.OTHER }.sortedByDescending { it.totalMs }.take(max)
    val rest = categories.sumOf { it.totalMs } - shown.sumOf { it.totalMs }
    return if (rest > 0) shown + CategoryTotal(AppCategory.OTHER, rest) else shown
}
