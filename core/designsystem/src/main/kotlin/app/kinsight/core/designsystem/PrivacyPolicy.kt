package app.kinsight.core.designsystem

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * The privacy policy's address, in one place: Play wants the same link in the Console and inside the app (Settings and
 * the child's consent screen). The string resource keeps a placeholder for debug builds; :app overrides it from the
 * `kinsight.privacyUrl` build property, and release packaging fails while the address is missing or a placeholder
 * (build-logic ReleaseGates).
 */
fun privacyPolicyUrl(context: Context): String = context.getString(R.string.ds_privacy_policy_url)

/** Opens the policy in the browser (the app goes online only for the Yandex Disk sync); no browser is logged, never a crash. */
fun openPrivacyPolicy(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyPolicyUrl(context))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Log.w("KinsightLinks", "no browser for the privacy policy", e)
    }
}
