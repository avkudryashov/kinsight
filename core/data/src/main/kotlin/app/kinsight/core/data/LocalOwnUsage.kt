package app.kinsight.core.data

import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.PendingDayRow
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.database.usageQueueChanged
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.EntryBody
import app.kinsight.core.model.proto.SignedEntry
import app.kinsight.core.usage.AppInfo
import app.kinsight.core.usage.AppInfoSource
import app.kinsight.core.usage.SettingsStore
import app.kinsight.core.usage.UsageContent
import app.kinsight.core.usage.ownTimeLocalAllowed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * A parent's own screen time as entries of its own (own time spec 2026-10-24), built in memory from the local queue
 * for one view: [summaries] and [details] per queue day, [catalog] for their apps. Never stored, never handed to sync.
 * [accessMissing]: usage access is not granted now, the card asks for it.
 */
class OwnLocal(
    val summaries: List<DecodedEntry>,
    val details: List<DecodedEntry>,
    val catalog: List<DecodedEntry>,
    val accessMissing: Boolean,
)

/**
 * Reads a parent's own screen time from the local queue (RoomUsageQueue, filled by UsagePipeline.collectLocal) and
 * shapes it like a child's published entries, so SnapshotViews shows it with no logic of its own. The entries carry an
 * all-zero signature (no peer would ever accept one) and seqs from [SEQ_BASE] up, far above any real seq of this
 * device, so they cannot collide with the log in the read model's maps. App labels and icons come from [apps], kept in
 * memory per package for the life of the process.
 */
class LocalOwnUsage(
    private val db: KinsightDatabase,
    private val settings: SettingsStore,
    private val apps: AppInfoSource,
    private val access: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val queue = RoomUsageQueue(db)
    private val infos = ConcurrentHashMap<String, AppInfo>()

    /** False while the switch is off; true at once and after every change of the local queue while it is on. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val changes: Flow<Boolean> = settings.settings.map { it.ownTimeLocal }.distinctUntilChanged().flatMapLatest { on ->
        if (on) db.usageQueueChanged().map { true } else flowOf(false)
    }

    /**
     * The queue days of [from]..[to] (plus the oldest queue day when it is earlier, so a screen knows it can go back) as
     * entries of [s]'s self. Null unless this phone is an active PARENT of [s] with the switch on.
     */
    suspend fun load(s: GroupSnapshot.Ready, from: LocalDate, to: LocalDate): OwnLocal? = withContext(io) {
        if (!settings.current().ownTimeLocal || !ownTimeLocalAllowed(s.membership, s.self)) return@withContext null
        val days = mutableListOf<PendingDayRow>()
        val first = queue.firstDay()
        if (first != null && first < from.toString()) days += queue.days(first, first)
        days += queue.days(from.toString(), to.toString())
        val at = queue.state()?.wallMs ?: clock()
        var seq = SEQ_BASE
        val summaries = mutableListOf<DecodedEntry>()
        val details = mutableListOf<DecodedEntry>()
        val packages = sortedSetOf<String>()
        for (day in days) {
            val usage = queue.dayUsage(day.date)
            val content = UsageContent.build(day.date, day.zoneId, usage.apps, usage.hours)
            summaries += entry(s, ++seq, at) { it.copy(usage_summary = content.summary) }
            details += entry(s, ++seq, at) { it.copy(usage_detail = content.detail) }
            content.summary.apps.mapTo(packages) { it.package_name }
        }
        val catalog = packages.mapNotNull { pkg ->
            val info = infos[pkg] ?: apps.info(pkg)?.also { infos[pkg] = it } ?: return@mapNotNull null
            entry(s, ++seq, at) {
                it.copy(app_catalog = AppCatalog(package_name = pkg, label = info.label, icon_webp = info.iconWebp.toByteString(), category = info.category))
            }
        }
        OwnLocal(summaries, details, catalog, accessMissing = !access())
    }

    private fun entry(s: GroupSnapshot.Ready, seq: Long, at: Long, fill: (EntryBody) -> EntryBody): DecodedEntry {
        val body = fill(
            EntryBody(
                group_id = s.groupId.bytes().toByteString(),
                author_id = s.self.bytes().toByteString(),
                seq = seq,
                created_at_ms = at,
            ),
        )
        return DecodedEntry.decode(SignedEntry.ADAPTER.encode(SignedEntry(body = EntryBody.ADAPTER.encode(body).toByteString(), signature = UNSIGNED)))
    }

    private companion object {
        /** Far above any seq a device reaches: the local entries order after everything of the log. */
        const val SEQ_BASE = 1L shl 50

        /** An all-zero signature: the right length for decoding, valid for no key. */
        val UNSIGNED: ByteString = ByteArray(64).toByteString()
    }
}
