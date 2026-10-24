package app.kinsight.core.data

import android.content.Context
import android.os.PowerManager
import app.kinsight.core.location.LocationPermissions
import app.kinsight.core.usage.AppOpsUsageAccess

/**
 * A step of the child's phone setup (UI refresh §6), in the order the wizard asks. ACCESSIBILITY: the service that closes
 * blocked apps (app blocking spec §5.4), last.
 */
enum class SetupItem { USAGE_ACCESS, LOCATION, LOCATION_BACKGROUND, BATTERY, ACCESSIBILITY }

object ChildSetup {
    /**
     * What is still missing, in wizard order; nothing that is already granted (§6 «Шаги не повторяются»). The
     * [accessibility] service is asked for only while [enforcing] (a parent's BLOCK or block-on-limit rule for this
     * child, BlockDecision.enforcing): otherwise the wizard would ask for more than it needs (app blocking spec §5.4).
     */
    fun pending(
        usageAccess: Boolean,
        location: LocationPermissions.State,
        batteryUnrestricted: Boolean,
        accessibility: Boolean,
        enforcing: Boolean,
    ): List<SetupItem> = buildList {
        if (!usageAccess) add(SetupItem.USAGE_ACCESS)
        if (location == LocationPermissions.State.NONE || location == LocationPermissions.State.COARSE_ONLY) add(SetupItem.LOCATION)
        if (location != LocationPermissions.State.ALWAYS) add(SetupItem.LOCATION_BACKGROUND)
        if (!batteryUnrestricted) add(SetupItem.BATTERY)
        if (enforcing && !accessibility) add(SetupItem.ACCESSIBILITY)
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

    /**
     * [pending] for this phone now. [accessibility] (core/blocking BlockingState.serviceEnabled) and [enforcing]
     * ([enforcing] of the latest snapshot) come from the caller: core/data does not depend on core/blocking.
     */
    fun read(context: Context, accessibility: Boolean, enforcing: Boolean): List<SetupItem> = pending(
        usageAccess = AppOpsUsageAccess(context).granted(),
        location = LocationPermissions.state(context),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
        accessibility = accessibility,
        enforcing = enforcing,
    )

    /** Whether this phone's own effective rules need the enforcement service; false outside a group or once removed. */
    fun enforcing(snapshot: GroupSnapshot): Boolean = (snapshot as? GroupSnapshot.Ready)?.let { r ->
        BlockDecision.enforcing(AppRules.effective(r).filter { it.child == r.self })
    } == true
}
