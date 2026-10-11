package app.kinsight.core.database

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.model.proto.HourBucket
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.proto.UsageDetail
import app.kinsight.core.model.proto.UsageSummary
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A year of three children (audit A-5, A-6): what Snapshots.load reads on every change of the log stays under
 * [BUDGET_MS] on the JVM host. The budget is deliberately generous (cold JIT, a busy machine): it catches a return to
 * decoding the whole table per read or an unpruned event history growing without bound, not a few percent. The time
 * measured on the development laptop goes into this task's report.
 */
class SnapshotReadBudgetTest {
    private companion object {
        const val BUDGET_MS = 2_000L
        const val MIN = 60_000L
        const val HOUR = 3_600_000L
        val APPS = (1..20).map { "com.example.app$it" }
    }

    private val dir = Files.createTempDirectory("kinsight-budget").toFile()
    private val db = KinsightDatabaseFactory.open(HostContext(), File(dir, "budget.db").absolutePath)

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun yearOf(a: EntryAuthor, setClock: (Long) -> Unit, start: LocalDate): List<DecodedEntry> = buildList {
        for (d in 0 until 365) {
            val date = start.plusDays(d.toLong())
            val evening = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 20 * HOUR
            setClock(evening)
            add(a.create { it.copy(usage_summary = UsageSummary(local_date = "$date", zone_id = "UTC", total_ms = 3 * HOUR, apps = APPS.map { p -> AppTotal(p, 9 * MIN) }, unlocks = 40)) })
            if (d >= 365 - 90) {
                val hours = (8 until 22).map { h -> HourBucket(hour = h, apps = APPS.take(8).map { p -> AppTotal(p, 4 * MIN) }, unlocks = 3) }
                add(a.create { it.copy(usage_detail = UsageDetail(local_date = "$date", zone_id = "UTC", hours = hours)) })
            }
            repeat(4) { i ->
                val kind = if (i % 2 == 0) StateEvent.Kind.BLUETOOTH_OFF else StateEvent.Kind.SHARING_PAUSED
                add(a.create { it.copy(state_event = StateEvent(kind = kind, from_ms = evening, to_ms = evening + MIN)) })
            }
        }
        for (p in APPS) add(a.create { it.copy(app_catalog = AppCatalog(package_name = p, label = p, icon_webp = ByteArray(3_000) { 7 }.toByteString(), category = 1)) })
        add(a.create { it.copy(heartbeat = Heartbeat(app_version = "0.8.0", protocol_version = 2)) })
    }

    private suspend fun reads(store: RoomEntryStore): Int =
        store.membershipEntries().size + store.summaries().size + store.catalog().size + store.stateEvents().size +
            store.heartbeats().size + store.categoryOverrides().size

    @Test
    fun aYearOfThreeChildrenReloadsWithinTheBudget() = runTest {
        val parent = DeviceKeys.generate()
        val genesis = GroupGenesis.create(parent, "Mom")
        val store = RoomEntryStore(db, genesis.groupId)
        store.applyBatch(listOf(genesis.entry))
        val start = LocalDate.of(2025, 10, 10)
        repeat(3) {
            var t = 0L
            val kid = EntryAuthor(genesis.groupId, DeviceKeys.generate(), lastSeq = 0, clock = { t })
            store.applyBatch(yearOf(kid, { t = it }, start))
        }
        val lastEvening = start.plusDays(364).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 21 * HOUR
        store.pruneHistory(summaryRetentionDays = 365, nowMs = lastEvening, self = parent.deviceId, zone = ZoneOffset.UTC)

        assertEquals(1 + 3 * (365 + 20 + 365 * 4 + 1), reads(store)) // warm-up, and nothing within the year was pruned
        val ms = measureTimeMillis { repeat(3) { reads(store) } } / 3
        println("snapshot reads, a year of three children: $ms ms") // for the task report
        assertTrue(ms < BUDGET_MS, "snapshot reads took $ms ms, budget $BUDGET_MS ms")
    }
}
