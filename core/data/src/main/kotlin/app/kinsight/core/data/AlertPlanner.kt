package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.StateEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the app should notify (spec §9.7); the app turns it into notifications on its channels. */
sealed interface Alert {
    /**
     * [id]: the child's device, so tapping the alert can open its detail screen; null when unknown. [durationMs]: an
     * interval event's length (to_ms after from_ms), null when it has none; a short Bluetooth break is quiet (U4).
     */
    data class Integrity(
        val device: String,
        val kind: StateEvent.Kind,
        val atMs: Long,
        val id: DeviceId? = null,
        val durationMs: Long? = null,
    ) : Alert

    /**
     * The child undid an open problem ([kind] is the open kind: SHARING_PAUSED or NOTIFICATION_HIDDEN, see
     * AlertPlanner.CLOSERS): the app cancels that child's notification of [kind] instead of posting.
     */
    data class Resolved(val id: DeviceId, val kind: StateEvent.Kind, val device: String) : Alert

    /** [id]: the child's device (the notification is grouped by it); null when unknown. */
    data class NoContact(val device: String, val sinceMs: Long, val id: DeviceId? = null) : Alert

    /** On a child's phone: someone new can see its data (transparency, §9.6). [id]: that parent's device. */
    data class NewParent(val name: String, val id: DeviceId? = null) : Alert

    /** Today's (or, weekly, the last 7 days') time per child. */
    data class Summary(val weekly: Boolean, val lines: List<Pair<String, Long>>) : Alert

    /**
     * A child passed a parent's daily limit (stage 5b, §9.7) on its own today: [usedMs] of [target] against [limitMs].
     * [targetLabel]: the app's label from the child's catalog for an app rule (null: unknown, or not an app rule).
     */
    data class Threshold(
        val child: DeviceId,
        val device: String,
        val target: ThresholdTarget,
        val targetLabel: String?,
        val usedMs: Long,
        val limitMs: Long,
    ) : Alert
}

/** How loud an alert is (U4, audit 2026-10-11); the app picks the notification channel by it. */
enum class AlertLevel { IMPORTANT, MINOR, TRANSPARENCY, SUMMARY, THRESHOLD }

/**
 * A child's today as the thresholds read it (AlertPlanner.input): its actual date (the zone of its latest summary; one
 * day before or after the parent's, UsageReadModel.childToday with thresholds = true) and the usage of that date; [usage] null when no summary of that date arrived yet (nothing to evaluate,
 * never the day before). [appLabels]: catalog labels of the apps the parent's rules name.
 */
data class ThresholdDay(val date: LocalDate, val usage: TodayUsage?, val appLabels: Map<String, String> = emptyMap())

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
    /** When each member was added (the AddMember entry's created_at_ms): no-contact counts from it if never seen. */
    val joinedMs: Map<DeviceId, Long> = emptyMap(),
    /** The admin who added this device; a child's «new parent» baseline is this admin only. Null: unknown. */
    val inviter: DeviceId? = null,
    /** The parent's daily limits (stage 5b); only rules of active children are evaluated. */
    val thresholds: List<ThresholdRule> = emptyList(),
    /** Each child's today for [thresholds], by device. */
    val todays: Map<DeviceId, ThresholdDay> = emptyMap(),
    /**
     * Per child, the viewer's last 7 days (oldest first) for the summary: the parent's day, not the child's (which
     * [children] carry since the dashboard shows the child's own day). Absent: [children]'s figures.
     */
    val viewerWeek: Map<DeviceId, List<DayTotal>> = emptyMap(),
)

data class AlertPlan(val alerts: List<Alert>, val markers: AlertMarkers)

