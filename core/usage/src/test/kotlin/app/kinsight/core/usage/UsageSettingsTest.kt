package app.kinsight.core.usage

import kotlin.test.Test
import kotlin.test.assertEquals

/** Review I2: each feature waits only for the consent text that first named it, not for the newest one. */
class UsageSettingsTest {
    private fun gates(s: UsageSettings) = listOf(s.cloudAndLocationConsented, s.blockingConsented, s.consentOutdated)

    @Test
    fun eachFeatureWaitsForTheTextThatNamedIt() {
        assertEquals(listOf(false, false, false), gates(UsageSettings()), "never agreed: nothing, and no re-consent card")
        assertEquals(listOf(false, false, true), gates(UsageSettings(consentAtMs = 1, consentVersion = 1)), "Bluetooth-only text")
        assertEquals(listOf(true, false, true), gates(UsageSettings(consentAtMs = 1, consentVersion = 2)), "Disk and location named, blocking not")
        assertEquals(listOf(true, true, false), gates(UsageSettings(consentAtMs = 1, consentVersion = 3)), "the current text")
        assertEquals(listOf(true, true, false), gates(UsageSettings(consentAtMs = 1)))
    }
}
