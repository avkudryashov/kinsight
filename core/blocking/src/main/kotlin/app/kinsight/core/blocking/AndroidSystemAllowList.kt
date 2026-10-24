package app.kinsight.core.blocking

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import app.kinsight.core.data.SystemAllowList

/**
 * Spec §5.1 step 1: the packages never blocked. Kinsight itself, the fixed system set ([FIXED]), every app that answers
 * HOME ([homes]: not only the default one, so a phone without a chosen launcher cannot block its launchers in a loop)
 * and the phone's current defaults ([defaults]: enabled keyboards, dialer, SMS app). Read on every call: they change.
 */
class AndroidSystemAllowList(
    private val context: Context,
    private val defaults: () -> Set<String> = { platformDefaults(context) },
    private val homes: () -> Set<String> = { homePackages(context.packageManager) },
) : SystemAllowList {
    override fun contains(packageName: String): Boolean =
        packageName == context.packageName || packageName in FIXED || isHome(packageName) || packageName in defaults()

    /** A launcher: its window means the child left the app (Enforcer clears the app on screen). */
    fun isHome(packageName: String): Boolean = packageName in homes()

    companion object {
        /** System UI, settings, emergency, the package installer and the permission controller (AOSP and Google builds). */
        val FIXED = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.emergency",
            "com.google.android.emergency",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )

        /** Every app with a HOME activity; the `<queries>` HOME intent of core/usage makes them visible. */
        fun homePackages(pm: PackageManager): Set<String> =
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .mapNotNullTo(HashSet()) { it.activityInfo?.packageName }

        fun platformDefaults(context: Context): Set<String> = buildSet {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { add(it.packageName) }
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(::add)
            Telephony.Sms.getDefaultSmsPackage(context)?.let(::add)
        }
    }
}
