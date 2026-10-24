package app.kinsight.core.data

import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.model.EntryKind
import app.kinsight.core.usage.AppInfo
import app.kinsight.core.usage.CollectResult
import app.kinsight.core.usage.EventType
import app.kinsight.core.usage.UsageEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.WeekFields
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A parent's own screen time on its own phone (own time spec 2026-10-24): local queue only, never the log. */
class OwnTimeLocalTest {
    private val mom = TestDevice()
    private val today = LocalDate.of(2026, 10, 10)
    private val noonMs = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val sunFirst = WeekFields.of(DayOfWeek.SUNDAY, 1)
    private val maps = "com.example.maps"
    private val chat = "com.example.chat"
    private val min = 60_000L

    @AfterTest
    fun cleanUp() = mom.close()

    private fun at(date: LocalDate, h: Int, m: Int) = date.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun use(pkg: String, date: LocalDate, fromH: Int, fromM: Int, toH: Int, toM: Int) {
        mom.usageEvents.events += UsageEvent(at(date, fromH, fromM), EventType.RESUMED, pkg, "$pkg.Main")
        mom.usageEvents.events += UsageEvent(at(date, toH, toM), EventType.PAUSED, pkg, "$pkg.Main")
    }

    private fun repo() = UsageRepository(mom.snapshots, mom.prefs, mom.appendOwn, date = { today }, clock = { noonMs }, ownLocal = mom.ownLocal)

    private suspend fun <T> Flow<T>.within(p: (T) -> Boolean): T = withTimeout(10_000) { first(p) }

    private suspend fun momWithOwnTime() {
        mom.now = noonMs
        mom.group.createGroup("Mom")
        use(maps, today, 9, 0, 9, 30)
        use(chat, today.minusDays(1), 20, 0, 20, 45)
    }

    @Test
    fun theDashboardShowsTheParentsOwnTodayOnceSwitchedOn() = runBlocking<Unit> {
        momWithOwnTime()
        val usage = repo()
        assertNull(usage.dashboard.within { it != null }!!.selfUsage) // off by default
        mom.settingsRepository.setOwnTimeLocal(true)
        val self = usage.dashboard.within { it?.selfUsage?.today?.hasData == true }!!.selfUsage!!
        assertEquals(30 * min, self.today.totalMs)
        assertTrue(self.member.ownLocal)
        assertTrue(self.member.isSelf)
        assertFalse(self.ownAccessMissing)
        assertEquals(45 * min, self.week[5].totalMs) // yesterday
        assertEquals(listOf(maps), self.topApps.map { it.app.packageName })
    }

    @Test
    fun detailAppsAndAppOfSelfReadTheLocalQueue() = runBlocking<Unit> {
        momWithOwnTime()
        mom.appInfo.known[maps] = AppInfo("Maps", byteArrayOf(1, 2), category = 6)
        mom.settingsRepository.setOwnTimeLocal(true)
        val usage = repo()
        val self = mom.keys.deviceId
        val week = DetailRange.of(Period.WEEK, today, sunFirst)
        val d = usage.detail(self, week).within { it?.totalMs == 75 * min }!!
        assertTrue(d.member.ownLocal)
        assertEquals(listOf(today.minusDays(1), today), d.days.filter { it.known }.map { it.date })
        assertEquals(listOf(chat to 45 * min, maps to 30 * min), d.apps.map { it.packageName to it.totalMs })
        assertEquals("Maps", d.apps.first { it.packageName == maps }.label)

        val day = usage.detail(self, DetailRange.of(Period.DAY, today, sunFirst)).within { it?.hours != null }!!
        assertEquals(30 * min, day.hours!![9].totalMs)

        val apps = usage.apps(self, week).within { it != null }!!
        assertEquals(listOf(chat, maps), apps.apps.map { it.packageName })

        val app = usage.app(self, maps, DetailRange.of(Period.DAY, today, sunFirst)).within { it != null }!!
        assertEquals(30 * min, app.totalMs)
    }

    @Test
    fun nothingReachesTheLogAndNoEventWaits() = runBlocking<Unit> {
        momWithOwnTime()
        val store = mom.store()
        val before = store.liveEntries().size
        val seqBefore = store.authorState(mom.keys.deviceId).lastSeq
        mom.settingsRepository.setOwnTimeLocal(true)
        repeat(3) { i ->
            mom.now = noonMs + (i + 1) * 3_600_000L // the wall clock moves while elapsed stands: CLOCK_CHANGED each run
            assertEquals(CollectResult.COLLECTED, mom.pipeline.collectLocal())
        }
        mom.pipeline.collectAndPublish(force = true)
        val usage = repo()
        usage.dashboard.within { it?.selfUsage?.today?.hasData == true }
        usage.detail(mom.keys.deviceId, DetailRange.of(Period.DAY, today, sunFirst)).within { it?.hours != null }

        assertEquals(before, store.liveEntries().size)
        assertEquals(seqBefore, store.authorState(mom.keys.deviceId).lastSeq)
        val usageKinds = setOf(EntryKind.USAGE_SUMMARY, EntryKind.USAGE_DETAIL, EntryKind.APP_CATALOG, EntryKind.STATE_EVENT)
        assertTrue(store.liveEntries().none { it.author == mom.keys.deviceId && it.kind in usageKinds })
        assertTrue(RoomUsageQueue(mom.db).pendingEvents().isEmpty())
    }

    @Test
    fun turningItOffHidesTheCardAndStopsCollection() = runBlocking<Unit> {
        momWithOwnTime()
        mom.settingsRepository.setOwnTimeLocal(true)
        val usage = repo()
        usage.dashboard.within { it?.selfUsage?.today?.hasData == true }
        mom.settingsRepository.setOwnTimeLocal(false)
        assertNull(usage.dashboard.within { it?.selfUsage == null }!!.selfUsage)
        assertEquals(CollectResult.NOT_ALLOWED, mom.pipeline.collectLocal())
        assertTrue(RoomUsageQueue(mom.db).changedDays().isEmpty())
        val d = usage.detail(mom.keys.deviceId, DetailRange.of(Period.WEEK, today, sunFirst)).within { it != null }!!
        assertFalse(d.member.ownLocal)
        assertEquals(0L, d.totalMs)
    }

    @Test
    fun withoutUsageAccessTheCardAsksForIt() = runBlocking<Unit> {
        momWithOwnTime()
        mom.usageAccessGranted = false
        mom.settingsRepository.setOwnTimeLocal(true)
        val self = repo().dashboard.within { it?.selfUsage != null }!!.selfUsage!!
        assertTrue(self.ownAccessMissing)
        assertFalse(self.today.hasData)
        val d = repo().detail(mom.keys.deviceId, DetailRange.of(Period.DAY, today, sunFirst)).within { it != null }
        assertNotNull(d)
        assertNull(d.hours)
    }
}
