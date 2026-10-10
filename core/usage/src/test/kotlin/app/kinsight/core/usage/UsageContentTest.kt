package app.kinsight.core.usage

import app.kinsight.core.database.PendingHourRow
import app.kinsight.core.database.PendingUsageRow
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.sync.SecureChannel
import app.kinsight.core.sync.SyncSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UsageContentTest {
    private val date = "2026-10-08"
    private val apps = listOf(
        PendingUsageRow(date, 10, MAPS, 30 * MIN),
        PendingUsageRow(date, 10, CHAT, 10 * MIN),
        PendingUsageRow(date, 11, MAPS, 5 * MIN),
        PendingUsageRow(date, 11, CHAT, 0),
    )
    private val hours = listOf(
        PendingHourRow(date, 10, 35 * MIN, 2),
        PendingHourRow(date, 11, 5 * MIN, 0),
        PendingHourRow(date, 12, 0, 1),
    )

    @Test
    fun summaryTotalsTheUnionAndRanksApps() {
        val s = UsageContent.build(date, "Europe/Madrid", apps, hours).summary
        assertEquals(date, s.local_date)
        assertEquals("Europe/Madrid", s.zone_id)
        assertEquals(40 * MIN, s.total_ms)
        assertEquals(3, s.unlocks)
        assertEquals(listOf(MAPS to 35 * MIN, CHAT to 10 * MIN), s.apps.map { it.package_name to it.foreground_ms })
    }

    @Test
    fun detailHasEachActiveHourWithAppsByName() {
        val d = UsageContent.build(date, "Europe/Madrid", apps, hours).detail
        assertEquals(listOf(10, 11, 12), d.hours.map { it.hour })
        assertEquals(listOf(CHAT to 10 * MIN, MAPS to 30 * MIN), d.hours[0].apps.map { it.package_name to it.foreground_ms })
        assertEquals(listOf(MAPS), d.hours[1].apps.map { it.package_name })
        assertEquals(listOf(2, 0, 1), d.hours.map { it.unlocks })
    }

    @Test
    fun hashIsStableAndFollowsContent() {
        val a = UsageContent.build(date, "Europe/Madrid", apps, hours).hash
        assertEquals(a, UsageContent.build(date, "Europe/Madrid", apps.reversed(), hours.reversed()).hash)
        assertNotEquals(a, UsageContent.build(date, "Europe/Madrid", apps.dropLast(2), hours).hash)
        assertNotEquals(a, UsageContent.build(date, "Asia/Tokyo", apps, hours).hash)
    }

    /**
     * A heavy but realistic day: 120 distinct apps, 18 active hours, 40 apps touched in each of them; and the worst
     * case, all 120 apps in every hour. The signed UsageDetail entry must fit one sync batch (SyncSession skips
     * entries above its 60,000-byte batch budget, itself under SecureChannel.MAX_PLAINTEXT).
     */
    @Test
    fun heavyDayDetailFitsOneSyncMessage() {
        val sizes = mapOf("realistic" to (18 to 40), "worst" to (24 to 120)).mapValues { (_, shape) ->
            val content = heavy(shape.first, shape.second)
            val keys = DeviceKeys.generate()
            val author = EntryAuthor(GroupGenesis.create(keys, "phone").groupId, keys, 1_000_000)
            author.create { it.copy(usage_detail = content.detail) }.bytes.size to
                author.create { it.copy(usage_summary = content.summary) }.bytes.size
        }
        println("heavy-day signed entry sizes (detail, summary): $sizes, limit ${SecureChannel.MAX_PLAINTEXT}")
        for ((detail, summary) in sizes.values) {
            assertTrue(detail <= Publisher.MAX_DETAIL_ENTRY_BYTES, "detail $detail")
            assertTrue(summary <= Publisher.MAX_DETAIL_ENTRY_BYTES, "summary $summary")
        }
        assertTrue(Publisher.MAX_DETAIL_ENTRY_BYTES < SecureChannel.MAX_PLAINTEXT)
    }

    @Test
    fun crowdedHourKeepsTopAppsAndFoldsTheExactRemainder() {
        val rows = heavyRows(24, 120)
        val content = UsageContent.build(date, "Europe/Madrid", rows, emptyList())
        assertEquals(120, content.summary.apps.size)
        assertEquals(24, content.detail.hours.size)
        for (bucket in content.detail.hours) {
            val raw = rows.filter { it.hour == bucket.hour }
            val top = raw.sortedWith(compareByDescending<PendingUsageRow> { it.foregroundMs }.thenBy { it.packageName })
                .take(UsageContent.MAX_APPS_PER_HOUR)
            assertEquals(UsageContent.MAX_APPS_PER_HOUR + 1, bucket.apps.size)
            assertEquals(top.map { it.packageName }.sorted(), bucket.apps.dropLast(1).map { it.package_name })
            val other = bucket.apps.last()
            assertEquals(UsageContent.OTHER_PACKAGE, other.package_name)
            assertEquals(raw.sumOf { it.foregroundMs } - top.sumOf { it.foregroundMs }, other.foreground_ms)
            assertEquals(raw.sumOf { it.foregroundMs }, bucket.apps.sumOf { it.foreground_ms })
        }
    }

    @Test
    fun capTiesAreBrokenByPackageName() {
        val rows = (0 until 42).map { PendingUsageRow(date, 9, "app.p%02d".format(it), MIN) }
        val a = UsageContent.build(date, "Europe/Madrid", rows, emptyList())
        val b = UsageContent.build(date, "Europe/Madrid", rows.shuffled(java.util.Random(7)), emptyList())
        assertEquals(a.hash, b.hash)
        val cells = a.detail.hours.single().apps
        assertEquals((0 until 40).map { "app.p%02d".format(it) } + UsageContent.OTHER_PACKAGE, cells.map { it.package_name })
        assertEquals(2 * MIN, cells.last().foreground_ms)
    }

    @Test
    fun hourAtTheCapHasNoOther() {
        val rows = (0 until 40).map { PendingUsageRow(date, 9, "app.p%02d".format(it), MIN + it) }
        val cells = UsageContent.build(date, "Europe/Madrid", rows, emptyList()).detail.hours.single().apps
        assertEquals(40, cells.size)
        assertTrue(cells.none { it.package_name == UsageContent.OTHER_PACKAGE })
    }

    private fun heavyRows(activeHours: Int, appsPerHour: Int) = (0 until activeHours).flatMap { h ->
        (0 until appsPerHour).map { i ->
            PendingUsageRow(date, h, "com.vendor${(h * 7 + i) % 120}.application.mobile", 59 * MIN + h * 997 + i)
        }
    }

    private fun heavy(activeHours: Int, appsPerHour: Int) = UsageContent.build(
        date,
        "America/Argentina/Buenos_Aires",
        heavyRows(activeHours, appsPerHour),
        (0 until activeHours).map { PendingHourRow(date, it, 59 * MIN, 12) },
    )

    @Test
    fun detailLimitIsTheSyncBatchBudget() {
        // SyncSession never sends an entry above its batch budget: a bigger detail would never leave the device.
        assertEquals(SyncSession.BATCH_BYTES, Publisher.MAX_DETAIL_ENTRY_BYTES)
    }
}
