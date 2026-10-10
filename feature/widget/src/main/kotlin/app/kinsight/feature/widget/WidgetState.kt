package app.kinsight.feature.widget

import app.kinsight.core.data.DashboardState
import app.kinsight.core.data.DeviceUsage
import app.kinsight.core.data.Role
import app.kinsight.core.data.Sharing

/** What the home-screen widget shows (spec §9.7, stage 5b), independent of Glance so it is tested on the JVM. */
sealed interface WidgetState {
    /** No group, a reset or a removed device: only «Open Kinsight». */
    data object Empty : WidgetState

    /** A parent: the first [WidgetStates.MAX_CHILDREN] children in the dashboard's order, [more] beyond them. */
    data class Parent(val children: List<ChildRow>, val more: Int) : WidgetState

    /** A child: its own time today and the parents who see it ([parents]: names, never their usage). */
    data class Child(val todayMs: Long, val hasData: Boolean, val parents: List<String>) : WidgetState
}

/** [id]: the device's hex id, what MainActivity's detail link takes. [hasData]: a summary for today exists. */
data class ChildRow(val id: String, val name: String, val todayMs: Long, val hasData: Boolean, val status: ChildStatus)

/** The dashboard card's status line (DashboardScreen.status), in data: the widget formats it. */
sealed interface ChildStatus {
    data class Paused(val sinceMs: Long) : ChildStatus

    /** Not seen for longer than the user's «no contact» setting (the same comparison as the alert). */
    data class NoContact(val seenMs: Long) : ChildStatus

    data class Updated(val atMs: Long) : ChildStatus

    /** Never consented: no time is shown (Play ruling, spec §4.8). */
    data object NotSharing : ChildStatus

    data object NoData : ChildStatus
}

object WidgetStates {
    const val MAX_CHILDREN = 4
    private const val HOUR_MS = 3_600_000L

    /**
     * [dashboard] null: no group (or removed). A parent sees only CHILD members other than itself (its own usage and
     * other parents' never appear, spec §4.8), minus children that left; a child sees its own day.
     */
    fun of(dashboard: DashboardState?, noContactHours: Int, nowMs: Long): WidgetState {
        val d = dashboard ?: return WidgetState.Empty
        if (d.self.role == Role.CHILD) {
            val own = d.selfUsage
            return WidgetState.Child(
                todayMs = own?.today?.totalMs ?: 0,
                hasData = own?.today?.hasData == true,
                parents = d.parents.filter { it.role == Role.PARENT && !it.isSelf }.map { it.name },
            )
        }
        val rows = d.children
            .filter { it.member.role == Role.CHILD && !it.member.isSelf && it.sharing != Sharing.Left }
            .map { row(it, noContactHours * HOUR_MS, nowMs) }
        return WidgetState.Parent(rows.take(MAX_CHILDREN), more = (rows.size - MAX_CHILDREN).coerceAtLeast(0))
    }

    private fun row(u: DeviceUsage, noContactMs: Long, nowMs: Long): ChildRow {
        val status = status(u, noContactMs, nowMs)
        val shown = status != ChildStatus.NotSharing
        return ChildRow(
            id = u.member.id.hex,
            name = u.member.name,
            todayMs = if (shown) u.today.totalMs else 0,
            hasData = shown && u.today.hasData,
            status = status,
        )
    }

    private fun status(u: DeviceUsage, noContactMs: Long, nowMs: Long): ChildStatus = when (val s = u.sharing) {
        is Sharing.Paused -> ChildStatus.Paused(s.sinceMs)
        Sharing.Never, Sharing.Left -> ChildStatus.NotSharing
        Sharing.On -> {
            val seen = u.member.lastSeenMs
            val updated = u.updatedMs
            when {
                seen != null && nowMs - seen > noContactMs -> ChildStatus.NoContact(seen)
                updated != null -> ChildStatus.Updated(updated)
                else -> ChildStatus.NoData
            }
        }
    }
}
