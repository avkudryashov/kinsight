package app.kinsight.core.blocking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.Telephony
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import app.kinsight.core.data.SystemAllowList

/**
 * Spec §5.1 step 1: the packages never blocked. Kinsight itself, the fixed system set ([FIXED], known OEM in-call screens
 * included), every app that answers HOME ([homes]: not only the default one, so a phone without a chosen launcher cannot
 * block its launchers in a loop), the enabled keyboards ([imes]), the phone's current defaults ([defaults]: dialer, SMS
 * app) and every in-call screen ([inCallUis]: Samsung and MIUI show a call in their own package, not in the default
 * dialer). Read on every call: they change. [inCall] (a phone call is on, [callActive]) is for the Enforcer, which then
 * closes nothing at all.
 */
class AndroidSystemAllowList(
    private val context: Context,
    private val defaults: () -> Set<String> = { platformDefaults(context) },
    private val homes: () -> Set<String> = { homePackages(context.packageManager) },
    private val imes: () -> Set<String> = { imePackages(context) },
    private val inCallUis: () -> Set<String> = { inCallUiPackages(context.packageManager) },
    private val callActive: () -> Boolean = { phoneCallActive(context) },
) : SystemAllowList {
    override fun contains(packageName: String): Boolean =
        packageName == context.packageName || packageName in FIXED || isHome(packageName) || isOverlay(packageName) ||
            packageName in defaults() || packageName in inCallUis()

    /** A phone call is on: nothing is closed while it lasts, whatever is in front (the app stays re-checked after it). */
    fun inCall(): Boolean = callActive()

    /** A window over the app rather than an app in front: an enabled keyboard or System UI (shade, volume, quick settings). */
    fun isOverlay(packageName: String): Boolean = packageName in SYSTEM_UI || packageName in imes()

    /** A launcher: its window means the child left the app (Enforcer clears the app on screen). */
    fun isHome(packageName: String): Boolean = packageName in homes()

    companion object {
        /** Settings, emergency, the package installer and the permission controller (AOSP and Google builds). */
        val SYSTEM_UI = setOf("com.android.systemui")

        val FIXED = setOf(
            "android",
            "com.android.settings",
            "com.android.emergency",
            "com.google.android.emergency",
            "com.android.phone",
            "com.android.server.telecom",
            // In-call screens outside the default dialer: AOSP/MIUI, Samsung.
            "com.android.incallui",
            "com.samsung.android.incallui",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )

        /** Every app with a HOME activity; the `<queries>` HOME intent of core/usage makes them visible. */
        fun homePackages(pm: PackageManager): Set<String> =
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .mapNotNullTo(HashSet()) { it.activityInfo?.packageName }

        /**
         * Every in-call screen: a service bound by Telecom ([InCallService.SERVICE_INTERFACE], guarded by
         * BIND_INCALL_SERVICE) that declares itself a call UI ([TelecomManager.METADATA_IN_CALL_SERVICE_UI]). Needs no
         * permission; the `<queries>` InCallService intent of this module makes them visible.
         */
        fun inCallUiPackages(pm: PackageManager): Set<String> =
            pm.queryIntentServices(Intent(InCallService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
                .mapNotNull { it.serviceInfo }
                .filter { it.permission == Manifest.permission.BIND_INCALL_SERVICE && it.metaData?.getBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_UI) == true }
                .mapTo(HashSet()) { it.packageName }

        /**
         * A phone call is ringing out or on, by the audio mode (no permission; TelecomManager.isInCall needs
         * READ_PHONE_STATE, which Kinsight does not hold). VoIP calls (MODE_IN_COMMUNICATION) do not count.
         */
        fun phoneCallActive(context: Context): Boolean =
            context.getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_IN_CALL

        fun imePackages(context: Context): Set<String> =
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.mapTo(HashSet()) { it.packageName }.orEmpty()

        fun platformDefaults(context: Context): Set<String> = buildSet {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(::add)
            Telephony.Sms.getDefaultSmsPackage(context)?.let(::add)
        }
    }
}
