package app.kinsight.core.usage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStoreTest {
    private val dir = Files.createTempDirectory("kinsight-settings").toFile()
    private val file = File(dir, "usage.preferences_pb")
    private val scopes = mutableListOf<CoroutineScope>()

    private fun store(): SettingsStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += scope
        return SettingsStore.create(file, scope)
    }

    @AfterTest
    fun cleanUp() {
        runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        dir.deleteRecursively()
    }

    @Test
    fun defaultsAreSharingOffAndSpecRetention() = runTest {
        assertEquals(UsageSettings(sharing = false, detailRetentionDays = 90, summaryRetentionDays = 365), store().current())
    }

    @Test
    fun valuesPersistAcrossInstances() = runTest {
        val first = store()
        first.setSharing(true)
        first.setRetention(30, 200)
        // DataStore allows one active instance per file: finish the first before opening the second.
        scopes.removeLast().coroutineContext.job.cancelAndJoin()
        assertEquals(UsageSettings(sharing = true, detailRetentionDays = 30, summaryRetentionDays = 200), store().current())
    }

    @Test
    fun rejectsInvalidRetention() = runTest {
        val s = store()
        assertFailsWith<IllegalArgumentException> { s.setRetention(0, 10) }
        assertFailsWith<IllegalArgumentException> { s.setRetention(100, 50) }
        assertFailsWith<IllegalArgumentException> { s.setRetention(10, 4000) }
        assertEquals(UsageSettings(), s.current())
    }

    @Test
    fun consentIsRecordedAndResetForgetsEverything() = runTest {
        val store = store()
        assertNull(store.current().consentAtMs)
        store.recordConsent(1_234L)
        store.setSharing(true)
        store.setRetention(detailDays = 30, summaryDays = 200)
        assertEquals(1_234L, store.current().consentAtMs)
        store.reset()
        assertEquals(UsageSettings(), store.current())
    }

    @Test
    fun aConsentStoredBeforeVersionsIsTheOldTextAndAnUpgradeKeepsItsTime() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        // A file written by a build before Y5: the consent time only.
        val raw = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        raw.edit { it[longPreferencesKey("consent_at_ms")] = 1_000L }
        val s = SettingsStore(raw)
        assertEquals(UsageSettings.LEGACY_CONSENT_VERSION, s.current().consentVersion)
        assertTrue(s.current().consentOutdated)
        s.upgradeConsent()
        assertEquals(UsageSettings(consentAtMs = 1_000L, consentVersion = UsageSettings.CONSENT_VERSION), s.current())
        assertFalse(s.current().consentOutdated)
    }

    @Test
    fun aStoredVersion2ConsentIsOutdatedSinceBlockingWasNotNamedInIt() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val raw = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        raw.edit {
            it[longPreferencesKey("consent_at_ms")] = 1_000L
            it[androidx.datastore.preferences.core.intPreferencesKey("consent_version")] = 2
        }
        val s = SettingsStore(raw).current()
        assertEquals(2, s.consentVersion)
        assertTrue(s.consentOutdated)
        assertEquals(3, UsageSettings.CONSENT_VERSION)
    }

    @Test
    fun aNewConsentIsTheCurrentTextAndNoConsentIsNeverOutdated() = runTest {
        val s = store()
        s.upgradeConsent()
        assertNull(s.current().consentAtMs, "an upgrade without a consent records nothing")
        assertFalse(s.current().consentOutdated)
        s.recordConsent(2_000L)
        assertEquals(UsageSettings.CONSENT_VERSION, s.current().consentVersion)
        s.recordConsent(3_000L, version = UsageSettings.LEGACY_CONSENT_VERSION)
        assertTrue(s.current().consentOutdated)
    }
}
