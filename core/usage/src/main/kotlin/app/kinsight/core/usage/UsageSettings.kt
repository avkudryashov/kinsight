package app.kinsight.core.usage

/**
 * Local settings (spec §4.7, §7, §4.8): sharing off until consent, retention 90 days detail and 365 days summary.
 * [consentAtMs] is when this device agreed on the consent screen. Usage is not read until consent; the first collection
 * after consent includes up to 7 previous days, disclosed on the consent screen (research §6.1).
 */
data class UsageSettings(
    val sharing: Boolean = false,
    val detailRetentionDays: Int = 90,
    val summaryRetentionDays: Int = 365,
    val consentAtMs: Long? = null,
)
