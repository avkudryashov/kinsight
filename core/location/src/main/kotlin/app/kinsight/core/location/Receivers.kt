package app.kinsight.core.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri

/** A proximity alert of one zone (spec §8.1); the data `kinsight-zone:<hex>` names it. */
class ZoneAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = zoneOf(intent) ?: return
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val pending = goAsync()
        LocationGraph.launch(context, "zone alert") { it.onProximity(id, entering) }.invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val SCHEME = "kinsight-zone"
        private val HEX = Regex("^[0-9a-f]{32}$")

        fun uri(zoneId: String): Uri = Uri.fromParts(SCHEME, zoneId, null)

        fun zoneOf(intent: Intent): String? = intent.data?.takeIf { it.scheme == SCHEME }?.schemeSpecificPart?.takeIf { HEX.matches(it) }
    }
}

/** The 5-minute tick outside the zones (spec §8.1). */
class LocationTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        LocationGraph.launch(context, "tick") { it.onTick(Tick.OUTSIDE) }.invokeOnCompletion { pending.finish() }
    }
}

/** Alerts and alarms are gone after a reboot or an update: everything is registered again. */
class LocationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        LocationGraph.refresh(context, rearm = true).invokeOnCompletion { pending.finish() }
    }
}
