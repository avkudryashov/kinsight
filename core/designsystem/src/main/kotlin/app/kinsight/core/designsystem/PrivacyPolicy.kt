package app.kinsight.core.designsystem

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * The privacy policy's address, in one place: Play wants the same link in the Console and inside the app (Settings and
 * the child's consent screen). A placeholder until the policy is hosted: replace it before the first upload.
 */
const val PRIVACY_POLICY_URL = "https://kinsight.example/privacy"

/** Opens the policy in the browser (the app itself has no INTERNET permission); no browser is logged, never a crash. */
fun openPrivacyPolicy(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Log.w("KinsightLinks", "no browser for the privacy policy", e)
    }
}
