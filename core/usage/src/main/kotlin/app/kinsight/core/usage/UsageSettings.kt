package app.kinsight.core.usage

/**
 * Local settings (spec §4.7, §7, §4.8): sharing off until consent, retention 90 days detail and 365 days summary.
 * [consentAtMs] is when this device agreed on the consent screen. Usage is not read until consent; the first collection
 * after consent includes up to 7 previous days, disclosed on the consent screen (research §6.1). [consentVersion] is
 * the consent text it agreed to.
 */
data class UsageSettings(
    val sharing: Boolean = false,
    val detailRetentionDays: Int = 90,
    val summaryRetentionDays: Int = 365,
    val consentAtMs: Long? = null,
    /** Which consent text this device agreed to; meaningless without [consentAtMs]. */
    val consentVersion: Int = CONSENT_VERSION,
) {
    /**
     * Agreed, but to an older text: nothing the old text did not name may go (privacy policy §13), so a child's phone
     * uses neither the Disk nor the location until it agrees to the current one.
     */
    val consentOutdated: Boolean get() = consentAtMs != null && consentVersion < CONSENT_VERSION

    companion object {
        /**
         * 3 (blocking): parents' limits and blocks enforced on this phone through an accessibility service (app blocking
         * spec §8); blocking waits for it ([consentOutdated]). 2 (cloud Y5) was screen time over Bluetooth and through a
         * parent's Yandex Disk, and the location.
         */
        const val CONSENT_VERSION = 3

        /** 1: screen time over Bluetooth only; every consent stored before versions existed. */
        const val LEGACY_CONSENT_VERSION = 1
    }
}
