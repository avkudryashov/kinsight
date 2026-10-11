package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.StateEvent
import java.time.DayOfWeek
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

/** One app over a period; [label] falls back to the package name (spec §7: invisible packages). Equal by content. */
class AppUsage(val packageName: String, val label: String, val icon: ByteArray?, val totalMs: Long) {
    override fun equals(other: Any?): Boolean = other is AppUsage && packageName == other.packageName && label == other.label &&
        totalMs == other.totalMs && icon.contentEquals(other.icon)

    override fun hashCode(): Int = ((packageName.hashCode() * 31 + label.hashCode()) * 31 + totalMs.hashCode()) * 31 + icon.contentHashCode()

    override fun toString(): String = "AppUsage($packageName, $label, $totalMs)"
}

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
data class HourTotal(val hour: Int, val totalMs: Long, val unlocks: Int, val byCategory: Map<AppCategory, Long>)

/**
 * Level 4 of a week or month: [cells] is [weekday][hour], weekday 0 being the locale's first day of week, each cell the
 * mean over the covered days of that weekday (a weekday with none is 0). [unlocks]: per hour, the mean over all
 * [coveredDays]. [coveredDays]: days of the range with a usable UsageDetail; [rangeDays]: days of the range up to the
 * viewer's today (a future day cannot have data).
 *
 * [firstDayOfWeek]: the weekday of row 0, from the same WeekFields the rows were built with, so the screen names the
 * rows by the data rather than by its own locale.
 */
class Heatmap(
    val cells: Array<LongArray>,
    val unlocks: IntArray,
    val coveredDays: Int,
    val rangeDays: Int,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
) {
    /** By content, so an identical heatmap does not redraw the screen. */
    override fun equals(other: Any?): Boolean = other is Heatmap && coveredDays == other.coveredDays && rangeDays == other.rangeDays &&
        firstDayOfWeek == other.firstDayOfWeek && cells.contentDeepEquals(other.cells) && unlocks.contentEquals(other.unlocks)

    override fun hashCode(): Int =
        (((cells.contentDeepHashCode() * 31 + unlocks.contentHashCode()) * 31 + coveredDays) * 31 + rangeDays) * 31 + firstDayOfWeek.hashCode()

    override fun toString(): String = "Heatmap(covered=$coveredDays/$rangeDays, first=$firstDayOfWeek)"
}

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
    /** Sharing is on but the child's phone does not collect (UsageReadModel.collectionOff, U1). */
    val collectionOff: Boolean = false,
)

/**
 * Levels 1 to 3 of spec §9.3 for one [range] (every date of it in [days], oldest first), and the device's events of
 * the range (newest first). [canGoBack]: a summary older than the range exists; [canGoForward]: the range ends before
 * the viewer's today.
 */
data class DeviceDetail(
    val member: MemberView,
    override val range: DetailRange,
    override val days: List<DayTotal>,
    val totalMs: Long,
    val unlocks: Int,
    val apps: List<AppUsage>,
    val events: List<EventView>,
    val sharing: Sharing,
    override val canGoBack: Boolean = false,
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
    override val today: LocalDate? = null,
) : RangedView {
    val period: Period get() = range.period
}

/**
 * Every app of a child over [range], largest first (the «All apps» screen): what [DeviceDetail.apps] holds for the same
 * range, without the detail's UsageDetail query, categories or heatmap. [today]: the child's today, as in the detail.
 */
data class AppList(val member: MemberView, val range: DetailRange, val apps: List<AppUsage>, val today: LocalDate?)

/**
 * What a screen with period navigation reads to move its range: [days] of [range] (a day is known when it has a
 * summary), [canGoBack] while an older summary exists, [today] the child's today (null: the viewer's).
 */
interface RangedView {
    val range: DetailRange
    val days: List<DayTotal>
    val canGoBack: Boolean
    val today: LocalDate?
}

/**
 * One app of a child over [range] (the app screen, spec §9.3, stage 5a). [days]: the app's time per date of the range
 * (known when the date has a summary, so a known zero is a day the app was not used); [hours]: 24 clock hours of a DAY
 * from a usable UsageDetail, else null; [rank]: 1-based place in the child's top of the range (the `~other`
 * remainder is not ranked), null without time.
 * [category] with the parent's override applied ([overridden]); [canChangeCategory]: the viewer is a parent and the
 * package is not the `~other` remainder.
 */
class AppDetail(
    val member: MemberView,
    val packageName: String,
    override val range: DetailRange,
    val label: String,
    val icon: ByteArray?,
    val category: AppCategory,
    val overridden: Boolean,
    override val days: List<DayTotal>,
    val totalMs: Long,
    val hours: List<Long>?,
    val rank: Int?,
    val canChangeCategory: Boolean,
    override val canGoBack: Boolean,
    val canGoForward: Boolean,
    override val today: LocalDate?,
    /** What «Default» returns to: the category without the parent's override (catalog, built-in table, else Other). */
    val defaultCategory: AppCategory,
    /** The child is still an active member: not removed and no LEFT_GROUP (a limit is offered only then). */
    val active: Boolean = true,
) : RangedView {
    val period: Period get() = range.period

    /** By content ([icon] too), so the app screen's flow drops an identical state. */
    override fun equals(other: Any?): Boolean = other is AppDetail && fields() == other.fields() && icon.contentEquals(other.icon)

    override fun hashCode(): Int = fields().hashCode() * 31 + icon.contentHashCode()

    private fun fields(): List<Any?> = listOf(
        member, packageName, range, label, category, overridden, days, totalMs, hours, rank, canChangeCategory, canGoBack,
        canGoForward, today, defaultCategory, active,
    )
}
