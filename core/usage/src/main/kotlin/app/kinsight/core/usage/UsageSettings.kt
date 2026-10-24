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
    /**
     * A parent's «show my screen time» (own time spec 2026-10-24): its own usage is collected into the local queue and
     * shown on this phone only, never signed or sent ([UsagePipeline.collectLocal]). Off until the parent turns it on.
     */
    val ownTimeLocal: Boolean = false,
) {
    /**
     * Agreed, but to an older text than the current one: the re-consent card asks the child to look. It does not switch
     * anything off by itself; each feature waits only for the text that first named it (privacy policy §12, §13):
     * [cloudAndLocationConsented], [blockingConsented].
     */
    val consentOutdated: Boolean get() = consentAtMs != null && consentVersion < CONSENT_VERSION

    /** Agreed to a text that names the Disk and the location (version 2 or later): a child's phone may use both. */
    val cloudAndLocationConsented: Boolean get() = consentCovers(CLOUD_AND_LOCATION_CONSENT_VERSION)

    /** Agreed to a text that names parents' limits and blocks on this phone (version 3 or later): blocking may act. */
    val blockingConsented: Boolean get() = consentCovers(BLOCKING_CONSENT_VERSION)

    /** Agreed, and to [version] or a later text. */
    fun consentCovers(version: Int): Boolean = consentAtMs != null && consentVersion >= version

    companion object {
        /**
         * 3 (blocking): parents' limits and blocks enforced on this phone through an accessibility service (app blocking
         * spec §8); only blocking waits for it ([blockingConsented]). 2 (cloud Y5) was screen time over Bluetooth and
         * through a parent's Yandex Disk, and the location ([cloudAndLocationConsented]); a version 2 child keeps both.
         */
        const val CONSENT_VERSION = 3

        /** The first text naming the Disk and the location. */
        const val CLOUD_AND_LOCATION_CONSENT_VERSION = 2

        /** The first text naming limits and blocks enforced on the child's phone. */
        const val BLOCKING_CONSENT_VERSION = 3

        /** 1: screen time over Bluetooth only; every consent stored before versions existed. */
        const val LEGACY_CONSENT_VERSION = 1
    }
}
