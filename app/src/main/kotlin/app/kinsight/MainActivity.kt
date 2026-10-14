package app.kinsight

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.CloudWork
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.feature.widget.KinsightWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The only screen activity (edge-to-edge, spec §9.8); also the target of `kinsight://pair/...` links (Decisions). */
class MainActivity : ComponentActivity() {
    private val invite = MutableStateFlow<String?>(null)

    /** A child's device to open (hex id, from an integrity alert); the main screen navigates and clears it. */
    private val device = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity has already handed its link to the first run.
        if (savedInstanceState == null) {
            invite.value = inviteOf(intent)
            device.value = deviceOf(intent)
            yandexLinkOf(intent)?.let(::finishYandexSignIn)
        }
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        setContent { KinsightTheme { KinsightApp(
                    invite,
                    onInviteConsumed = { invite.value = null },
                    appVersion = version,
                    openDevice = device,
                    onDeviceOpened = { device.value = null },
                ) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inviteOf(intent)?.let { invite.value = it }
        deviceOf(intent)?.let { device.value = it }
        yandexLinkOf(intent)?.let(::finishYandexSignIn)
    }

    /**
     * Opening the app starts sync, but honours the user's Stop (SyncControl.start; only the Start button clears it), and
     * reads the cloud at once (spec §9).
     */
    override fun onStart() {
        super.onStart()
        AppGraph.get(this).sync.start(fast = false)
        CloudWork.runSoon(applicationContext)
    }

    /**
     * Back from a system dialog too (the notification permission of onboarding does not stop the activity): the sync
     * notification is posted again once it can be seen (emulator run 2026-10-10, finding 3).
     */
    override fun onResume() {
        super.onResume()
        AppGraph.get(this).sync.refreshNotification()
    }

    /**
     * Leaving the app after a reset, a leave or a pause: the widget shows it now, not at the next hourly run. A rotation
     * or another configuration change stops the activity too, but nothing was left: no redraw then.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) refreshWidget(applicationContext)
    }

    /**
     * Any kinsight://pair/ link (the manifest filter): the first run parses it and names a wrong or newer invite
     * itself, so the version is not checked here.
     */
    private fun inviteOf(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.takeIf { it.scheme == "kinsight" && it.host == "pair" }?.toString()

    /** The Yandex sign-in redirect (spec §3, §14); it carries the code and the state, so it is never logged. */
    private fun yandexLinkOf(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.takeIf { it.scheme == BuildConfig.YANDEX_SCHEME }?.toString()

    private fun finishYandexSignIn(link: String) {
        val g = AppGraph.get(this)
        g.scope.launch { g.cloudSignIn.finish(link) }
    }

    companion object {
        /** The hex id of a child's device whose detail screen an alert opens. */
        const val EXTRA_DEVICE = "app.kinsight.extra.DEVICE"
        private val HEX_ID = Regex("[0-9a-f]{32}")

        /** The app as the launcher opens it (the dashboard, or whatever root the state calls for). */
        fun open(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        /** The app on the detail screen of [hex]'s device. Own action: never mistaken for the launcher intent. */
        fun openDevice(context: Context, hex: String): Intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_DEVICE)
            .putExtra(EXTRA_DEVICE, hex)

        /** Only a well formed id: anything else from an intent is ignored. */
        fun deviceOf(intent: Intent?): String? = intent?.getStringExtra(EXTRA_DEVICE)?.takeIf { HEX_ID.matches(it) }

        private const val ACTION_OPEN_DEVICE = "app.kinsight.OPEN_DEVICE"

        /** Redraws the widget on the app scope (never blocks the stop); replaced by tests. */
        @VisibleForTesting
        internal var refreshWidget: (Context) -> Unit = { app -> AppGraph.get(app).scope.launch { KinsightWidget.update(app) } }
    }
}
