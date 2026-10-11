package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.proto.UsageDetail
import app.kinsight.core.usage.HourSlot
import app.kinsight.core.usage.LocalHours
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Pure views over signed entries for the v1 screens. Totals (levels 1 to 3 of spec §9.3) come only from UsageSummary;
 * level 4 (hours) takes a day's UsageDetail only when its seq is above the summary's seq of the same date, never a
 * detail older than the summary (stage 3b review, [detailOf]).
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
 * data whatever summaries exist. Events and sharing come only from the member's own signed events. [detail] lists every own event of the period,
 * uncapped, so a flood of one kind cannot push an integrity event out of view; the screen bounds what it draws.
 */
object UsageReadModel {
    const val MAX_MS_PER_DAY = 86_400_000L
    const val MAX_UNLOCKS_PER_DAY = 10_000
    const val MAX_CLOCK_SKEW_MS = 5 * 60_000L

    /** Most a clock hour holds, one bucket or a whole slot: two runs of the hour on a fall-back day (spec §7). */
    const val MAX_MS_PER_HOUR_SLOT = 2 * 3_600_000L

    /** Per author and local date, the summary with the highest seq (a malformed date is skipped). */
    fun summaries(entries: List<DecodedEntry>): Map<DeviceId, Map<LocalDate, DecodedEntry>> = latest(entries, EntryKind.USAGE_SUMMARY)

    /** Per author and local date, the UsageDetail with the highest seq (a malformed date is skipped). */
    fun details(entries: List<DecodedEntry>): Map<DeviceId, Map<LocalDate, DecodedEntry>> = latest(entries, EntryKind.USAGE_DETAIL)

    private fun latest(entries: List<DecodedEntry>, kind: EntryKind): Map<DeviceId, Map<LocalDate, DecodedEntry>> =
        entries.filter { it.kind == kind && it.usageDate != null }
            .groupBy { it.author }
            .mapValues { (_, own) -> own.groupBy { it.usageDate!! }.mapValues { (_, same) -> same.maxBy { it.seq } } }

    /**
     * The UsageDetail of [date] usable for level 4: only when the date has a summary and the detail's seq is above it
     * (a detail not newer than the summary describes an older state of the day); else null.
     */
    fun detailOf(summaries: Map<LocalDate, DecodedEntry>?, details: Map<LocalDate, DecodedEntry>?, date: LocalDate): UsageDetail? {
        val summary = summaries?.get(date) ?: return null
        val detail = details?.get(date)?.takeIf { it.seq > summary.seq } ?: return null
        return detail.body.usage_detail
    }

    /**
     * 24 clock hours of [detail] (spec §7: hours are the child's local clock hours). A missing hour is 0, buckets of the
     * same hour are summed (the publisher writes one bucket per hour; the repeated hour of a fall-back day can hold two
     * hours, a skipped spring-forward hour has none); a bucket outside 0..23 is dropped. Each slot holds at most the
     * real length of that clock hour in the detail's zone ([slotLengths]: 1 h, 2 h for the repeated fall-back hour, 0 for
     * the skipped one); apps in the foreground together (split screen, picture in picture) can claim more, and the slot's
     * categories are then scaled down by one factor. Untrusted peer: each slot, however many buckets claim it, also stays
     * within [MAX_MS_PER_HOUR_SLOT] and holds at most [MAX_UNLOCKS_PER_DAY] unlocks.
     */
    fun hours(detail: UsageDetail, catalog: Map<String, AppCatalog>, overrides: Map<String, AppCategory>): List<HourTotal> {
        val cats = Array(24) { java.util.EnumMap<AppCategory, Long>(AppCategory::class.java) }
        val unlocks = IntArray(24)
        for (b in detail.hours) {
            val h = b.hour
            if (h !in 0..23) continue
            val bucket = java.util.EnumMap<AppCategory, Long>(AppCategory::class.java)
            for (a in b.apps) {
                val ms = a.foreground_ms.coerceIn(0L, MAX_MS_PER_HOUR_SLOT)
                if (ms > 0) bucket.merge(Categories.of(a.package_name, catalog[a.package_name]?.category, overrides), ms, Long::plus)
            }
            val room = MAX_MS_PER_HOUR_SLOT - cats[h].values.sum()
            fitDown(bucket, room)
            bucket.forEach { (c, ms) -> cats[h].merge(c, ms, Long::plus) }
            unlocks[h] = (unlocks[h] + b.unlocks.coerceIn(0, MAX_UNLOCKS_PER_DAY)).coerceAtMost(MAX_UNLOCKS_PER_DAY)
        }
        val lengths = slotLengths(detail)
        return (0..23).map { h ->
            fitDown(cats[h], lengths[h])
            val byCategory = cats[h].filterValues { it > 0 }
            // A skipped spring-forward hour did not happen: no time, and no unlocks either.
            HourTotal(h, byCategory.values.sum(), if (lengths[h] == 0L) 0 else unlocks[h], byCategory)
        }
    }

