package app.kinsight.core.usage

import app.kinsight.core.database.PendingHourRow
import app.kinsight.core.database.PendingUsageRow
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.sync.SecureChannel
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
     * A heavy but realistic day: 120 distinct apps, 18 active hours, 40 apps touched in each of them. The signed
     * UsageDetail entry must fit one sync batch (SyncSession skips entries above its 60,000-byte batch budget,
     * itself under SecureChannel.MAX_PLAINTEXT).
     */
    @Test
    fun heavyDayDetailFitsOneSyncMessage() {
        val sizes = mapOf("realistic" to (18 to 40), "worst" to (24 to 120)).mapValues { (_, shape) ->
            val (activeHours, appsPerHour) = shape
            val rows = (0 until activeHours).flatMap { h ->
                (0 until appsPerHour).map { i ->
                    PendingUsageRow(date, h, "com.vendor${(h * 7 + i) % 120}.application.mobile", 59 * MIN + h * 997 + i)
                }
            }
            val hourRows = (0 until activeHours).map { PendingHourRow(date, it, 59 * MIN, 12) }
            val content = UsageContent.build(date, "America/Argentina/Buenos_Aires", rows, hourRows)
            val keys = DeviceKeys.generate()
            val author = EntryAuthor(GroupGenesis.create(keys, "phone").groupId, keys, 1_000_000)
            author.create { it.copy(usage_detail = content.detail) }.bytes.size to
                author.create { it.copy(usage_summary = content.summary) }.bytes.size
        }
        println("heavy-day signed entry sizes (detail, summary): $sizes, limit ${SecureChannel.MAX_PLAINTEXT}")
        assertTrue(sizes.getValue("realistic").first <= SYNC_BATCH_BYTES)
        assertTrue(sizes.getValue("realistic").second <= SYNC_BATCH_BYTES)
        assertTrue(SYNC_BATCH_BYTES < SecureChannel.MAX_PLAINTEXT)
    }

    private companion object {
        /** SyncSession.BATCH_BYTES (private there): larger entries are never sent. */
        const val SYNC_BATCH_BYTES = 60_000
    }
}
