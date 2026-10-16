package app.kinsight.core.data

import app.kinsight.core.usage.UsageSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsRepositoryTest {
    private val kid = TestDevice()

    @AfterTest
    fun cleanUp() = kid.close()

    /** Roadmap Y5 (ruling P5): «Принимаю» on the new text moves only the version; the first consent time and the sharing switch stay. */
    @Test
    fun anOutdatedChildAgreeingAgainKeepsItsFirstConsentTime() = runBlocking<Unit> {
        var upgraded = 0
        val repo = SettingsRepository(kid.settings, kid.prefs, kid.pipeline, prune = {}, clock = { kid.now }, onConsentUpgraded = { upgraded++ })
        val first = kid.now
        kid.settings.recordConsent(first, version = UsageSettings.LEGACY_CONSENT_VERSION)
        kid.now += 86_400_000L
        repo.agreeToConsent()
        val s = kid.settings.current()
        assertEquals(first, s.consentAtMs, "the first consent time stays")
        assertEquals(UsageSettings.CONSENT_VERSION, s.consentVersion)
        assertFalse(s.consentOutdated)
        assertFalse(s.sharing, "sharing stays as it was")
        assertEquals(1, upgraded, "the cloud run and the location refresh are asked for")
    }
}