    /**
     * Weekday × hour means over the [dates] that have a usable detail (spec §9.3: average over days with hourly data);
     * null when none has. Rows follow [weekFields]' first day of week.
     */
    fun heatmap(
        summaries: Map<LocalDate, DecodedEntry>?,
        details: Map<LocalDate, DecodedEntry>?,
        dates: List<LocalDate>,
        today: LocalDate,
        catalog: Map<String, AppCatalog>,
        overrides: Map<String, AppCategory>,
        weekFields: WeekFields,
    ): Heatmap? {
        val past = dates.filter { !it.isAfter(today) }
        val sums = Array(7) { LongArray(24) }
        val perWeekday = IntArray(7)
        val unlockSums = LongArray(24)
        var covered = 0
        for (date in past) {
            val detail = detailOf(summaries, details, date) ?: continue
            val row = (date.dayOfWeek.value - weekFields.firstDayOfWeek.value + 7) % 7
            perWeekday[row]++
            covered++
            hours(detail, catalog, overrides).forEach {
                sums[row][it.hour] += it.totalMs
                unlockSums[it.hour] += it.unlocks.toLong()
            }
        }
        if (covered == 0) return null
        val cells = Array(7) { row -> LongArray(24) { h -> if (perWeekday[row] == 0) 0L else sums[row][h] / perWeekday[row] } }
        val unlocks = IntArray(24) { h -> Math.round(unlockSums[h].toDouble() / covered).toInt() }
        return Heatmap(cells, unlocks, covered, past.size, weekFields.firstDayOfWeek)
    }

    /**
     * The real length of each clock hour 0..23 of [detail]'s local date in its zone (LocalHours.lengthMs), within
     * [MAX_MS_PER_HOUR_SLOT]; every hour [MAX_MS_PER_HOUR_SLOT] when the date or the zone does not parse (untrusted peer).
     */
    private fun slotLengths(detail: UsageDetail): LongArray {
        val date = runCatching { LocalDate.parse(detail.local_date) }.getOrNull()
        val zone = runCatching { ZoneId.of(detail.zone_id) }.getOrNull()
        if (date == null || zone == null) return LongArray(24) { MAX_MS_PER_HOUR_SLOT }
        return LongArray(24) { h -> LocalHours.lengthMs(HourSlot(date, h), zone).coerceIn(0L, MAX_MS_PER_HOUR_SLOT) }
    }

    /** Scales [cats] down so it sums to at most [cap] (≥ 0), the rounding residue going to the largest entry. */
    private fun fitDown(cats: MutableMap<AppCategory, Long>, cap: Long) {
        val sum = cats.values.sum()
        val total = cap.coerceAtLeast(0L)
        if (sum <= total) return
        for (e in cats.entries) e.setValue((e.value.toDouble() * total / sum).toLong())
        val largest = cats.entries.maxBy { it.value }
        largest.setValue(largest.value + total - cats.values.sum())
    }

    fun events(entries: List<DecodedEntry>): List<EventView> {
        val sharingKinds = setOf(StateEvent.Kind.SHARING_PAUSED, StateEvent.Kind.SHARING_RESUMED)
        val firstSharing = entries.filter { it.body.state_event?.kind in sharingKinds }
            .groupBy { it.author }
            .mapValues { (_, own) -> own.minBy { it.seq }.seq }
        return entries.mapNotNull { e ->
            e.body.state_event?.let {
                val first = it.kind == StateEvent.Kind.SHARING_RESUMED && firstSharing[e.author] == e.seq
                EventView(e.author, e.seq, it.kind, it.from_ms, it.to_ms, e.body.created_at_ms, it.clock_shift_ms, first)
            }
        }
    }

