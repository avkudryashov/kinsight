package app.kinsight.core.usage

import app.kinsight.core.database.PendingEventRow
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.sync.SyncSession
import app.kinsight.core.sync.observedLamport
import okio.ByteString.Companion.toByteString
import java.time.LocalDate

/** The group and identity entries are signed for; null until a group exists (stage 4). */
data class PublishTarget(val groupId: GroupId, val keys: DeviceKeys)

/**
 * What one [Publisher.publish] call signed. [oversized] lists dates whose UsageDetail entry would exceed
 * [Publisher.MAX_DETAIL_ENTRY_BYTES]: only their UsageSummary went out (local diagnostic, nothing is stored).
 */
data class PublishReport(
    val events: Int,
    val days: List<String>,
    val catalog: List<String>,
    val oversized: List<String> = emptyList(),
) {
    companion object {
        val NONE = PublishReport(0, emptyList(), emptyList())
    }
}

/**
 * Turns the local queue into signed entries when sharing is on (spec §5 «Переключатели», §7). Every entry is stored
 * through [RoomEntryStore.appendOwn] together with the queue bookkeeping it settles, and the EntryAuthor is rebuilt
 * from the stored author state on every call, so a failure never burns or repeats a seq. [onStateEvent] hears every
 * StateEvent once it is stored (UsagePipeline's own-integrity signal); it runs inside the pipeline lock, so it must
 * not block or call the pipeline.
 */
class Publisher(
    private val queue: RoomUsageQueue,
    private val store: RoomEntryStore,
    private val keys: DeviceKeys,
    private val apps: AppInfoSource,
    private val clock: () -> Long,
    private val today: () -> LocalDate,
    private val detailLimitBytes: Int = MAX_DETAIL_ENTRY_BYTES,
    private val onStateEvent: (StateEvent.Kind) -> Unit = {},
) {
    suspend fun publish(settings: UsageSettings, force: Boolean): PublishReport {
        if (!settings.sharing) return PublishReport.NONE
        var author = newAuthor()

        var events = 0
        for (row in queue.pendingEvents()) {
            val event = row.toStateEvent()
            val e = author.create { it.copy(state_event = event) }
            store.appendOwn(listOf(e)) { queue.deleteEvent(row.id) }
            onStateEvent(event.kind)
            events++
        }

        val now = clock()
        val last = queue.lastUsagePublishMs()
        val due = force || last == null || now < last || now - last >= MIN_INTERVAL_MS
        if (!due) return PublishReport(events, emptyList(), emptyList())

        val summaryHorizon = today().minusDays(settings.summaryRetentionDays.toLong())
        val detailHorizon = today().minusDays(settings.detailRetentionDays.toLong())
        val days = mutableListOf<String>()
        val oversized = mutableListOf<String>()
        val packages = sortedSetOf<String>()
        for (day in queue.changedDays()) {
            val usage = queue.dayUsage(day.date)
            val content = UsageContent.build(day.date, day.zoneId, usage.apps, usage.hours)
            val date = LocalDate.parse(day.date)
            val entries = mutableListOf<DecodedEntry>()
            if (content.hash != day.publishedHash) {
                val before = author.lastSeq to author.lastLamport
                if (!date.isBefore(summaryHorizon)) entries += author.create { it.copy(usage_summary = content.summary) }
                if (!date.isBefore(detailHorizon)) {
                    val detail = author.create { it.copy(usage_detail = content.detail) }
                    if (detail.bytes.size <= detailLimitBytes) {
                        entries += detail
                    } else {
                        // Sync would never send it: keep the summary only. Rewind the author so no seq is burned
                        // (the summary is re-signed with the same seq and Lamport value).
                        oversized += day.date
                        author = newAuthor(before.first, before.second)
                        entries.clear()
                        if (!date.isBefore(summaryHorizon)) entries += author.create { it.copy(usage_summary = content.summary) }
                    }
                }
            }
            if (entries.isEmpty()) {
                queue.markPublished(day.date, content.hash, day.version)
                continue
            }
            store.appendOwn(entries) { queue.markPublished(day.date, content.hash, day.version) }
            days += day.date
            content.summary.apps.mapTo(packages) { it.package_name }
        }
        if (days.isNotEmpty()) queue.setLastUsagePublishMs(now)

        val catalog = mutableListOf<String>()
        // This run's packages, then any package of an earlier published day that never got its catalog entry (a crash
        // after the day went out, or an app that was invisible then).
        for (pkg in packages + queue.packagesWithoutCatalog()) {
            val info = apps.info(pkg) ?: continue
            val hash = info.hash()
            if (queue.catalogHash(pkg) == hash) continue
            val e = author.create {
                it.copy(
                    app_catalog = AppCatalog(
                        package_name = pkg,
                        label = info.label,
                        icon_webp = info.iconWebp.toByteString(),
                        category = info.category,
                    ),
                )
            }
            store.appendOwn(listOf(e)) { queue.setCatalogHash(pkg, hash) }
            catalog += pkg
        }
        return PublishReport(events, days, catalog, oversized)
    }

    /** SHARING_PAUSED / SHARING_RESUMED: signed at once, whatever the sharing switch says (spec §5). */
    suspend fun recordSharingChange(sharing: Boolean) {
        val now = clock()
        val kind = if (sharing) StateEvent.Kind.SHARING_RESUMED else StateEvent.Kind.SHARING_PAUSED
        val e = newAuthor().create { it.copy(state_event = StateEvent(kind = kind, from_ms = now, to_ms = now)) }
        store.appendOwn(listOf(e))
        onStateEvent(kind)
    }

    private suspend fun newAuthor(): EntryAuthor {
        val state = store.authorState(keys.deviceId)
        return newAuthor(state.lastSeq, state.lastLamport)
    }

    private suspend fun newAuthor(lastSeq: Long, lastLamport: Long): EntryAuthor {
        val observed = store.observedLamport()
        return EntryAuthor(store.groupId, keys, lastSeq, clock, lastLamport) { observed }
    }

    companion object {
        /** At most one new UsageSummary/UsageDetail version per hour, except a forced run (spec §7). */
        const val MIN_INTERVAL_MS = 3_600_000L

        /**
         * Largest signed UsageDetail entry we publish: SyncSession.BATCH_BYTES. SyncSession never sends an entry
         * above its batch budget, so a bigger one would never leave this device.
         */
        const val MAX_DETAIL_ENTRY_BYTES = SyncSession.BATCH_BYTES
    }
}

internal fun PendingEventRow.toStateEvent() = StateEvent(
    kind = StateEvent.Kind.fromValue(kind) ?: StateEvent.Kind.KIND_UNSPECIFIED,
    from_ms = fromMs,
    to_ms = toMs,
    clock_shift_ms = clockShiftMs,
)
