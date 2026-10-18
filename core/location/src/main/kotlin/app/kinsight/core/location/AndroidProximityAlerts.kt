package app.kinsight.core.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log

/**
 * Spec §8.1 «LocationManager.addProximityAlert на каждую зону». One explicit PendingIntent per zone (its data names the
 * zone); mutable, because the system adds KEY_PROXIMITY_ENTERING to it. No expiry.
 */
class AndroidProximityAlerts(context: Context) : ProximityAlerts {
    private val app = context.applicationContext
    private val lm = app.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission") // the engine registers only when LocationPermissions says ALWAYS
    override fun register(zone: Zone) {
        try {
            lm.addProximityAlert(zone.latE7 / 1e7, zone.lonE7 / 1e7, zone.radiusM.toFloat(), -1L, intent(zone.id))
        } catch (e: SecurityException) {
            Log.w(TAG, "proximity alert refused: ${e.javaClass.simpleName}")
        }
    }

    override fun unregister(zoneId: String) {
        // No PendingIntent means no alert to remove (none was registered, or a reboot dropped both).
        intent(zoneId, PendingIntent.FLAG_NO_CREATE)?.let(lm::removeProximityAlert)
    }

    internal fun intent(zoneId: String): PendingIntent = intent(zoneId, PendingIntent.FLAG_UPDATE_CURRENT)!!

    private fun intent(zoneId: String, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        app,
        0,
        Intent(app, ZoneAlertReceiver::class.java).setData(ZoneAlertReceiver.uri(zoneId)),
        mode or PendingIntent.FLAG_MUTABLE,
    )

    private companion object {
        const val TAG = "KinsightLocation"
    }
}
