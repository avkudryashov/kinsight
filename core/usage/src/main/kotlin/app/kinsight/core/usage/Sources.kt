package app.kinsight.core.usage

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.toHex
import java.time.ZoneId

/** UsageStatsManager.queryEvents behind an interface: events with timeMs in [fromMs, untilMs). */
interface EventSource {
    fun read(fromMs: Long, untilMs: Long): List<UsageEvent>
}

/** Whether the app may read usage history (AppOpsManager GET_USAGE_STATS). */
interface UsageAccess {
    fun granted(): Boolean
}

interface DeviceClock {
    fun sample(): ClockSample
    fun zone(): ZoneId
}

/** Packages excluded from screen time: the default launcher (spec §7). */
interface LauncherSource {
    fun launchers(): Set<String>
}

/** What AppCatalog carries for a package (spec §4.3): label, 48 px WebP icon, ApplicationInfo.category. */
class AppInfo(val label: String, val iconWebp: ByteArray, val category: Int) {
    /** Content hash: a catalog entry is re-emitted only when this changes. */
    fun hash(): String = Primitives.sha256(
        label.encodeToByteArray(), byteArrayOf(0), category.toString().encodeToByteArray(), byteArrayOf(0), iconWebp,
    ).toHex()
}

/** Null when the package is not visible to the app (no launcher activity, spec §7): the UI shows the package name. */
interface AppInfoSource {
    fun info(packageName: String): AppInfo?
}
