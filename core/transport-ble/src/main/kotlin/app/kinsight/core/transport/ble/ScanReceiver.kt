package app.kinsight.core.transport.ble

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelUuid
import android.util.Log

/**
 * Background scan results (a batch when the controller batches). No I/O here: the results become Sightings and go to
 * the running engine, which decides off this thread. A service without a radio drops the batch; with no service at
 * all the scan is an orphan that outlived its process, and it is stopped ([ScanRouting]). A scan error goes to the
 * service, which retries the scan.
 */
@SuppressLint("MissingPermission") // stopScan of an orphan: a revoked permission throws, and that is caught
class ScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, -1)
        if (error != -1) Log.w(TAG, "scan error $error")
        val engine = EngineHolder.engine
        when (ScanRouting.route(engineRunning = engine != null, serviceRunning = SyncService.instance != null)) {
            ScanRouting.Action.STOP_ORPHAN_SCAN -> return stopOrphanScan(context)
            ScanRouting.Action.DROP -> return
            ScanRouting.Action.DELIVER -> Unit
        }
        if (error != -1) SyncService.scanError(error) else SyncService.scanOk()
        val results: List<ScanResult> = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT).orEmpty()
        }
        val sightings = results.mapNotNull { r ->
            AdvertPayload.decode(r.scanRecord?.getServiceData(ParcelUuid(BleIds.SERVICE_UUID)))
                ?.let { Sighting(AndroidPeerAddress(r.device), it, r.rssi) }
        }
        if (sightings.isNotEmpty()) engine?.onSightings(sightings)
    }

    private fun stopOrphanScan(context: Context) {
        try {
            val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            if (scanner == null) {
                Log.w(TAG, "orphan scan: no scanner to stop it with (Bluetooth off?)")
                return
            }
            scanner.stopScan(pendingIntent(context))
            Log.i(TAG, "orphan scan stopped: no sync service in this process")
        } catch (e: Exception) {
            Log.w(TAG, "orphan scan: stopScan failed", e) // SecurityException after a revoked permission, etc.
        }
    }

    companion object {
        private const val TAG = "KinsightSync"
        private const val REQUEST_SCAN = 1

        /** MUTABLE: the system writes the scan results into the intent. */
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_SCAN,
            Intent(context, ScanReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
