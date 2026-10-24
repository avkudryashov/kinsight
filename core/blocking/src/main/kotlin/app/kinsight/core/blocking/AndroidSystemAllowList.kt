package app.kinsight.core.blocking

import android.content.Context
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import app.kinsight.core.data.SystemAllowList
import app.kinsight.core.usage.HomeLauncherSource

/**
 * Spec §5.1 step 1: the packages never blocked. Kinsight itself, the fixed system set ([FIXED]) and the phone's current
 * defaults ([defaults]: launcher, enabled keyboards, dialer, SMS app), read on every call because the user can change them.
 */
class AndroidSystemAllowList(
    private val context: Context,
    private val defaults: () -> Set<String> = { platformDefaults(context) },
) : SystemAllowList {
    override fun contains(packageName: String): Boolean =
        packageName == context.packageName || packageName in FIXED || packageName in defaults()

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

        fun platformDefaults(context: Context): Set<String> = buildSet {
            addAll(HomeLauncherSource(context.packageManager).launchers())
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { add(it.packageName) }
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(::add)
            Telephony.Sms.getDefaultSmsPackage(context)?.let(::add)
        }
    }
}
