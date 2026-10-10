package app.kinsight.core.usage

/** Local settings (spec §4.7, §7): sharing off until consent (stage 4), retention 90 days detail, 365 days summary. */
data class UsageSettings(
    val sharing: Boolean = false,
    val detailRetentionDays: Int = 90,
    val summaryRetentionDays: Int = 365,
)
