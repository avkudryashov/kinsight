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
 *
 * Zone rule: `today` is the viewer's (the parent's) local date. A summary is keyed by the local_date its child wrote
 * in its own zone; days are matched by that date label, not by instant, so a child in another zone shows its own
 * calendar day under the parent's date of the same label.
 *
 * Peer numbers are untrusted: per day, total_ms and each app's foreground_ms read as 0..[MAX_MS_PER_DAY] and unlocks
 * as 0..[MAX_UNLOCKS_PER_DAY]; a summary's created_at_ms counts for "updated" at most [MAX_CLOCK_SKEW_MS] ahead of
 * now. After clamping no period sum can overflow.
 *
 * Only CHILD members' usage is surfaced (Play Stalkerware ruling): for any other member [usage] and [detail] show no
 * data whatever summaries exist. Events and sharing come only from the member's own signed events.
 */
object UsageReadModel {
    const val MAX_EVENTS = 20
    const val MAX_MS_PER_DAY = 86_400_000L
    const val MAX_UNLOCKS_PER_DAY = 10_000
    const val MAX_CLOCK_SKEW_MS = 5 * 60_000L

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
        DayTotal(date, clampMs(s?.total_ms ?: 0L), (s?.unlocks ?: 0).coerceIn(0, MAX_UNLOCKS_PER_DAY), hasData = s != null)
    }

    /** Mean of the [n] days before today that have a summary; null when none has. */
    fun average(byDate: Map<LocalDate, DecodedEntry>?, today: LocalDate, n: Int = 7): Long? {
        val prior = (1..n).mapNotNull { byDate?.get(today.minusDays(it.toLong()))?.body?.usage_summary?.total_ms?.let(::clampMs) }
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
        for (d in dates) {
            val day = HashMap<String, Long>()
            byDate?.get(d)?.body?.usage_summary?.apps?.forEach { day.merge(it.package_name, clampMs(it.foreground_ms), ::addClamped) }
            day.forEach { (pkg, ms) -> totals.merge(pkg, ms, Long::plus) }
        }
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
        nowMs: Long = System.currentTimeMillis(),
    ): DeviceUsage {
        val own = surfaced(member, byDate)
        val week = days(own, today, 7)
        val updated = own?.values?.maxOfOrNull { it.body.created_at_ms.coerceAtMost(nowMs + MAX_CLOCK_SKEW_MS) }
        val mine = events.filter { it.author == member.id }
        return DeviceUsage(member, week.last(), week, average(own, today), sharing(mine, departed), updated)
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
        val own = surfaced(member, byDate)
        val mine = events.filter { it.author == member.id }
        val days = days(own, today, period.days)
        return DeviceDetail(
            member = member,
            period = period,
            days = days,
            totalMs = days.sumOf { it.totalMs },
            unlocks = days.sumOf { it.unlocks },
            apps = apps(own, days.map { it.date }, catalog),
            events = mine.sortedByDescending { it.seq }.take(MAX_EVENTS),
            sharing = sharing(mine, departed),
        )
    }

    private fun surfaced(member: MemberView, byDate: Map<LocalDate, DecodedEntry>?) = byDate.takeIf { member.role == Role.CHILD }

    private fun clampMs(ms: Long) = ms.coerceIn(0L, MAX_MS_PER_DAY)

    private fun addClamped(a: Long, b: Long) = (a + b).coerceAtMost(MAX_MS_PER_DAY)
}