/**
 * Pure alert planning, run hourly and after each session. A parent hears about its children only: integrity events
 * (each once, by seq; the first run marks history as seen) and their resolution (a resume after a pause, a shown
 * after a hidden notification: once per closing event, by the same seq marker), no contact for longer than N hours
 * (once per loss) and a daily (after [SUMMARY_HOUR]) or weekly (Sunday) summary. A child hears about every new parent.
 *
 * The summary is posted at most once per day and is a snapshot of that moment: usage synced later the same evening does
 * not re-post or update it (the dashboard shows the live figure). A child is left out of the summary on the day it
 * joined the group, so a parent who pairs in the evening is not told "<1 min".
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
        StateEvent.Kind.BLUETOOTH_OFF,
        StateEvent.Kind.DATA_GAP,
        StateEvent.Kind.CLOCK_CHANGED,
    )
    /**
     * Open problems the child can undo, by their closing kind. Their notification stays up until the closing event
     * cancels it (Alert.Resolved). Interval kinds posted only after they end (SYNC_STOPPED, BLUETOOTH_OFF, ...) are
     * not here.
     */
    val CLOSERS = mapOf(
        StateEvent.Kind.SHARING_PAUSED to StateEvent.Kind.SHARING_RESUMED,
        StateEvent.Kind.NOTIFICATION_HIDDEN to StateEvent.Kind.NOTIFICATION_SHOWN,
    )
    private val OPENERS = CLOSERS.entries.associate { it.value to it.key }

    /** Events that need nothing from a parent: «Прочие события», no sound (U4). */
    val MINOR_KINDS = setOf(StateEvent.Kind.CLOCK_CHANGED, StateEvent.Kind.DATA_GAP)

    /** A Bluetooth break shorter than this is minor; one of unknown length is important. */
    const val SHORT_BLUETOOTH_MS = 30 * 60_000L

    fun level(alert: Alert): AlertLevel = when (alert) {
        is Alert.Integrity -> when {
            alert.kind in MINOR_KINDS -> AlertLevel.MINOR
            alert.kind == StateEvent.Kind.BLUETOOTH_OFF && (alert.durationMs ?: Long.MAX_VALUE) < SHORT_BLUETOOTH_MS -> AlertLevel.MINOR
            else -> AlertLevel.IMPORTANT
        }
        is Alert.NoContact, is Alert.Resolved -> AlertLevel.IMPORTANT
        is Alert.NewParent -> AlertLevel.TRANSPARENCY
        is Alert.Summary -> AlertLevel.SUMMARY
        is Alert.Threshold -> AlertLevel.THRESHOLD
    }

    /** An interval event's length; null without a to_ms after its from_ms. */
    private fun durationOf(e: EventView): Long? = if (e.fromMs > 0 && e.toMs > e.fromMs) e.toMs - e.fromMs else null
    const val SUMMARY_HOUR = 20

    fun plan(input: AlertInput): AlertPlan = if (input.self.role == Role.CHILD) child(input) else parent(input)

    /** How many of the children's dates the threshold markers keep. */
    const val THRESHOLD_MARKER_DATES = 3

    /**
     * Builds the input from a snapshot (the worker in app). [thresholds] and the viewer's category [overrides] feed the
     * threshold check; each child's today is its own (UsageReadModel.todayUsage).
     */
    fun input(
        ready: GroupSnapshot.Ready,
        prefs: Prefs,
        markers: AlertMarkers,
        nowMs: Long,
        zone: ZoneId,
        thresholds: List<ThresholdRule> = emptyList(),
        overrides: Map<String, AppCategory> = emptyMap(),
    ): AlertInput {
        val members = SnapshotViews.members(ready, nowMs)
        val departed = ready.membership.departed.values.map {
            MemberView(it.member.id, it.member.displayName, it.member.role.toRole(), it.member.isAdmin, isSelf = false, lastSeenMs = null)
        }
        val adds = ready.membership.effectiveEntries.mapNotNull { e ->
            val add = e.body.membership_op?.add ?: return@mapNotNull null
            val pk = add.sign_public_key.toByteArray()
            if (pk.size != 32) null else DeviceId.fromSignPublicKey(pk) to e
        }.toMap()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val byAuthor = UsageReadModel.summaries(ready.summaries)
        val children = members.filter { it.role == Role.CHILD && !it.isSelf }
        val ruled = thresholds.map { it.child }.toSet()
        val todays = children.filter { it.id in ruled }.associate { child ->
            val byDate = byAuthor[child.id]
            val catalog = UsageReadModel.catalogOf(ready.catalog, child.id)
            val apps = thresholds.filter { it.child == child.id }.mapNotNull { (it.target as? ThresholdTarget.App)?.packageName }
            child.id to ThresholdDay(
                date = UsageReadModel.childToday(byDate, today, nowMs, thresholds = true),
                usage = UsageReadModel.todayUsage(child, byDate, catalog, today, overrides, nowMs),
                appLabels = apps.mapNotNull { p -> catalog[p]?.label?.takeIf { it.isNotBlank() }?.let { p to it } }.toMap(),
            )
        }
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
            joinedMs = adds.mapValues { it.value.body.created_at_ms },
            inviter = adds[members.first { it.isSelf }.id]?.author,
            thresholds = thresholds,
            todays = todays,
            viewerWeek = children.associate { it.id to UsageReadModel.days(byAuthor[it.id], today, 7) },
        )
    }

    private fun parent(i: AlertInput): AlertPlan {
        val alerts = mutableListOf<Alert>()
        val notified = i.markers.notifiedSeq.toMutableMap()
        for (child in (i.members + i.departed).filter { it.role == Role.CHILD && !it.isSelf }.distinctBy { it.id }) {
            val own = i.events.filter { it.author == child.id }
            val max = own.maxOfOrNull { it.seq } ?: 0L
            val mark = notified[child.id] ?: if (i.markers.initialized) 0L else max
            val sorted = own.sortedBy { it.seq }
            for ((index, e) in sorted.withIndex()) {
                if (e.seq <= mark) continue
                if (e.kind in INTEGRITY_KINDS) alerts += Alert.Integrity(child.name, e.kind, e.atMs, child.id, durationOf(e))
                val open = OPENERS[e.kind] ?: continue
                // Resolved only when the pair's latest earlier event is the open one: a repeated resume says nothing.
                val before = sorted.subList(0, index).lastOrNull { it.kind == open || it.kind == e.kind }
                if (before?.kind == open) alerts += Alert.Resolved(child.id, open, child.name)
            }
            notified[child.id] = maxOf(mark, max)
        }

        val limitMs = i.noContactHours * 3_600_000L
        val noContact = i.markers.noContact.toMutableSet()
        for (m in i.members.filter { it.role == Role.CHILD && !it.isSelf }) {
            val seen = m.lastSeenMs ?: i.joinedMs[m.id] ?: continue
            if (i.nowMs - seen > limitMs) {
                if (noContact.add(m.id)) alerts += Alert.NoContact(m.name, seen, m.id)
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
        // A child that joined today has only minutes of data (pairing often happens in the evening): it is left out
        // until its first full day. With nobody left, nothing is posted and the day is not used up.
        val summarised = i.children.filter { c ->
            i.joinedMs[c.member.id]?.let { Instant.ofEpochMilli(it).atZone(i.zone).toLocalDate() != now.toLocalDate() } ?: true
        }
        if (dayOk && now.hour >= SUMMARY_HOUR && lastSummary != today && summarised.isNotEmpty()) {
            val weekly = i.summaryMode == SummaryMode.WEEKLY
            alerts += Alert.Summary(
                weekly,
                summarised.map { c ->
                    // The parent's day (and week), not the child's: a child ahead would read as its near-empty new day.
                    val week = i.viewerWeek[c.member.id]
                    c.member.name to when {
                        weekly -> (week ?: c.week).sumOf { d -> d.totalMs }
                        week != null -> week.lastOrNull()?.totalMs ?: 0L
                        else -> c.today.totalMs
                    }
                },
            )
            lastSummary = today
        }
        val thresholdNotified = thresholds(i, alerts)
        return AlertPlan(
            alerts,
            i.markers.copy(
                notifiedSeq = notified, noContact = noContact, lastSummaryDate = lastSummary, initialized = true,
                thresholdNotified = thresholdNotified,
            ),
        )
    }

    /**
     * Daily limits (stage 5b, §9.7): active children only, on the child's own today. A rule fires once per (rule,
     * limit, child's date), also on the first run (a rule is a promise about today, not history). Markers of a
     * deleted rule go (re-adding it notifies again); the rest keep the last [THRESHOLD_MARKER_DATES] dates.
     */
    private fun thresholds(i: AlertInput, alerts: MutableList<Alert>): Set<String> {
        val children = i.members.filter { it.role == Role.CHILD && !it.isSelf }.associateBy { it.id }
        val ruleIds = i.thresholds.map { it.id }.toSet()
        val marks = i.markers.thresholdNotified.filterTo(mutableSetOf()) { AlertMarkers.parseThresholdKey(it)?.first in ruleIds }
        for (rule in i.thresholds.sortedBy { it.id }) {
            val child = children[rule.child] ?: continue
            val day = i.todays[child.id] ?: continue
            val usage = day.usage ?: continue
            val used = ThresholdEval.usage(rule.target, usage)
            if (used < rule.limitMs) continue
            if (marks.add(AlertMarkers.thresholdKey(rule.id, rule.limitMs, day.date))) {
                val label = (rule.target as? ThresholdTarget.App)?.let { day.appLabels[it.packageName] }
                alerts += Alert.Threshold(child.id, child.name, rule.target, label, used, rule.limitMs)
            }
        }
        val keep = marks.mapNotNull { AlertMarkers.parseThresholdKey(it)?.second }.distinct().sortedDescending().take(THRESHOLD_MARKER_DATES).toSet()
        return marks.filterTo(mutableSetOf()) { AlertMarkers.parseThresholdKey(it)?.second in keep }
    }

    /**
     * The parents a child's phone already knows before this pass: after the first pass, the ones it was told about;
     * on the first, only the inviting admin (a parent added since the join is news); unknown inviter: all. The
     * Overview banner starts from the same set (U7).
     */
    fun knownParents(i: AlertInput): Set<DeviceId> {
        val parents = i.members.filter { it.role == Role.PARENT && !it.isSelf }
        return if (i.markers.initialized) i.markers.knownParents.orEmpty() else
            i.inviter?.takeIf { id -> parents.any { it.id == id } }?.let { setOf(it) } ?: parents.map { it.id }.toSet()
    }

    /**
     * Each current child's problems still open (a pause or a hidden notification whose latest event of the pair is the
     * opening one) that were already announced (seq within the child's marker: a newer one is the plan's to say,
     * with sound), as alerts; for a re-post after their notifications were lost, not for the markers.
     */
    fun openProblems(i: AlertInput): List<Alert.Integrity> =
        i.members.filter { it.role == Role.CHILD && !it.isSelf }.flatMap { child ->
            val mark = i.markers.notifiedSeq[child.id] ?: 0L
            val own = i.events.filter { it.author == child.id && it.seq <= mark }
            CLOSERS.mapNotNull { (open, close) ->
                own.filter { it.kind == open || it.kind == close }.maxByOrNull { it.seq }?.takeIf { it.kind == open }
                    ?.let { Alert.Integrity(child.name, open, it.atMs, child.id, durationOf(it)) }
            }
        }

    private fun child(i: AlertInput): AlertPlan {
        val parents = i.members.filter { it.role == Role.PARENT && !it.isSelf }
        val known = knownParents(i)
        val alerts = parents.filter { it.id !in known }.map { Alert.NewParent(it.name, it.id) }
        return AlertPlan(alerts, i.markers.copy(knownParents = parents.map { it.id }.toSet(), initialized = true))
    }
}
