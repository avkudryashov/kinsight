package app.kinsight.core.transport.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** The runtime «Nearby devices» permissions the radio needs (API 31+). */
object BlePermissions {
    val REQUIRED: List<String> = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    fun missing(context: Context): List<String> =
        REQUIRED.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
