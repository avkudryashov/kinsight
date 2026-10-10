package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.StateEvent
import java.time.LocalDate

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

data class DayTotal(val date: LocalDate, val totalMs: Long, val unlocks: Int, val hasData: Boolean = totalMs > 0 || unlocks > 0)

/** One app over a period; [label] falls back to the package name (spec §7: invisible packages). */
class AppUsage(val packageName: String, val label: String, val icon: ByteArray?, val totalMs: Long)

enum class Period(val days: Int) { DAY(1), WEEK(7), MONTH(30) }

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
)

/** A dashboard card: today, the last 7 days (oldest first), the mean of the 7 days before today. */
data class DeviceUsage(
    val member: MemberView,
    val today: DayTotal,
    val week: List<DayTotal>,
    val averageMs: Long?,
    val sharing: Sharing,
    val updatedMs: Long?,
)

/** Levels 1 and 2 of spec §9.3 for one period, and the device's latest events (newest first). */
data class DeviceDetail(
    val member: MemberView,
    val period: Period,
    val days: List<DayTotal>,
    val totalMs: Long,
    val unlocks: Int,
    val apps: List<AppUsage>,
    val events: List<EventView>,
    val sharing: Sharing,
)
