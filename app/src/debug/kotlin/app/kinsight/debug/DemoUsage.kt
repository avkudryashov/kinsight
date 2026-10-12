package app.kinsight.debug

import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.HourBucket
import app.kinsight.core.model.proto.UsageDetail
import app.kinsight.core.model.proto.UsageSummary
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random

/** One demo app: package, label, raw ApplicationInfo.category, icon colour (ARGB) and a share weight. */
data class DemoApp(val packageName: String, val label: String, val category: Int, val color: Int, val weight: Double)

data class DemoHour(val hour: Int, val apps: Map<String, Long>, val unlocks: Int) {
    val totalMs: Long get() = apps.values.sum()
}

data class DemoDay(val date: LocalDate, val hours: List<DemoHour>) {
    val totalMs: Long get() = hours.sumOf { it.totalMs }
    val unlocks: Int get() = hours.sumOf { it.unlocks }

    fun summary(zoneId: String) = UsageSummary(
        local_date = date.toString(),
        zone_id = zoneId,
        total_ms = totalMs,
        apps = hours.flatMap { it.apps.entries }.groupBy({ it.key }, { it.value })
            .map { (pkg, ms) -> AppTotal(package_name = pkg, foreground_ms = ms.sum()) }
            .sortedWith(compareByDescending<AppTotal> { it.foreground_ms }.thenBy { it.package_name }),
        unlocks = unlocks,
    )

    fun detail(zoneId: String) = UsageDetail(
        local_date = date.toString(),
        zone_id = zoneId,
        hours = hours.map { h ->
            HourBucket(
                hour = h.hour,
                apps = h.apps.entries.sortedBy { it.key }.map { AppTotal(package_name = it.key, foreground_ms = it.value) },
                unlocks = h.unlocks,
            )
        },
    )
}

/**
 * Debug builds only: a deterministic, plausible screen-time history for store screenshots (DEMO_USAGE). Pure: the
 * receiver signs what it returns. A full day has 1.5–4.5 h (more at weekends), mostly 14:00–22:00, nothing
 * 23:00–07:00, 25–70 unlocks; today is cut at [nowHour]:[nowMinute].
 */
object DemoUsage {
    const val MIN_DAY_MS = 90L * 60_000
    const val MAX_DAY_MS = 270L * 60_000
    const val MIN_UNLOCKS = 25
    const val MAX_UNLOCKS = 70

    val APPS = listOf(
        DemoApp("com.google.android.youtube", "YouTube", 2, 0xFFE53935.toInt(), 0.24),
        DemoApp("com.zhiliaoapp.musically", "TikTok", 4, 0xFF212121.toInt(), 0.18),
        DemoApp("com.mojang.minecraftpe", "Minecraft", 0, 0xFF43A047.toInt(), 0.14),
        DemoApp("com.roblox.client", "Roblox", 0, 0xFF546E7A.toInt(), 0.12),
        DemoApp("com.whatsapp", "WhatsApp", 4, 0xFF25D366.toInt(), 0.10),
        DemoApp("org.telegram.messenger", "Telegram", 4, 0xFF2AABEE.toInt(), 0.08),
        DemoApp("com.duolingo", "Duolingo", 7, 0xFF58CC02.toInt(), 0.06),
        DemoApp("com.android.chrome", "Chrome", 7, 0xFF1E88E5.toInt(), 0.08),
    )

