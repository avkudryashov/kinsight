package app.kinsight.core.data

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.transport.ble.GroupConfig
import app.kinsight.core.model.addMember
import app.kinsight.core.model.appRuleEntry
import app.kinsight.core.model.proto.AppRule
import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.HourBucket
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.proto.UsageDetail
import app.kinsight.core.model.proto.UsageSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageRepositoryTest {
    private val mom = TestDevice()
    private val today = LocalDate.of(2026, 10, 10)

    // The viewer's clock on [today]: never the wall clock, the child's own day (UTC) depends on it.
    private val noonMs = today.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    private val sunFirst = WeekFields.of(DayOfWeek.SUNDAY, 1)

    @AfterTest
    fun cleanUp() = mom.close()

    private suspend fun Flow<DeviceDetail?>.firstWithin(p: (DeviceDetail?) -> Boolean): DeviceDetail =
        withTimeout(10_000) { first(p)!! }

    @Test
    fun detailFlowCarriesTheStoredUsageDetails() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val kidKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)) }
        val kid = EntryAuthor(mom.groups.read()!!.groupId, kidKeys, lastSeq = 0)
        val monday = LocalDate.of(2026, 10, 5)
        fun day(date: LocalDate, hour: Int) = listOf(
            kid.create { it.copy(usage_summary = UsageSummary(local_date = date.toString(), zone_id = "UTC", total_ms = 600_000)) },
            kid.create {
                it.copy(
                    usage_detail = UsageDetail(
                        local_date = date.toString(), zone_id = "UTC",
                        hours = listOf(HourBucket(hour = hour, apps = listOf(AppTotal("com.example.game", 600_000)), unlocks = 2)),
                    ),
                )
            },
        )
        // A detail outside the requested range must not leak in.
        mom.store().applyBatch(day(monday, 9) + day(today, 15) + day(LocalDate.of(2026, 9, 30), 7))
        val usage = UsageRepository(mom.snapshots, mom.prefs, mom.appendOwn, date = { today }, clock = { noonMs })

        val dayRange = DetailRange.of(Period.DAY, today, sunFirst)
        val hours = assertNotNull(usage.detail(kidKeys.deviceId, dayRange).firstWithin { it?.hours != null }.hours)
        assertEquals(600_000L, hours[15].totalMs)

    }

    @Test
    fun appsFlowListsTheRangesAppsLikeTheDetail() = runBlocking<Unit> {
        // The «All apps» screen reads this lighter flow (final fix F6): no UsageDetail query.
        mom.group.createGroup("Mom")
        val kidKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)) }
        val kid = EntryAuthor(mom.groups.read()!!.groupId, kidKeys, lastSeq = 0)
        fun day(date: LocalDate, vararg apps: Pair<String, Long>) = kid.create {
            it.copy(
                usage_summary = UsageSummary(
                    local_date = date.toString(), zone_id = "UTC", total_ms = apps.sumOf { a -> a.second },
                    apps = apps.map { (p, ms) -> AppTotal(p, ms) },
                ),
            )
        }
        mom.store().applyBatch(
            listOf(day(today, "a" to 60_000L, "b" to 120_000L), day(today.minusDays(1), "a" to 120_000L), day(today.minusDays(9), "c" to 1L)),
        )
        val usage = UsageRepository(mom.snapshots, mom.prefs, mom.appendOwn, date = { today }, clock = { noonMs })
        val week = DetailRange.of(Period.WEEK, today, sunFirst)
        val list = withTimeout(10_000) { usage.apps(kidKeys.deviceId, week).first { it != null }!! }
        assertEquals(listOf("a" to 180_000L, "b" to 120_000L), list.apps.map { it.packageName to it.totalMs })
        assertEquals("Kid", list.member.name)
        assertEquals(week, list.range)
        assertEquals(today, list.today)
        assertEquals(usage.detail(kidKeys.deviceId, week).firstWithin { it != null }.apps, list.apps)
    }

    @Test
    fun aCategoryOverrideReEmitsTheDetailAndTheApp() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val kidKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)) }
        val kid = EntryAuthor(mom.groups.read()!!.groupId, kidKeys, lastSeq = 0)
        val game = "com.example.game" // no catalog entry, not in the built-in table: Other
        mom.store().applyBatch(
            listOf(
                kid.create {
                    it.copy(usage_summary = UsageSummary(local_date = today.toString(), zone_id = "UTC", total_ms = 600_000, apps = listOf(AppTotal(game, 600_000))))
                },
            ),
        )
        val usage = UsageRepository(mom.snapshots, mom.prefs, mom.appendOwn, date = { today }, clock = { noonMs })
        val week = DetailRange.of(Period.WEEK, today, sunFirst)
        fun categories(d: DeviceDetail) = d.categories.map { it.category to it.totalMs }

        assertEquals(listOf(AppCategory.OTHER to 600_000L), categories(usage.detail(kidKeys.deviceId, week).firstWithin { it?.categories?.isNotEmpty() == true }))
        val app = withTimeout(10_000) { usage.app(kidKeys.deviceId, game, week).first { it != null }!! }
        assertEquals(AppCategory.OTHER, app.category)
        assertEquals(true, app.canChangeCategory) // the viewer is Mom, a parent

        usage.setCategory(game, AppCategory.GAMES)
        usage.detail(kidKeys.deviceId, week).firstWithin { d -> d != null && categories(d) == listOf(AppCategory.GAMES to 600_000L) }
        val changed = withTimeout(10_000) { usage.app(kidKeys.deviceId, game, week).first { it?.category == AppCategory.GAMES }!! }
        assertEquals(true, changed.overridden)

        usage.setCategory(game, null) // «По умолчанию»
        usage.detail(kidKeys.deviceId, week).firstWithin { d -> d != null && categories(d) == listOf(AppCategory.OTHER to 600_000L) }
        assertEquals(false, withTimeout(10_000) { usage.app(kidKeys.deviceId, game, week).first { it?.category == AppCategory.OTHER }!! }.overridden)
    }

    private suspend fun momWithKid(): DeviceKeys {
        mom.group.createGroup("Mom")
        val kidKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)) }
        return kidKeys
    }

    private fun repo() = UsageRepository(mom.snapshots, mom.prefs, mom.appendOwn, date = { today }, clock = { noonMs })

    @Test
    fun setWritesAWinningRuleTheFlowShows() = runBlocking<Unit> {
        val kid = momWithKid().deviceId
        val usage = repo()
        val rule = ThresholdRule.of(kid, ThresholdTarget.Total, 3_600_000L, blockOnLimit = true)!!
        usage.setThreshold(rule)
        assertEquals(listOf(rule.copy(author = mom.keys.deviceId)), withTimeout(10_000) { usage.thresholds.first { it.isNotEmpty() } })
        assertEquals(1L, mom.store().appRules().single().body.app_rule!!.rank)
        assertEquals(emptyList(), mom.prefs.thresholds.first(), "never the local prefs")
        assertNull(withTimeout(10_000) { usage.ruleChangedBy(rule.id).first() }, "her own rule names nobody")

        // The same rule again signs nothing; a changed one ranks above the winner.
        val seq = mom.store().authorState(mom.keys.deviceId).lastSeq
        usage.setThreshold(rule)
        assertEquals(seq, mom.store().authorState(mom.keys.deviceId).lastSeq)
        val block = ThresholdRule.block(kid, ThresholdTarget.App("com.game"))!!
        usage.setThreshold(block)
        val raised = ThresholdRule.of(kid, ThresholdTarget.Total, 7_200_000L)!!
        usage.setThreshold(raised)
        assertEquals(
            setOf(raised, block).map { it.copy(author = mom.keys.deviceId) }.toSet(),
            withTimeout(10_000) { usage.thresholds.first { r -> r.any { it.limitMs == 7_200_000L } } }.toSet(),
        )
        assertEquals(2L, mom.store().appRules().filter { it.body.app_rule!!.target == AppRule.Target.TOTAL }.maxOf { it.body.app_rule!!.rank })
    }

    @Test
    fun removeWritesADeletedWinner() = runBlocking<Unit> {
        val kid = momWithKid().deviceId
        val usage = repo()
        val rule = ThresholdRule.of(kid, ThresholdTarget.Total, 3_600_000L)!!
        usage.removeThreshold(rule.id) // no winner: nothing signed
        assertTrue(mom.store().appRules().isEmpty())
        usage.setThreshold(rule)
        withTimeout(10_000) { usage.thresholds.first { it.isNotEmpty() } }
        usage.removeThreshold(rule.id)
        assertEquals(emptyList(), withTimeout(10_000) { usage.thresholds.first { it.isEmpty() } })
        val winner = AppRules.winners(mom.store().appRules(), (mom.snapshots.load(emptyMap()) as GroupSnapshot.Ready).membership)[rule.id]!!
        assertTrue(winner.body.app_rule!!.deleted)
        assertEquals(2L, winner.body.app_rule!!.rank)
        assertEquals(3_600_000L, winner.body.app_rule!!.limit_ms, "the tombstone keeps the winner's fields")
        val seq = mom.store().authorState(mom.keys.deviceId).lastSeq
        usage.removeThreshold(rule.id) // already deleted
        assertEquals(seq, mom.store().authorState(mom.keys.deviceId).lastSeq)
        usage.setThreshold(rule) // back again, above the tombstone
        assertEquals(listOf(rule.copy(author = mom.keys.deviceId)), withTimeout(10_000) { usage.thresholds.first { it.isNotEmpty() } })
    }

    @Test
    fun aChildPhoneRefusesToWrite() = runBlocking<Unit> {
        val kid = TestDevice()
        try {
            val genesis = GroupGenesis.create(mom.keys, "Mom")
            val momAuthor = EntryAuthor(genesis.groupId, mom.keys, lastSeq = 1, lastLamport = 1)
            val add = momAuthor.addMember(kid.keys.sign.publicKey, kid.keys.dh.publicKey, "Kid", DeviceRole.CHILD)
            kid.groups.write(GroupConfig(genesis.groupId, ByteArray(32)))
            kid.store().applyBatch(listOf(genesis.entry, add))
            val local = ThresholdRule.of(kid.keys.deviceId, ThresholdTarget.Total, 3_600_000L)!!
            kid.storeLocalLimit(local) // a stray local limit: not published, not cleared

            kid.usage.setThreshold(local)
            kid.usage.removeThreshold(local.id)
            kid.usage.migrateLocalThresholds()
            assertTrue(kid.store().appRules().isEmpty())
            assertEquals(0L, kid.store().authorState(kid.keys.deviceId).lastSeq)
            assertEquals(listOf(local), kid.prefs.thresholds.first())
        } finally {
            kid.close()
        }
    }

    @Test
    fun aRuleForANonChildIsRefused() = runBlocking<Unit> {
        momWithKid()
        val usage = repo()
        val seq = mom.store().authorState(mom.keys.deviceId).lastSeq
        usage.setThreshold(ThresholdRule.of(DeviceKeys.generate().deviceId, ThresholdTarget.Total, 3_600_000L)!!) // not a member
        usage.setThreshold(ThresholdRule.of(mom.keys.deviceId, ThresholdTarget.Total, 3_600_000L)!!) // a parent
        usage.setThreshold(ThresholdRule("x", mom.keys.deviceId, ThresholdTarget.Total)) // not a valid rule
        assertEquals(seq, mom.store().authorState(mom.keys.deviceId).lastSeq)
    }

    @Test
    fun migrationKeepsASharedRule() = runBlocking<Unit> {
        val kidKeys = momWithKid()
        val kid = kidKeys.deviceId
        val dadKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad", DeviceRole.PARENT)) }
        val dad = EntryAuthor(mom.groups.read()!!.groupId, dadKeys, lastSeq = 0)
        val twoHours = ThresholdRule.of(kid, ThresholdTarget.Total, 7_200_000L)!!
        val w = AppRules.wire(twoHours)
        mom.store().applyBatch(
            listOf(dad.appRuleEntry(w.child_id.toByteArray(), w.target, mode = w.mode, limitMs = w.limit_ms, rank = 1)),
        )
        mom.storeLocalLimit(ThresholdRule.of(kid, ThresholdTarget.Total, 3_600_000L)!!)
        val usage = repo()
        usage.migrateLocalThresholds()
        assertEquals(listOf(twoHours.copy(author = dadKeys.deviceId)), withTimeout(10_000) { usage.thresholds.first { it.isNotEmpty() } })
        assertEquals(emptyList(), mom.prefs.thresholds.first(), "prefs cleared")
        assertTrue(mom.store().appRules().none { it.author == mom.keys.deviceId }, "no own entry for Total")
    }

    @Test
    fun migrationPublishesALocalRuleNobodySet() = runBlocking<Unit> {
        val kid = momWithKid().deviceId
        val total = ThresholdRule.of(kid, ThresholdTarget.Total, 3_600_000L)!!
        val games = ThresholdRule.of(kid, ThresholdTarget.Category(AppCategory.GAMES), 1_800_000L)!!
        val gone = ThresholdRule.of(DeviceKeys.generate().deviceId, ThresholdTarget.Total, 3_600_000L)!!
        listOf(total, games, gone).forEach { mom.storeLocalLimit(it) }
        val usage = repo()
        usage.migrateLocalThresholds()
        assertEquals(
            setOf(total, games).map { it.copy(author = mom.keys.deviceId) }.toSet(),
            withTimeout(10_000) { usage.thresholds.first { it.size == 2 } }.toSet(),
        )
        assertEquals(emptyList(), mom.prefs.thresholds.first())
        assertEquals(2, mom.store().appRules().size, "a device that is no child is dropped, not signed")
        // A rerun signs nothing.
        val seq = mom.store().authorState(mom.keys.deviceId).lastSeq
        usage.migrateLocalThresholds()
        assertEquals(seq, mom.store().authorState(mom.keys.deviceId).lastSeq)
    }

    @Test
    fun migrationWithoutAGroupKeepsThePrefs() = runBlocking<Unit> {
        val limit = ThresholdRule.of(DeviceKeys.generate().deviceId, ThresholdTarget.Total, 3_600_000L)!!
        mom.storeLocalLimit(limit)
        repo().migrateLocalThresholds()
        assertEquals(listOf(limit), mom.prefs.thresholds.first())
    }

    @Test
    fun onlyAParentOfAGroupMaySetThresholds() = runBlocking<Unit> {
        val usage = UsageRepository(mom.snapshots, mom.prefs, mom.appendOwn, date = { today }, clock = { noonMs })
        assertEquals(false, withTimeout(10_000) { usage.viewerIsParent.first() }, "no group yet")
        mom.group.createGroup("Mom")
        assertEquals(true, withTimeout(10_000) { usage.viewerIsParent.first { it } })
    }

    @Test
    fun theCardsCollectionOffFollowsTheNoContactSetting() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val kidKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)) }
        val kid = EntryAuthor(mom.groups.read()!!.groupId, kidKeys, lastSeq = 0)
        val consent = kid.create { it.copy(created_at_ms = noonMs - 30 * 3_600_000L, state_event = StateEvent(kind = StateEvent.Kind.SHARING_RESUMED)) }
        mom.store().applyBatch(listOf(consent))
        val usage = UsageRepository(mom.snapshots, mom.prefs, mom.appendOwn, date = { today }, clock = { noonMs })
        fun off(d: DashboardState?) = d?.children?.singleOrNull()?.collectionOff
        assertEquals(true, withTimeout(10_000) { usage.dashboard.first { off(it) != null } }.let(::off), "default 24 h: 30 h silent is off")
        mom.prefs.setNoContactHours(72)
        assertEquals(false, withTimeout(10_000) { usage.dashboard.first { off(it) == false } }.let(::off), "inside the 72 h window")
    }

    /** Three earlier days (UTC) with 30 min by noon and 60 min after, then today's summary written at noon. */
    private suspend fun seedUsual(todayMinutes: Long): DeviceKeys {
        mom.group.createGroup("Mom")
        val kidKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)) }
        val kid = EntryAuthor(mom.groups.read()!!.groupId, kidKeys, lastSeq = 0)
        val min = 60_000L
        val earlier = (1L..3L).flatMap { k ->
            val date = today.minusDays(k)
            listOf(
                kid.create { it.copy(usage_summary = UsageSummary(local_date = date.toString(), zone_id = "UTC", total_ms = 90 * min)) },
                kid.create {
                    it.copy(
                        usage_detail = UsageDetail(
                            local_date = date.toString(), zone_id = "UTC",
                            hours = listOf(
                                HourBucket(hour = 9, apps = listOf(AppTotal("com.example.game", 30 * min))),
                                HourBucket(hour = 15, apps = listOf(AppTotal("com.example.game", 60 * min))),
                            ),
                        ),
                    )
                },
            )
        }
        val todays = kid.create {
            it.copy(created_at_ms = noonMs, usage_summary = UsageSummary(local_date = today.toString(), zone_id = "UTC", total_ms = todayMinutes * min))
        }
        mom.store().applyBatch(earlier + todays)
        return kidKeys
    }

    private suspend fun dashboardWithUsual(todayMinutes: Long): TodayComparison? {
        seedUsual(todayMinutes)
        val usage = UsageRepository(mom.snapshots, mom.prefs, mom.appendOwn, date = { today }, clock = { noonMs })
        return withTimeout(10_000) { usage.dashboard.first { it?.children?.singleOrNull()?.comparison != null } }!!.children.single().comparison
    }

    @Test
    fun theDashboardComparesWithUsualByThisTimeFromStoredDetails() = runBlocking<Unit> {
        assertEquals(TodayComparison.ByThisTime(30 * 60_000L, 3), dashboardWithUsual(todayMinutes = 60))
    }

    @Test
    fun aFewMinutesOverTheUsualReadAboutTheSame() = runBlocking<Unit> {
        val c = assertIs<TodayComparison.ByThisTime>(dashboardWithUsual(todayMinutes = 34))
        assertTrue(UsageReadModel.aboutTheSame(34 * 60_000L, c.usualMs), "4 min over a usual 30 min")
    }

    @Test
    fun theTodayReportCarriesTheSameComparisonAsTheDashboard() = runBlocking<Unit> {
        val kidKeys = seedUsual(todayMinutes = 60)
        val usage = UsageRepository(mom.snapshots, mom.prefs, mom.appendOwn, date = { today }, clock = { noonMs })
        val day = usage.detail(kidKeys.deviceId, DetailRange.of(Period.DAY, today, sunFirst)).firstWithin { it?.dayComparison != null }
        assertEquals(TodayComparison.ByThisTime(30 * 60_000L, 3), day.dayComparison)
    }
}
