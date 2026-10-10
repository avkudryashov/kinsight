package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.StateEvent
import java.time.LocalDate
import java.time.temporal.WeekFields

/** What the screens call a device's role (spec §4.8); unspecified reads as a parent. */
enum class Role { PARENT, CHILD }

fun DeviceRole.toRole(): Role = if (this == DeviceRole.CHILD) Role.CHILD else Role.PARENT

fun Role.toDeviceRole(): DeviceRole = if (this == Role.CHILD) DeviceRole.CHILD else DeviceRole.PARENT

/** A member as the screens show it. [lastSeenMs]: direct session or freshest entry, whichever is later. */
data class MemberView(
    val id: DeviceId,
    val name: String,
    val role: Role,
    val isAdmin: Boolean,
    val isSelf: Boolean,
    val lastSeenMs: Long?,
)

/**
 * One day of a child. [known]: a summary exists for the date (a day after the viewer's today never is, it is drawn
 * empty); a day that is not known reads as «no data», not as zero (spec §9.3, stage 5a).
 */
data class DayTotal(
    val date: LocalDate,
    val totalMs: Long,
    val unlocks: Int,
    val hasData: Boolean = totalMs > 0 || unlocks > 0,
    val known: Boolean = hasData,
)

/** One app over a period; [label] falls back to the package name (spec §7: invisible packages). */
class AppUsage(val packageName: String, val label: String, val icon: ByteArray?, val totalMs: Long)

enum class Period { DAY, WEEK, MONTH }

/**
 * The dates a detail screen shows, [start] to [end] inclusive (spec §9.3, stage 5a): a DAY is any date, a WEEK the
 * calendar week whose first day follows the locale ([WeekFields]), a MONTH the calendar month. [previous] and [next]
 * step one whole period; bounds (oldest summary, today) are the read model's business, not the range's.
 */
data class DetailRange(val period: Period, val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "range ends before it starts: $start..$end" }
    }

    fun previous(): DetailRange = when (period) {
        Period.DAY -> DetailRange(period, start.minusDays(1), end.minusDays(1))
        Period.WEEK -> DetailRange(period, start.minusWeeks(1), end.minusWeeks(1))
        Period.MONTH -> month(start.minusMonths(1))
    }

    fun next(): DetailRange = when (period) {
        Period.DAY -> DetailRange(period, start.plusDays(1), end.plusDays(1))
        Period.WEEK -> DetailRange(period, start.plusWeeks(1), end.plusWeeks(1))
        Period.MONTH -> month(start.plusMonths(1))
    }

    /** Every date of the range, oldest first. */
    fun dates(): List<LocalDate> = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()

    companion object {
        /** The [period] containing [anchor]; [weekFields] is `WeekFields.of(Locale.getDefault())` on a phone. */
        fun of(period: Period, anchor: LocalDate, weekFields: WeekFields): DetailRange = when (period) {
            Period.DAY -> DetailRange(period, anchor, anchor)
            Period.WEEK -> anchor.with(weekFields.dayOfWeek(), 1).let { DetailRange(period, it, it.plusDays(6)) }
            Period.MONTH -> month(anchor)
        }

        private fun month(anchor: LocalDate): DetailRange =
            DetailRange(Period.MONTH, anchor.withDayOfMonth(1), anchor.withDayOfMonth(anchor.lengthOfMonth()))
    }
}

/**
 * One local clock hour (0..23) of a day's UsageDetail, as the child's clock read it: on a fall-back day both runs of the
 * repeated hour are summed here, on a spring-forward day the skipped hour is zero. [totalMs] sums [byCategory].
 */
class HourTotal(val hour: Int, val totalMs: Long, val unlocks: Int, val byCategory: Map<AppCategory, Long>)

/**
 * Level 4 of a week or month: [cells] is [weekday][hour], weekday 0 being the locale's first day of week, each cell the
 * mean over the covered days of that weekday (a weekday with none is 0). [unlocks]: per hour, the mean over all
 * [coveredDays]. [coveredDays]: days of the range with a usable UsageDetail; [rangeDays]: days of the range up to the
 * viewer's today (a future day cannot have data).
 */
class Heatmap(val cells: Array<LongArray>, val unlocks: IntArray, val coveredDays: Int, val rangeDays: Int)

/** A child's sharing as the group sees it, from its own signed events. */
sealed interface Sharing {
    data object On : Sharing

    data class Paused(val sinceMs: Long) : Sharing

    /** Never resumed: joined without consent, or no SHARING_* has arrived yet. */
    data object Never : Sharing

    data object Left : Sharing
}

data class EventView(
    val author: DeviceId,
    val seq: Long,
    val kind: StateEvent.Kind,
    val fromMs: Long,
    val toMs: Long,
    val createdMs: Long,
    val shiftMs: Long,
    /** The author's first SHARING_* event, and a resume: sharing began here (consent), it was not resumed. */
    val firstSharing: Boolean = false,
) {
    /** When it happened, the same for the event list and the alert: an interval's start, else the signing time. */
    val atMs: Long get() = if (fromMs > 0) fromMs else createdMs
}

/** A dashboard card: today, the last 7 days (oldest first), the mean of the 7 days before today. */
data class DeviceUsage(
    val member: MemberView,
    val today: DayTotal,
    val week: List<DayTotal>,
    val averageMs: Long?,
    val sharing: Sharing,
    val updatedMs: Long?,
)

/**
 * Levels 1 to 3 of spec §9.3 for one [range] (every date of it in [days], oldest first), and the device's events of
 * the range (newest first). [canGoBack]: a summary older than the range exists; [canGoForward]: the range ends before
 * the viewer's today.
 */
data class DeviceDetail(
    val member: MemberView,
    val range: DetailRange,
    val days: List<DayTotal>,
    val totalMs: Long,
    val unlocks: Int,
    val apps: List<AppUsage>,
    val events: List<EventView>,
    val sharing: Sharing,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** Level 3: the range's time per category, largest first, zero omitted; sums to [totalMs]. */
    val categories: List<CategoryTotal> = emptyList(),
    /** Level 3: one stack per entry of [days] (same order); empty for a day with no time; each sums to its day total. */
    val dayCategories: List<Map<AppCategory, Long>> = emptyList(),
    /** Level 4 of a DAY: 24 clock hours; null when the date has no usable UsageDetail (none, or not newer than its summary). */
    val hours: List<HourTotal>? = null,
    /** Level 4 of a WEEK or MONTH: weekday × hour averages; null when no day of the range has a usable UsageDetail. */
    val heatmap: Heatmap? = null,
    /**
     * The child's today, the bound of forward navigation: the viewer's date, or the next one when the child's zone is
     * already there (UsageReadModel.childToday); null when not given (screen tests), the screen then uses the viewer's.
     */
    val today: LocalDate? = null,
) {
    val period: Period get() = range.period
}