    /** Hour weights of a school day and a weekend day; 0 = never used. */
    private val WEEKDAY = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.3, 0.15, 0.05, 0.05, 0.1, 0.15, 0.3,
        0.9, 1.0, 1.1, 1.3, 1.4, 1.5, 1.6, 1.3, 0.6, 0.0)
    private val WEEKEND = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.2, 0.4, 0.6, 0.7, 0.6, 0.7,
        1.0, 1.1, 1.2, 1.3, 1.3, 1.4, 1.5, 1.3, 0.7, 0.0)

    /** [days] dates ending with [today], oldest first; today stops at [nowHour]:[nowMinute]. */
    fun generate(today: LocalDate, days: Int, seed: Long, nowHour: Int, nowMinute: Int): List<DemoDay> {
        require(days >= 1) { "days must be at least 1" }
        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val full = day(date, seed)
            if (back == 0) cut(full, nowHour, nowMinute) else full
        }
    }

    /** One full day, a function of the date and the seed only. */
    fun day(date: LocalDate, seed: Long): DemoDay {
        val rnd = Random(mix(seed * 1_000_003L + date.toEpochDay()))
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        val minutes = if (weekend) rnd.nextInt(170, 271) else rnd.nextInt(90, 211)
        val profile = if (weekend) WEEKEND else WEEKDAY
        val weights = DoubleArray(24) { h -> if (profile[h] == 0.0) 0.0 else profile[h] * (0.6 + rnd.nextDouble() * 0.8) }
        val perHour = split(minutes, weights, cap = 55)
        // The day's taste: every app's weight moves by up to ±50 %.
        val taste = APPS.map { it.weight * (0.5 + rnd.nextDouble()) }
        val unlocks = rnd.nextInt(MIN_UNLOCKS, MAX_UNLOCKS + 1)
        val active = (0 until 24).filter { perHour[it] > 0 }
        val unlockSplit = IntArray(24)
        active.forEach { unlockSplit[it] = 1 }
        val extra = split(unlocks - active.size, DoubleArray(24) { h -> if (perHour[h] > 0) perHour[h].toDouble() else 0.0 }, cap = Int.MAX_VALUE)
        for (h in 0 until 24) unlockSplit[h] += extra[h]
        val hours = active.map { h ->
            val ms = perHour[h] * 60_000L
            DemoHour(h, appsFor(ms, taste, rnd), unlockSplit[h])
        }
        return DemoDay(date, hours)
    }

    /** The day up to [hour]:[minute]: earlier hours whole, the current hour scaled by the minutes passed. */
    fun cut(day: DemoDay, hour: Int, minute: Int): DemoDay {
        val hours = day.hours.mapNotNull { h ->
            when {
                h.hour < hour -> h
                h.hour > hour -> null
                else -> {
                    val f = minute / 60.0
                    val apps = h.apps.mapValues { (it.value * f).toLong() / 1000 * 1000 }.filterValues { it > 0 }
                    if (apps.isEmpty()) null else DemoHour(h.hour, apps, maxOf(1, (h.unlocks * f).toInt()))
                }
            }
        }
        return DemoDay(day.date, hours)
    }

    /** [ms] of one hour over one to three apps, weighted by the day's taste; whole seconds, exact sum. */
    private fun appsFor(ms: Long, taste: List<Double>, rnd: Random): Map<String, Long> {
        val count = minOf(1 + rnd.nextInt(3), APPS.size)
        val picked = mutableListOf<Int>()
        while (picked.size < count) {
            val left = APPS.indices.filter { it !in picked }
            var x = rnd.nextDouble() * left.sumOf { taste[it] }
            picked += left.firstOrNull { x -= taste[it]; x <= 0 } ?: left.last()
        }
        val seconds = split((ms / 1000).toInt(), DoubleArray(picked.size) { taste[picked[it]] * (0.5 + rnd.nextDouble()) }, cap = Int.MAX_VALUE)
        return picked.indices.filter { seconds[it] > 0 }.associate { APPS[picked[it]].packageName to seconds[it] * 1000L }
    }

    /** SplitMix64 finaliser: Random's first values follow close seeds (consecutive dates) too closely. */
    private fun mix(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** [total] units over [weights] (largest remainder), no slot above [cap], zero-weight slots stay empty. */
    internal fun split(total: Int, weights: DoubleArray, cap: Int): IntArray {
        val out = IntArray(weights.size)
        var left = total
        while (left > 0) {
            val open = weights.indices.filter { weights[it] > 0 && out[it] < cap }
            require(open.isNotEmpty()) { "cannot place $left more units" }
            val sum = open.sumOf { weights[it] }
            val share = open.associateWith { minOf((left * weights[it] / sum), (cap - out[it]).toDouble()) }
            var placed = 0
            share.forEach { (i, sh) -> out[i] += sh.toInt(); placed += sh.toInt() }
            // The remainder: one unit each to the largest fractional shares that still have room (one pass, so a
            // single heavy slot does not collect every leftover unit).
            val need = left - placed
            open.filter { out[it] < cap }.sortedByDescending { share.getValue(it) - share.getValue(it).toInt() }
                .take(need).forEach { out[it]++; placed++ }
            left -= placed
        }
        return out
    }
}
