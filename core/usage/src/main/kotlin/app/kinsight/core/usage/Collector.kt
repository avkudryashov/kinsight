package app.kinsight.core.usage

import app.kinsight.core.database.CollectBatch
import app.kinsight.core.database.CollectorStateRow
import app.kinsight.core.database.DayZone
import app.kinsight.core.database.HourDelta
import app.kinsight.core.database.PendingEventRow
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.database.UsageDelta
import app.kinsight.core.model.proto.StateEvent
import java.time.Instant
import java.time.ZoneId

enum class CollectResult { COLLECTED, NO_ACCESS }

/**
 * Moves usage events from the system into the local queue (spec §7): always, sharing on or off. Each run reads
 * from the persisted cursor to now, so the current hour is recomputed incrementally, and commits the counts, the
 * integrity events and the new cursor in one transaction.
 */
class Collector(
    private val queue: RoomUsageQueue,
    private val source: EventSource,
    private val access: UsageAccess,
    private val clock: DeviceClock,
    private val launchers: LauncherSource,
) {
    suspend fun collect(): CollectResult {
        val now = clock.sample()
        val zoneNow = clock.zone()
        val granted = access.granted()
        val prev = queue.state()
        if (prev == null) {
            if (!granted) return CollectResult.NO_ACCESS
            val firstDay = Instant.ofEpochMilli(now.wallMs).atZone(zoneNow).toLocalDate().minusDays(FIRST_RUN_DAYS - 1)
            val from = firstDay.atStartOfDay(zoneNow).toInstant().toEpochMilli()
            return aggregate(from, now.wallMs, StreamState.INITIAL, zoneNow, zoneNow, now, emptyList(), prev)
        }
        if (!granted) return revoked(prev, now)
        val events = mutableListOf<PendingEventRow>()
        if (prev.accessGranted == false) {
            events += event(StateEvent.Kind.USAGE_ACCESS_RESTORED, prev.accessLostMs ?: prev.cursorMs, now.wallMs)
        }
        var from = prev.cursorMs
        var stream = StreamState(prev.screenOn, StreamCodec.decode(prev.resumed))
        var until = now.wallMs
        var jumpAt: Long? = null
        val shift = ClockWatch.shift(ClockSample(prev.wallMs, prev.elapsedMs, prev.bootCount), now)
        if (shift != null) {
            events += event(StateEvent.Kind.CLOCK_CHANGED, prev.wallMs, now.wallMs, shift)
            // Set back: finish the old timeline up to the elapsed-clock "now", then continue from the new wall time.
            if (shift < 0) until = maxOf(from, now.wallMs - shift)
            // Set ahead: nothing open is carried over the jump, or the jumped hours would count as screen time.
            if (shift > 0) jumpAt = now.wallMs - shift
        }
        if (until - from > HISTORY_MS) {
            val resume = until - HISTORY_MS
            events += event(StateEvent.Kind.DATA_GAP, from, resume)
            from = resume
            stream = StreamState.INITIAL
        }
        return aggregate(from, until, stream, ZoneId.of(prev.zoneId), zoneNow, now, events, prev, jumpAt)
    }

    /** Access is gone: record USAGE_ACCESS_REVOKED on the "had it → lost it" transition and hold the cursor. */
    private suspend fun revoked(prev: CollectorStateRow?, now: ClockSample): CollectResult {
        if (prev != null && prev.accessGranted != false) {
            queue.commit(
                CollectBatch(
                    events = listOf(event(StateEvent.Kind.USAGE_ACCESS_REVOKED, now.wallMs, now.wallMs)),
                    state = prev.copy(accessGranted = false, accessLostMs = now.wallMs),
                ),
            )
        }
        return CollectResult.NO_ACCESS
    }

    private suspend fun aggregate(
        from: Long,
        until: Long,
        start: StreamState,
        zone: ZoneId,
        zoneNext: ZoneId,
        now: ClockSample,
        events: List<PendingEventRow>,
        prev: CollectorStateRow?,
        jumpAt: Long? = null,
    ): CollectResult {
        val raw = if (until > from) source.read(from, until) else emptyList()
        // Without access the system answers with no events, which looks like an idle period: a revoke during the
        // read must not move the cursor over data that is still there.
        if (!access.granted()) return revoked(prev, now)
        val excluded = launchers.launchers()
        val agg = if (jumpAt == null) {
            Aggregator.run(start, raw, from, until, zone, excluded)
        } else {
            // A forward jump: [from, jumpAt) with the carried state, then [jumpAt, until) with no app carried over.
            // An app open across the jump undercounts until its next RESUMED (spec §7).
            val cut = jumpAt.coerceIn(from, until)
            val before = Aggregator.run(start, raw, from, cut, zone, excluded)
            val after = Aggregator.run(StreamState(before.end.screenOn, emptySet()), raw, cut, until, zone, excluded)
            before + after
        }
        val hourSlots = agg.screenMs.keys + agg.unlocks.keys
        val dates = (hourSlots + agg.appMs.keys.map { it.slot }).map { it.date }.distinct().sorted()
        queue.commit(
            CollectBatch(
                usage = agg.appMs.map { (k, ms) ->
                    UsageDelta(k.slot.date.toString(), k.slot.hour, k.packageName, ms, LocalHours.lengthMs(k.slot, zone))
                },
                hours = hourSlots.map { slot ->
                    HourDelta(
                        slot.date.toString(), slot.hour, agg.screenMs[slot] ?: 0L, agg.unlocks[slot] ?: 0,
                        LocalHours.lengthMs(slot, zone),
                    )
                },
                days = dates.map { DayZone(it.toString(), zone.id) },
                events = events,
                state = CollectorStateRow(
                    cursorMs = now.wallMs,
                    zoneId = zoneNext.id,
                    screenOn = agg.end.screenOn,
                    resumed = StreamCodec.encode(agg.end.resumed),
                    wallMs = now.wallMs,
                    elapsedMs = now.elapsedMs,
                    bootCount = now.bootCount,
                    accessGranted = true,
                    accessLostMs = null,
                ),
            ),
        )
        return CollectResult.COLLECTED
    }

    private fun event(kind: StateEvent.Kind, from: Long, to: Long, shift: Long = 0) =
        PendingEventRow(kind = kind.value, fromMs = from, toMs = to, clockShiftMs = shift)

    companion object {
        /** The first run reads this many local days, today included (within [HISTORY_MS] even across DST). */
        const val FIRST_RUN_DAYS = 7L

        /** History the system reliably keeps (measured about 9 days, spec §7); an older cursor is a DATA_GAP. */
        const val HISTORY_MS = 8 * 86_400_000L
    }
}

/** Resumed activities in collector_state: "package/class" lines (neither contains '/' nor '\n'). */
internal object StreamCodec {
    fun encode(resumed: Set<ActivityKey>): String =
        resumed.sortedWith(compareBy({ it.packageName }, { it.className }))
            .joinToString("\n") { "${it.packageName}/${it.className}" }

    fun decode(text: String): Set<ActivityKey> =
        if (text.isEmpty()) {
            emptySet()
        } else {
            text.split('\n').mapTo(LinkedHashSet()) {
                val slash = it.indexOf('/')
                ActivityKey(it.substring(0, slash), it.substring(slash + 1))
            }
        }
}
