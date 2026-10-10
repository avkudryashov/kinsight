package app.kinsight.feature.settings

import app.kinsight.core.data.Prefs
import app.kinsight.core.usage.SettingsStore
import app.kinsight.core.usage.UsageSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetentionRulesTest {
    @Test
    fun detailNeverExceedsSummary() {
        assertEquals(365 to 365, RetentionRules.withDetail(365, 90))
        assertEquals(30 to 730, RetentionRules.withDetail(30, 730))
        assertEquals(90 to 90, RetentionRules.withSummary(180, 90))
        assertEquals(30 to 730, RetentionRules.withSummary(30, 730))
    }

    @Test
    fun everyChoiceGivesAPairTheSettingsStoreAccepts() {
        for (d in RetentionRules.DETAIL) for (s in RetentionRules.SUMMARY) {
            for ((detail, summary) in listOf(RetentionRules.withDetail(d, s), RetentionRules.withSummary(d, s))) {
                assertTrue(detail in 1..summary && summary <= SettingsStore.MAX_RETENTION_DAYS, "$d/$s gave $detail/$summary")
            }
        }
    }

    @Test
    fun theDefaultsAreAmongTheChoices() {
        // Spec §4.7 (90 / 365 days) and §9.7 (24 hours): a fresh install shows a selected option.
        assertTrue(UsageSettings().detailRetentionDays in RetentionRules.DETAIL)
        assertTrue(UsageSettings().summaryRetentionDays in RetentionRules.SUMMARY)
        assertTrue(Prefs().noContactHours in RetentionRules.NO_CONTACT)
    }

    @Test
    fun noContactChoicesPassTheAppPrefsRangeCheck() {
        // AppPrefs.setNoContactHours requires 1..168 and AlertPlanner reads the stored value as is.
        assertTrue(RetentionRules.NO_CONTACT.all { it in 1..168 })
    }
}
