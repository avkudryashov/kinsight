package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.StateEvent
import java.time.LocalDate

/**
 * Pure views over signed entries for the v1 screens. Only UsageSummary is read (levels 1 and 2 of spec §9.3);
 * UsageDetail is not: levels 3 and 4 must take a day's detail only when its seq is above the summary's seq of the
 * same date, never a detail older than the summary (stage 3b review).
 */
object UsageReadModel {
    const val MAX_EVENTS = 20

    /** Per author and local date, the summary with the highest seq (a malformed date is skipped). */
    fun summaries(entries: List<DecodedEntry>): Map<DeviceId, Map<LocalDate, DecodedEntry>> =
        entries.filter { it.kind == EntryKind.USAGE_SUMMARY && it.usageDate != null }
            .groupBy { it.author }
            .mapValues { (_, own) -> own.groupBy { it.usageDate!! }.mapValues { (_, same) -> same.maxBy { it.seq } } }

    fun events(entries: List<DecodedEntry>): List<EventView> = entries.mapNotNull { e ->
        e.body.state_event?.let { EventView(e.author, e.seq, it.kind, it.from_ms, it.to_ms, e.body.created_at_ms, it.clock_shift_ms) }
    }

    fun catalogOf(entries: List<DecodedEntry>, author: DeviceId): Map<String, AppCatalog> =
        entries.filter { it.author == author }.sortedBy { it.seq }.mapNotNull { it.body.app_catalog }.associateBy { it.package_name }

    /** [n] days ending with [today], oldest first; a day without a summary is zero. */
    fun days(byDate: Map<LocalDate, DecodedEntry>?, today: LocalDate, n: Int): List<DayTotal> = (n - 1 downTo 0).map { back ->
        val date = today.minusDays(back.toLong())
        val s = byDate?.get(date)?.body?.usage_summary
        DayTotal(date, s?.total_ms ?: 0L, s?.unlocks ?: 0)
    }

    /** Mean of the [n] days before today that have a summary; null when none has. */
    fun average(byDate: Map<LocalDate, DecodedEntry>?, today: LocalDate, n: Int = 7): Long? {
        val prior = (1..n).mapNotNull { byDate?.get(today.minusDays(it.toLong()))?.body?.usage_summary?.total_ms }
        return if (prior.isEmpty()) null else prior.sum() / prior.size
    }

    /** From the author's own events; leaving (or being departed) wins. */
    fun sharing(events: List<EventView>, departed: Boolean): Sharing {
        if (departed || events.any { it.kind == StateEvent.Kind.LEFT_GROUP }) return Sharing.Left
        val last = events.filter { it.kind == StateEvent.Kind.SHARING_PAUSED || it.kind == StateEvent.Kind.SHARING_RESUMED }
            .maxByOrNull { it.seq } ?: return Sharing.Never
        return if (last.kind == StateEvent.Kind.SHARING_RESUMED) Sharing.On else Sharing.Paused(last.createdMs)
    }

    /** Apps summed over [dates], largest first (ties by package name). */
    fun apps(byDate: Map<LocalDate, DecodedEntry>?, dates: List<LocalDate>, catalog: Map<String, AppCatalog>): List<AppUsage> {
        val totals = HashMap<String, Long>()
        for (d in dates) byDate?.get(d)?.body?.usage_summary?.apps?.forEach { totals.merge(it.package_name, it.foreground_ms, Long::plus) }
        return totals.filterValues { it > 0 }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .map { (pkg, ms) ->
                val c = catalog[pkg]
                AppUsage(pkg, c?.label?.takeIf { it.isNotBlank() } ?: pkg, c?.icon_webp?.toByteArray()?.takeIf { it.isNotEmpty() }, ms)
            }
    }

    fun usage(
        member: MemberView,
        byDate: Map<LocalDate, DecodedEntry>?,
        events: List<EventView>,
        today: LocalDate,
        departed: Boolean = false,
    ): DeviceUsage {
        val week = days(byDate, today, 7)
        return DeviceUsage(member, week.last(), week, average(byDate, today), sharing(events, departed), byDate?.values?.maxOfOrNull { it.body.created_at_ms })
    }

    fun detail(
        member: MemberView,
        byDate: Map<LocalDate, DecodedEntry>?,
        catalog: Map<String, AppCatalog>,
        events: List<EventView>,
        today: LocalDate,
        period: Period,
        departed: Boolean = false,
    ): DeviceDetail {
        val days = days(byDate, today, period.days)
        return DeviceDetail(
            member = member,
            period = period,
            days = days,
            totalMs = days.sumOf { it.totalMs },
            unlocks = days.sumOf { it.unlocks },
            apps = apps(byDate, days.map { it.date }, catalog),
            events = events.sortedByDescending { it.seq }.take(MAX_EVENTS),
            sharing = sharing(events, departed),
        )
    }
}
