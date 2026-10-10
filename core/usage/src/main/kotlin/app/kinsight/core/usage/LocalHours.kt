package app.kinsight.core.usage

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.ZoneId

data class HourSlot(val date: LocalDate, val hour: Int)

/** Local date and hour arithmetic for the hourly buckets (spec §7: an event belongs to its local date). */
object LocalHours {
    fun slot(ms: Long, zone: ZoneId): HourSlot {
        val local = Instant.ofEpochMilli(ms).atZone(zone)
        return HourSlot(local.toLocalDate(), local.hour)
    }

    /** Cuts [fromMs, untilMs) at local hour boundaries and reports each piece. */
    fun split(fromMs: Long, untilMs: Long, zone: ZoneId, sink: (HourSlot, Long) -> Unit) {
        var a = fromMs
        while (a < untilMs) {
            val local = Instant.ofEpochMilli(a).atZone(zone)
            // The next boundary lives on the local timeline: truncating the zoned time keeps the current offset and
            // breaks on 30-minute DST shifts. A boundary that is itself repeated resolves to its later occurrence.
            val boundary = local.toLocalDateTime().truncatedTo(ChronoUnit.HOURS).plusHours(1).atZone(zone)
            var next = boundary.toInstant().toEpochMilli()
            if (next <= a) next = boundary.withLaterOffsetAtOverlap().toInstant().toEpochMilli()
            check(next > a) { "no progress splitting at $a in $zone" }
            val b = minOf(next, untilMs)
            sink(HourSlot(local.toLocalDate(), local.hour), b - a)
            a = b
        }
    }

    /** Real time covered by a local hour: 1 h, 2 h for the repeated hour at DST end, 0 for the skipped one. */
    fun lengthMs(slot: HourSlot, zone: ZoneId): Long {
        val start = slot.date.atTime(slot.hour, 0)
        return Duration.between(start.atZone(zone), start.plusHours(1).atZone(zone)).toMillis()
    }
}