    fun catalogOf(entries: List<DecodedEntry>, author: DeviceId): Map<String, AppCatalog> =
        entries.filter { it.author == author }.sortedBy { it.seq }.mapNotNull { it.body.app_catalog }.associateBy { it.package_name }

    /** [n] days ending with [today], oldest first; a day without a summary is zero. */
    fun days(byDate: Map<LocalDate, DecodedEntry>?, today: LocalDate, n: Int): List<DayTotal> =
        days(byDate, (n - 1 downTo 0).map { today.minusDays(it.toLong()) }, today)

    /**
     * One [DayTotal] per date; a day without a summary is zero and not known. A day after [today] is zero and not
     * known whatever a clock-ahead child already wrote for it.
     */
    fun days(byDate: Map<LocalDate, DecodedEntry>?, dates: List<LocalDate>, today: LocalDate): List<DayTotal> = dates.map { date ->
        val s = byDate?.get(date)?.body?.usage_summary?.takeUnless { date.isAfter(today) }
        DayTotal(date, clampMs(s?.total_ms ?: 0L), (s?.unlocks ?: 0).coerceIn(0, MAX_UNLOCKS_PER_DAY), hasData = s != null, known = s != null)
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
        for (d in dates) dayApps(byDate, d).forEach { (pkg, ms) -> totals.merge(pkg, ms, Long::plus) }
        return totals.filterValues { it > 0 }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .map { (pkg, ms) ->
                val c = catalog[pkg]
                AppUsage(pkg, c?.label?.takeIf { it.isNotBlank() } ?: pkg, c?.icon_webp?.toByteArray()?.takeIf { it.isNotEmpty() }, ms)
            }
    }

    /** One day's apps, a package listed twice merged; each clamped to a day. */
    private fun dayApps(byDate: Map<LocalDate, DecodedEntry>?, date: LocalDate): Map<String, Long> {
        val day = HashMap<String, Long>()
        byDate?.get(date)?.body?.usage_summary?.apps?.forEach { day.merge(it.package_name, clampMs(it.foreground_ms), ::addClamped) }
        return day
    }

    /**
     * Level 3 stack of [day] from its summary's app totals (spec §9.3, stage 5a), summing exactly to the day total: time
     * no app accounts for (rounding, apps beyond the summary's list) goes to Other; apps claiming more than the day
     * (untrusted peer) are scaled down to it, the rounding residue going to the largest category. Zero is omitted.
     */
    fun dayCategories(
        byDate: Map<LocalDate, DecodedEntry>?,
        day: DayTotal,
        catalog: Map<String, AppCatalog>,
        overrides: Map<String, AppCategory>,
    ): Map<AppCategory, Long> {
        if (!day.known || day.totalMs <= 0) return emptyMap()
        val cats = java.util.EnumMap<AppCategory, Long>(AppCategory::class.java)
        dayApps(byDate, day.date).forEach { (pkg, ms) ->
            if (ms > 0) cats.merge(Categories.of(pkg, catalog[pkg]?.category, overrides), ms, Long::plus)
        }
        val sum = cats.values.sum()
        val total = day.totalMs
        if (sum < total) cats.merge(AppCategory.OTHER, total - sum, Long::plus) else fitDown(cats, total)
        return cats.filterValues { it > 0 }
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
        // The child's own day (spec §9.3), as on the detail screen: it can already be tomorrow for the viewer.
        @Suppress("NAME_SHADOWING")
        val today = childToday(own, today, nowMs)
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
        range: DetailRange,
        today: LocalDate,
        departed: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
        overrides: Map<String, AppCategory> = emptyMap(),
        details: Map<LocalDate, DecodedEntry>? = null,
        weekFields: WeekFields = WeekFields.of(Locale.getDefault()),
        nowMs: Long? = null,
    ): DeviceDetail {
        val own = surfaced(member, byDate)
        val ownDetails = surfaced(member, details)
        @Suppress("NAME_SHADOWING")
        val today = childToday(own, today, nowMs)
        val mine = events.filter { it.author == member.id }
        val days = days(own, range.dates(), today)
        val dayCategories = days.map { dayCategories(own, it, catalog, overrides) }
        val categoryTotals = java.util.EnumMap<AppCategory, Long>(AppCategory::class.java)
        dayCategories.forEach { d -> d.forEach { (c, ms) -> categoryTotals.merge(c, ms, Long::plus) } }
        val start = range.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = range.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return DeviceDetail(
            member = member,
            range = range,
            days = days,
            totalMs = days.sumOf { it.totalMs },
            unlocks = days.sumOf { it.unlocks },
            apps = apps(own, days.filter { it.known }.map { it.date }, catalog),
            events = mine.filter { inWindow(it, start, end) }.sortedByDescending { it.seq },
            sharing = sharing(mine, departed),
            canGoBack = own?.keys?.any { it.isBefore(range.start) } == true,
            canGoForward = range.end.isBefore(today),
            categories = categoryTotals.entries.filter { it.value > 0 }
                .sortedWith(compareByDescending<Map.Entry<AppCategory, Long>> { it.value }.thenBy { it.key.ordinal })
                .map { CategoryTotal(it.key, it.value) },
            dayCategories = dayCategories,
            hours = if (range.period == Period.DAY) {
                detailOf(own, ownDetails, range.start)?.takeUnless { range.start.isAfter(today) }?.let { hours(it, catalog, overrides) }
            } else {
                null
            },
            heatmap = if (range.period == Period.DAY) null else heatmap(own, ownDetails, range.dates(), today, catalog, overrides, weekFields),
            today = today,
        )
    }

