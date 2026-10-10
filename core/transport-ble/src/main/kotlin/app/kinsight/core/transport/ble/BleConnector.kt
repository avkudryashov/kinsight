package app.kinsight.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.kinsight.core.sync.ByteStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

/**
 * Client half (spike: GATT connect 2.2 s, PSM read about 4 s in total, L2CAP connect 56 ms): read the peer's PSM over
 * GATT, drop GATT, connect the insecure L2CAP channel. The Noise handshake above authenticates the peer.
 * Every failure, a revoked «Nearby devices» permission (SecurityException) included, ends [connect] with an exception
 * the engine logs; the GATT callbacks run on binder threads and never let one escape there.
 */
@SuppressLint("MissingPermission")
internal class BleConnector(private val context: Context, private val log: (String) -> Unit) : BleLink {
    override suspend fun connect(address: PeerAddress): ByteStream {
        val device = (address as AndroidPeerAddress).device
        val psm = withTimeoutOrNull(GATT_TIMEOUT) { readPsm(device) } ?: throw IOException("GATT timed out")
        val socket = device.createInsecureL2capChannel(psm)
        try {
            runClosingOnCancel(Dispatchers.IO.asExecutor(), onCancel = { runCatching { socket.close() } }) { socket.connect() }
            val stream = socket.byteStream() // getInputStream/getOutputStream can throw too: the socket must not leak
            log("l2cap connected, psm=$psm")
            return stream
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private suspend fun readPsm(device: BluetoothDevice): Int = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)

        fun finish(gatt: BluetoothGatt, result: Result<Int>) {
            if (!done.compareAndSet(false, true)) return
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
            cont.resumeWith(result)
        }

        /** Runs a callback body; anything it throws fails the read instead of crashing the binder thread. */
        fun guard(gatt: BluetoothGatt, body: () -> Unit) {
            try {
                body()
            } catch (e: Exception) {
                finish(gatt, Result.failure(IOException("GATT call failed: $e", e)))
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = guard(gatt) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!gatt.discoverServices()) finish(gatt, Result.failure(IOException("discoverServices refused")))
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    finish(gatt, Result.failure(IOException("GATT disconnected, status $status")))
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = guard(gatt) {
                val ch = gatt.getService(BleIds.SERVICE_UUID)?.getCharacteristic(BleIds.PSM_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null || !gatt.readCharacteristic(ch)) {
                    finish(gatt, Result.failure(IOException("no PSM characteristic (status $status)")))
                }
            }

            // API 33+.
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) = guard(gatt) { onPsm(gatt, value, status) }

            // API 31-32: the only variant there.
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
                guard(gatt) { onPsm(gatt, characteristic.value, status) }

            private fun onPsm(gatt: BluetoothGatt, value: ByteArray?, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.size != 2) {
                    finish(gatt, Result.failure(IOException("PSM read failed (status $status)")))
                } else {
                    finish(gatt, Result.success(((value[0].toInt() and 0xff) shl 8) or (value[1].toInt() and 0xff)))
                }
            }
        }
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            if (done.compareAndSet(false, true)) cont.resumeWithException(IOException("connectGatt returned null"))
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            if (done.compareAndSet(false, true)) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
        }
    }

    private companion object {
        val GATT_TIMEOUT = 15.seconds
    }
}
