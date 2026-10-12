package app.kinsight.core.usage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Android's usage access screen (U1): onboarding, the child's Overview banner and Settings open it the same way. */
object UsageAccessSettings {
    /** Kinsight's own row where the settings app supports it, else the whole list, else Kinsight's App info. */
    fun intents(packageName: String): List<Intent> = listOf(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
    )

    /** Starts the first intent some settings app accepts; false when none did (never throws). */
    fun open(context: Context): Boolean = intents(context.packageName).any { intent ->
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
