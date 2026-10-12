package app.kinsight.core.transport.ble

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * BOOT_COMPLETED and MY_PACKAGE_REPLACED: run the service, but only if a group exists (none until stage 4), the
 * «Nearby devices» permissions are granted (SyncService.start checks) and the user has not pressed Stop. The service
 * checks the stop flag again, so a failed read here only costs a short notification.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!SyncGraph.groups(app).exists()) return@launch // file system: off the main thread
                if (BlePermissions.missing(app).isNotEmpty()) {
                    Log.i(TAG, "$action: no Nearby devices permission, not starting")
                    return@launch
                }
                val stopped = try {
                    withTimeout(READ_TIMEOUT) { SyncGraph.state(app).stoppedByUser() }
                } catch (e: Exception) {
                    Log.w(TAG, "$action: cannot read the stop flag, the service checks it again", e)
                    false
                }
                if (stopped) Log.i(TAG, "$action: stopped by the user, not starting") else SyncService.start(app, action)
            } catch (e: Exception) {
                // A group file or permission check that throws must not crash the process at boot.
                Log.e(TAG, "$action: start check failed, not starting", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "KinsightSync"
        val READ_TIMEOUT = 5.seconds
    }
}

/**
 * Re-advertises with the tags of the new 15-minute window (spike: setAndAllowWhileIdle every 15 min, late by Doze
 * windows; the group check tolerates the neighbouring window). Scan batches and sessions refresh the advert too.
 */
class RotationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (SyncService.instance == null) return
        SyncService.rotationTick()
        schedule(context)
    }

    companion object {
        private const val PERIOD_MS = 15 * 60_000L
        private const val REQUEST_ROTATION = 2

        private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
            context,
            REQUEST_ROTATION,
            Intent(context, RotationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun schedule(context: Context) {
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + PERIOD_MS,
                pendingIntent(context),
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
        }
    }
}