    /** [detail]'s app list alone (the «All apps» screen): the same days, bounds and apps, nothing else computed. */
    fun appList(
        member: MemberView,
        byDate: Map<LocalDate, DecodedEntry>?,
        catalog: Map<String, AppCatalog>,
        range: DetailRange,
        today: LocalDate,
        nowMs: Long? = null,
    ): AppList {
        val own = surfaced(member, byDate)
        @Suppress("NAME_SHADOWING")
        val today = childToday(own, today, nowMs)
        val known = days(own, range.dates(), today).filter { it.known }.map { it.date }
        return AppList(member, range, apps(own, known, catalog), today)
    }

    /**
     * One app [packageName] of [member] over [range] (the app screen, spec §9.3, stage 5a), from the same summaries,
     * details and bounds as [detail]: per-day time from each date's summary (clamped to a day), the DAY's clock hours
     * from a usable UsageDetail (each slot cut to the hour's real length, as in [hours]), the place in [apps] of the range.
     * [viewerIsParent]: only a parent may change a category (a child's own phone never offers it), never of `~other`.
     */
    fun app(
        member: MemberView,
        byDate: Map<LocalDate, DecodedEntry>?,
        details: Map<LocalDate, DecodedEntry>?,
        catalog: Map<String, AppCatalog>,
        packageName: String,
        range: DetailRange,
        today: LocalDate,
        overrides: Map<String, AppCategory>,
        viewerIsParent: Boolean = false,
        nowMs: Long? = null,
        active: Boolean = true,
    ): AppDetail {
        val own = surfaced(member, byDate)
        val ownDetails = surfaced(member, details)
        @Suppress("NAME_SHADOWING")
        val today = childToday(own, today, nowMs)
        val days = days(own, range.dates(), today).map { d ->
            val ms = if (d.known) dayApps(own, d.date)[packageName] ?: 0L else 0L
            DayTotal(d.date, ms, 0, hasData = ms > 0, known = d.known)
        }
        val top = apps(own, days.filter { it.known }.map { it.date }, catalog)
        val hours = if (range.period == Period.DAY && !range.start.isAfter(today)) {
            detailOf(own, ownDetails, range.start)?.let { appHours(it, packageName) }
        } else {
            null
        }
        val remainder = packageName == Categories.REMAINDER_PACKAGE
        val c = catalog[packageName]
        return AppDetail(
            member = member,
            packageName = packageName,
            range = range,
            label = c?.label?.takeIf { it.isNotBlank() } ?: packageName,
            icon = c?.icon_webp?.toByteArray()?.takeIf { it.isNotEmpty() },
            category = Categories.of(packageName, c?.category, overrides),
            overridden = !remainder && overrides.containsKey(packageName),
            days = days,
            totalMs = days.sumOf { it.totalMs },
            hours = hours,
            // The ~other remainder is not an app: it neither has a place nor takes one from the apps below it.
            rank = top.filter { it.packageName != Categories.REMAINDER_PACKAGE }.indexOfFirst { it.packageName == packageName }
                .takeIf { it >= 0 }?.plus(1),
            canChangeCategory = viewerIsParent && !remainder,
            canGoBack = own?.keys?.any { it.isBefore(range.start) } == true,
            canGoForward = range.end.isBefore(today),
            today = today,
            defaultCategory = Categories.of(packageName, c?.category, emptyMap()),
            active = active,
        )
    }

