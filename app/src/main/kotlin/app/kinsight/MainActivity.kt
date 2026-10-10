package app.kinsight

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.kinsight.core.data.AppGraph
import app.kinsight.core.designsystem.theme.KinsightTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** The only screen activity (edge-to-edge, spec §9.8); also the target of `kinsight://pair/...` links (Decisions). */
class MainActivity : ComponentActivity() {
    private val invite = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity has already handed its link to the first run.
        if (savedInstanceState == null) invite.value = inviteOf(intent)
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        setContent { KinsightTheme { KinsightApp(invite, onInviteConsumed = { invite.value = null }, appVersion = version) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inviteOf(intent)?.let { invite.value = it }
    }

    /** Opening the app starts sync, but honours the user's Stop (SyncControl.start; only the Start button clears it). */
    override fun onStart() {
        super.onStart()
        AppGraph.get(this).sync.start(fast = false)
    }

    /**
     * Any kinsight://pair/ link (the manifest filter): the first run parses it and names a wrong or newer invite
     * itself, so the version is not checked here.
     */
    private fun inviteOf(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.takeIf { it.scheme == "kinsight" && it.host == "pair" }?.toString()
}
