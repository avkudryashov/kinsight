package app.kinsight.core.data

import android.content.Context
import android.os.PowerManager
import app.kinsight.core.location.LocationPermissions
import app.kinsight.core.usage.AppOpsUsageAccess

/** A step of the child's phone setup (UI refresh §6), in the order the wizard asks. */
enum class SetupItem { USAGE_ACCESS, LOCATION, LOCATION_BACKGROUND, BATTERY }

object ChildSetup {
    /** What is still missing, in wizard order; nothing that is already granted (§6 «Шаги не повторяются»). */
    fun pending(usageAccess: Boolean, location: LocationPermissions.State, batteryUnrestricted: Boolean): List<SetupItem> = buildList {
        if (!usageAccess) add(SetupItem.USAGE_ACCESS)
        if (location == LocationPermissions.State.NONE || location == LocationPermissions.State.COARSE_ONLY) add(SetupItem.LOCATION)
        if (location != LocationPermissions.State.ALWAYS) add(SetupItem.LOCATION_BACKGROUND)
        if (!batteryUnrestricted) add(SetupItem.BATTERY)
    }

    /**
     * The step to show: the first of [pending] not in [skipped] («Позже»). The background step waits while precise
     * location is pending: Android grants «all the time» only on top of a foreground grant (Review Focus 4).
     */
    fun next(pending: List<SetupItem>, skipped: Set<SetupItem>): SetupItem? = showable(pending, skipped).firstOrNull()

    /** The steps of [pending] that can still be shown: not skipped, and not the held-back background step. */
    fun showable(pending: List<SetupItem>, skipped: Set<SetupItem>): List<SetupItem> = pending.filter { item ->
        item !in skipped && !(item == SetupItem.LOCATION_BACKGROUND && SetupItem.LOCATION in pending)
    }

    /**
     * «Шаг N из M» for the page of [current]: [passed] are the steps already shown and left behind (granted or
     * skipped), M adds the steps still to show, so the last visible page always reads «N из N».
     */
    fun progress(passed: Int, pending: List<SetupItem>, skipped: Set<SetupItem>): Pair<Int, Int> =
        (passed + 1) to (passed + showable(pending, skipped).size.coerceAtLeast(1))

    /** [pending] for this phone now. */
    fun read(context: Context): List<SetupItem> = pending(
        usageAccess = AppOpsUsageAccess(context).granted(),
        location = LocationPermissions.state(context),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
    )
}
