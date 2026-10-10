package app.kinsight.core.transport.ble

import android.app.PendingIntent
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
 * the running engine, which decides off this thread. Without a running service the batch is dropped.
 */
class ScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, -1)
        if (error != -1) Log.w(TAG, "scan error $error")
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
        if (sightings.isNotEmpty()) EngineHolder.engine?.onSightings(sightings)
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