    /**
     * 24 clock hours of one app in [detail]; buckets of the same hour summed. As in [hours], a slot whose apps together
     * claim more than the hour's real length ([slotLengths]) is scaled down, the app keeping its share of the hour.
     */
    private fun appHours(detail: UsageDetail, packageName: String): List<Long> {
        val slots = LongArray(24)
        val totals = LongArray(24)
        for (b in detail.hours) {
            if (b.hour !in 0..23) continue
            for (a in b.apps) {
                val ms = a.foreground_ms.coerceIn(0L, MAX_MS_PER_HOUR_SLOT)
                totals[b.hour] = (totals[b.hour] + ms).coerceAtMost(Long.MAX_VALUE / 2)
                if (a.package_name == packageName) slots[b.hour] += ms
            }
        }
        val lengths = slotLengths(detail)
        return (0..23).map { h ->
            val ms = slots[h].coerceAtMost(totals[h])
            if (totals[h] <= lengths[h]) ms else (ms.toDouble() * lengths[h] / totals[h]).toLong()
        }
    }

    /**
     * [member]'s today for the thresholds (spec §9.7, stage 5b): the summary of the child's actual date ([childToday]
     * with the same [today] and [nowMs], thresholds = true), categories as in [dayCategories] with the viewer's [overrides], apps as summed per
     * day (each clamped). Null when [member] is not a CHILD or has no summary for that date yet: never yesterday's
     * total read as today's.
     */
    fun todayUsage(
        member: MemberView,
        byDate: Map<LocalDate, DecodedEntry>?,
        catalog: Map<String, AppCatalog>,
        today: LocalDate,
        overrides: Map<String, AppCategory>,
        nowMs: Long?,
    ): TodayUsage? {
        val own = surfaced(member, byDate) ?: return null
        val date = childToday(own, today, nowMs, thresholds = true)
        val day = days(own, listOf(date), date).single().takeIf { it.known } ?: return null
        return TodayUsage(day.totalMs, dayCategories(own, day, catalog, overrides), dayApps(own, date).filterValues { it > 0 })
    }

    /**
     * The child's own date at [nowMs], from the zone of its latest summary (highest seq), when that is later than the
     * viewer's [today]: a child in a zone ahead of the parent can already be on the next day (Review Focus 3). Never
     * earlier than [today] and at most one day later (zone offsets span 26 h); an unparsable zone (untrusted peer), no
     * summary or a null [nowMs] keeps [today].
     *
     * [thresholds]: the child's actual date, also one day earlier than [today] (final fix item 7): a child west of the
     * parent is still on its day after the parent's midnight, and a limit it passes in its evening must be caught.
     * The screens keep the later of the two (the detail and the dashboard never open on the parent's yesterday).
     */
    fun childToday(byDate: Map<LocalDate, DecodedEntry>?, today: LocalDate, nowMs: Long?, thresholds: Boolean = false): LocalDate {
        if (nowMs == null) return today
        val zoneId = byDate?.values?.maxByOrNull { it.seq }?.body?.usage_summary?.zone_id ?: return today
        val zone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return today
        val theirs = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return theirs.coerceIn(if (thresholds) today.minusDays(1) else today, today.plusDays(1))
    }

    /**
     * Whether [e] belongs to the window [start, end) of the viewer's days: it was created in it, or its interval
     * (from_ms to to_ms; to_ms missing or before from_ms reads as the instant from_ms) reaches into it.
     */
    private fun inWindow(e: EventView, start: Long, end: Long): Boolean {
        if (e.createdMs in start until end) return true
        if (e.fromMs <= 0) return false
        return e.fromMs < end && maxOf(e.toMs, e.fromMs) >= start
    }

    private fun surfaced(member: MemberView, byDate: Map<LocalDate, DecodedEntry>?) = byDate.takeIf { member.role == Role.CHILD }

    private fun clampMs(ms: Long) = ms.coerceIn(0L, MAX_MS_PER_DAY)

    private fun addClamped(a: Long, b: Long) = (a + b).coerceAtMost(MAX_MS_PER_DAY)
}
