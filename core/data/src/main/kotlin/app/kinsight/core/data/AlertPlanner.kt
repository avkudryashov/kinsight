package app.kinsight.core.data

import app.kinsight.core.model.proto.StateEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** What the app should notify (spec §9.7); the app turns it into notifications on its channels. */
sealed interface Alert {
    data class Integrity(val device: String, val kind: StateEvent.Kind, val atMs: Long) : Alert

    data class NoContact(val device: String, val sinceMs: Long) : Alert

    /** On a child's phone: someone new can see its data (transparency, §9.6). */
    data class NewParent(val name: String) : Alert

    /** Today's (or, weekly, the last 7 days') time per child. */
    data class Summary(val weekly: Boolean, val lines: List<Pair<String, Long>>) : Alert
}

data class AlertInput(
    val self: MemberView,
    val members: List<MemberView>,
    val departed: List<MemberView>,
    val events: List<EventView>,
    val children: List<DeviceUsage>,
    val nowMs: Long,
    val zone: ZoneId,
    val noContactHours: Int,
    val summaryMode: SummaryMode,
    val markers: AlertMarkers,
)

data class AlertPlan(val alerts: List<Alert>, val markers: AlertMarkers)

/**
 * Pure alert planning, run hourly and after each session. A parent hears about its children only: integrity events
 * (each once, by seq; the first run marks history as seen), no contact for longer than N hours (once per loss) and a
 * daily (after [SUMMARY_HOUR]) or weekly (Sunday) summary. A child hears about every new parent.
 */
object AlertPlanner {
    val INTEGRITY_KINDS = setOf(
        StateEvent.Kind.SHARING_PAUSED,
        StateEvent.Kind.USAGE_ACCESS_REVOKED,
        StateEvent.Kind.LEFT_GROUP,
        StateEvent.Kind.SYNC_STOPPED,
        StateEvent.Kind.NOTIFICATION_HIDDEN,
        StateEvent.Kind.NEARBY_PERMISSION_OFF,
        StateEvent.Kind.APP_FORCE_STOPPED,
    )
    const val SUMMARY_HOUR = 20

    fun plan(input: AlertInput): AlertPlan = if (input.self.role == Role.CHILD) child(input) else parent(input)

    /** Builds the input from a snapshot (the worker in app). */
    fun input(ready: GroupSnapshot.Ready, prefs: Prefs, markers: AlertMarkers, nowMs: Long, zone: ZoneId): AlertInput {
        val members = SnapshotViews.members(ready, nowMs)
        val departed = ready.membership.departed.values.map {
            MemberView(it.member.id, it.member.displayName, it.member.role.toRole(), it.member.isAdmin, isSelf = false, lastSeenMs = null)
        }
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return AlertInput(
            self = members.first { it.isSelf },
            members = members,
            departed = departed,
            events = UsageReadModel.events(ready.events),
            children = SnapshotViews.dashboard(ready, today, nowMs).children,
            nowMs = nowMs,
            zone = zone,
            noContactHours = prefs.noContactHours,
            summaryMode = prefs.summaryMode,
            markers = markers,
        )
    }

    private fun parent(i: AlertInput): AlertPlan {
        val alerts = mutableListOf<Alert>()
        val notified = i.markers.notifiedSeq.toMutableMap()
        for (child in (i.members + i.departed).filter { it.role == Role.CHILD && !it.isSelf }.distinctBy { it.id }) {
            val own = i.events.filter { it.author == child.id }
            val max = own.maxOfOrNull { it.seq } ?: continue
            val mark = notified[child.id]
            if (mark != null) {
                own.filter { it.seq > mark && it.kind in INTEGRITY_KINDS }.sortedBy { it.seq }
                    .forEach { alerts += Alert.Integrity(child.name, it.kind, it.createdMs) }
            }
            notified[child.id] = maxOf(mark ?: 0L, max)
        }

        val limitMs = i.noContactHours * 3_600_000L
        val noContact = i.markers.noContact.toMutableSet()
        for (m in i.members.filter { it.role == Role.CHILD && !it.isSelf }) {
            val seen = m.lastSeenMs ?: continue
            if (i.nowMs - seen > limitMs) {
                if (noContact.add(m.id)) alerts += Alert.NoContact(m.name, seen)
            } else {
                noContact.remove(m.id)
            }
        }
        noContact.retainAll(i.members.map { it.id }.toSet())

        var lastSummary = i.markers.lastSummaryDate
        val now = Instant.ofEpochMilli(i.nowMs).atZone(i.zone)
        val today = now.toLocalDate().toString()
        val dayOk = when (i.summaryMode) {
            SummaryMode.OFF -> false
            SummaryMode.DAILY -> true
            SummaryMode.WEEKLY -> now.dayOfWeek == DayOfWeek.SUNDAY
        }
        if (dayOk && now.hour >= SUMMARY_HOUR && lastSummary != today && i.children.isNotEmpty()) {
            val weekly = i.summaryMode == SummaryMode.WEEKLY
            alerts += Alert.Summary(weekly, i.children.map { it.member.name to if (weekly) it.week.sumOf { d -> d.totalMs } else it.today.totalMs })
            lastSummary = today
        }
        return AlertPlan(alerts, i.markers.copy(notifiedSeq = notified, noContact = noContact, lastSummaryDate = lastSummary))
    }

    private fun child(i: AlertInput): AlertPlan {
        val parents = i.members.filter { it.role == Role.PARENT && !it.isSelf }
        val known = i.markers.knownParents
        val alerts = if (known == null) emptyList() else parents.filter { it.id !in known }.map { Alert.NewParent(it.name) }
        return AlertPlan(alerts, i.markers.copy(knownParents = parents.map { it.id }.toSet()))
    }
}
