package app.kinsight.feature.settings

/**
 * Choices of spec §4.7 (local, never synced; defaults 90 and 365 days) and §9.7 (no contact, default 24 hours).
 * Detail never exceeds summary (SettingsStore requires it); every no-contact choice is inside AppPrefs' 1..168.
 */
object RetentionRules {
    val DETAIL = listOf(30, 90, 180, 365)
    val SUMMARY = listOf(90, 180, 365, 730)
    val NO_CONTACT = listOf(6, 12, 24, 48, 72)

    fun withDetail(detail: Int, summary: Int): Pair<Int, Int> = detail to maxOf(summary, SUMMARY.first { it >= detail })

    fun withSummary(detail: Int, summary: Int): Pair<Int, Int> = minOf(detail, DETAIL.last { it <= summary }) to summary
}
