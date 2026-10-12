package app.kinsight.core.usage

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

const val MIN = 60_000L
const val HOUR_MS = 3_600_000L
const val DAY = 86_400_000L
const val MAPS = "com.example.maps"
const val CHAT = "com.example.chat"
const val LAUNCHER = "com.example.launcher"
const val SETTINGS = "com.android.settings"

/** Recorded event sequences written as local wall times of [date] in [zone] ("10:15", "23:59:59"). */
class Script(private val zone: ZoneId, private val date: LocalDate) {
    fun ms(time: String, plusDays: Long = 0): Long =
        LocalDateTime.of(date.plusDays(plusDays), LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    fun resumed(time: String, pkg: String, cls: String = "$pkg.Main", plusDays: Long = 0) =
        UsageEvent(ms(time, plusDays), EventType.RESUMED, pkg, cls)

    fun paused(time: String, pkg: String, cls: String = "$pkg.Main", plusDays: Long = 0) =
        UsageEvent(ms(time, plusDays), EventType.PAUSED, pkg, cls)

    fun screenOn(time: String, plusDays: Long = 0) = UsageEvent(ms(time, plusDays), EventType.SCREEN_ON)
    fun screenOff(time: String, plusDays: Long = 0) = UsageEvent(ms(time, plusDays), EventType.SCREEN_OFF)
    fun unlock(time: String, plusDays: Long = 0) = UsageEvent(ms(time, plusDays), EventType.UNLOCK)
    fun shutdown(time: String, plusDays: Long = 0) = UsageEvent(ms(time, plusDays), EventType.SHUTDOWN)
    fun startup(time: String, plusDays: Long = 0) = UsageEvent(ms(time, plusDays), EventType.STARTUP)
}
