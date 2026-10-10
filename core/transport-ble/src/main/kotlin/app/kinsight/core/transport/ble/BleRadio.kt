package app.kinsight.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import app.kinsight.core.sync.ByteStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/** Stands for a peer's current (rotating) radio address. Never logged as a MAC, never an identity. */
internal class AndroidPeerAddress(val device: BluetoothDevice) : PeerAddress {
    override fun toString() = "ble-peer"
}

internal fun BluetoothSocket.byteStream() = BlockingByteStream(inputStream, outputStream, closer = ::close)

/** The engine of the running service, for the scan receiver (null while the radio is down). */
internal object EngineHolder {
    @Volatile
    var engine: SyncEngine? = null
}

/**
 * The server half of the radio plus advertising and the background scan (patterns proven by the spike on Pixel 8a
 * and Galaxy S23). Created only after BlePermissions.missing() came back empty and the adapter is on.
 * The «Nearby devices» permission can still be revoked (or Bluetooth turned off) while it runs: every radio call then
 * throws (SecurityException, IllegalStateException). [openServers] lets that reach its caller, which tears the radio
 * down; everything else, including the binder and accept-loop threads, logs it and carries on, never crashing the app.
 */
@SuppressLint("MissingPermission")
internal class BleRadio(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val log: (String) -> Unit,
    /** An advertising failure that stands (text), or null once advertising works; called on any thread. */
    private val onAdvertProblem: (RadioProblem?) -> Unit = {},
    /** The L2CAP listener died and could not be reopened (text): the owner restarts the radio; any thread. */
    private val onListenerLost: (String) -> Unit = {},
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)

    // Written on the service's dispatcher, read on binder threads (GATT callback) and by close().
    @Volatile
    private var gattServer: BluetoothGattServer? = null

    @Volatile
    private var l2cap: BluetoothServerSocket? = null

    @Volatile
    private var psm = -1

    @Volatile
    private var advertising = false

    /** The radio's scope (from [openServers]): the advertising retry runs in it and dies with it. */
    @Volatile
    private var scope: CoroutineScope? = null

    private val advertiseRetry = AdvertiseRetry()

    // The advert the retry repeats; written by advertise() before it starts, read on the binder thread.
    @Volatile
    private var lastAdvert: Pair<AdvertPayload, Boolean>? = null

    /** Set by [close]: a call racing the teardown (another thread) must not start a scan or an advert again. */
    @Volatile
    private var closed = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            log("advertising mode=${settingsInEffect.mode}")
            onAdvertProblem(null)
        }

        override fun onStartFailure(errorCode: Int) {
            when (advertiseRetry.onFailure(errorCode)) {
                AdvertiseRetry.Next.OK -> {
                    log("advertising already started")
                    onAdvertProblem(null)
                }
                AdvertiseRetry.Next.RETRY -> {
                    log("advertising failed: $errorCode, retrying in ${ADVERTISE_RETRY_MS / 1000} s")
                    onAdvertProblem(RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, retrying = true, code = errorCode))
                    val gen = advertiseRetry.current()
                    val again = lastAdvert
                    scope?.launch {
                        delay(ADVERTISE_RETRY_MS)
                        if (again != null && advertiseRetry.isCurrent(gen)) start(again.first, again.second)
                    }
                }
                AdvertiseRetry.Next.GIVE_UP -> {
                    log("advertising failed: $errorCode")
                    onAdvertProblem(RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, code = errorCode))
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = byteArrayOf((psm ushr 8).toByte(), psm.toByte())
            val response = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            try {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
            } catch (e: Exception) {
                log("PSM read response failed: $e") // binder thread: an escaping exception would crash the app
            }
        }
    }

    /**
     * The insecure L2CAP listener first (its PSM goes into the characteristic), then the GATT service. Throws if
     * either cannot be opened; the caller then calls [close]. A listener whose accept() fails later is closed and
     * reopened (launchResilientAcceptLoop: 1 s, 5 s, 30 s, 2 min, a new PSM served from the same characteristic); if
     * that keeps failing, onListenerLost tells the owner, which restarts the radio as a whole.
     */
    fun openServers(scope: CoroutineScope, onInbound: (ByteStream) -> Unit) {
        this.scope = scope
        val server = adapter.listenUsingInsecureL2capChannel()
        l2cap = server
        psm = server.psm
        scope.launchResilientAcceptLoop(
            first = server,
            accept = { it.accept() },
            reopen = {
                // The GATT characteristic is answered from [psm] on every read: the new value is live at once.
                adapter.listenUsingInsecureL2capChannel().also {
                    l2cap = it
                    psm = it.psm
                    if (closed) runCatching { it.close() } // close() sets closed before it reads l2cap
                    log("l2cap listener open, psm=${it.psm}")
                }
            },
            isClosed = { closed },
            onSocket = { socket ->
                val stream = try {
                    socket.byteStream()
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    throw e
                }
                onInbound(stream)
            },
            discard = { socket -> socket.close() },
            onGiveUp = onListenerLost,
            log = log,
        )
        val gatt = manager.openGattServer(context, gattCallback) ?: throw IOException("openGattServer returned null")
        gattServer = gatt
        val service = BluetoothGattService(BleIds.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BleIds.PSM_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ),
        )
        if (!gatt.addService(service)) log("addService refused") // as in the spike: not fatal, peers just cannot read the PSM
        log("servers open, psm=$psm")
    }

    /**
     * (Re)starts legacy connectable advertising: flags + our service data (31 bytes exactly), the 128-bit service UUID
     * list in the scan response (it does not fit in the advertisement). A failure reported by the stack is retried
     * once after [ADVERTISE_RETRY_MS] (a newer advert cancels that retry) and handed to onAdvertProblem. Never throws.
     */
    fun advertise(payload: AdvertPayload, fast: Boolean) {
        if (closed) return
        lastAdvert = payload to fast
        advertiseRetry.fresh()
        start(payload, fast)
    }

    private fun start(payload: AdvertPayload, fast: Boolean) {
        if (closed) return
        try {
            val advertiser = adapter.bluetoothLeAdvertiser ?: return log("no advertiser")
            if (advertising) runCatching { advertiser.stopAdvertising(advertiseCallback) }
            advertising = false
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(
                    if (fast) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
                )
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid(BleIds.SERVICE_UUID), payload.encode())
                .build()
            val response = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(BleIds.SERVICE_UUID))
                .build()
            advertiser.startAdvertising(settings, data, response, advertiseCallback)
            advertising = true
            // close() sets closed before it stops: one of us sees the other, so no advert outlives the radio.
            if (closed) runCatching { advertiser.stopAdvertising(advertiseCallback) }.also { advertising = false }
        } catch (e: Exception) {
            log("advertise failed: $e")
            onAdvertProblem(RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, cause = e::class.simpleName))
        }
    }

    /**
     * (Re)starts the background scan delivered through ScanReceiver's PendingIntent. Two OR-ed filters (the UUID list
     * from the scan response, the service data from the advertisement). Normal mode batches results in the controller
     * for [BATCH_DELAY_MS] when supported (each delivery wakes the app); fast mode reports at once. Never throws.
     * Returns the startScan result: 0 when the scan runs, [SCAN_NOT_STARTED] when it was not even attempted or threw.
     */
    fun startScan(fast: Boolean): Int {
        if (closed) return SCAN_NOT_STARTED
        return try {
            val scanner = adapter.bluetoothLeScanner ?: return SCAN_NOT_STARTED.also { log("no scanner") }
            val pending = ScanReceiver.pendingIntent(context)
            runCatching { scanner.stopScan(pending) }
            val filters = listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(BleIds.SERVICE_UUID)).build(),
                ScanFilter.Builder().setServiceData(ParcelUuid(BleIds.SERVICE_UUID), ByteArray(0), ByteArray(0)).build(),
            )
            val delay = if (!fast && adapter.isOffloadedScanBatchingSupported) BATCH_DELAY_MS else 0L
            val settings = ScanSettings.Builder()
                .setScanMode(if (fast) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(delay)
                .build()
            val rc = scanner.startScan(filters, settings, pending)
            if (closed) runCatching { scanner.stopScan(pending) } // same pairing as for the advert
            log("scan started fast=$fast reportDelay=$delay rc=$rc")
            rc
        } catch (e: Exception) {
            log("scan failed: $e")
            SCAN_NOT_STARTED
        }
    }

    /** Stops everything; never throws, safe to call twice. */
    fun close() {
        closed = true
        runCatching { adapter.bluetoothLeScanner?.stopScan(ScanReceiver.pendingIntent(context)) }
        if (advertising) runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
        runCatching { l2cap?.close() }
        runCatching { gattServer?.close() }
        l2cap = null
        gattServer = null
    }

    companion object {
        const val BATCH_DELAY_MS = 30_000L
        const val ADVERTISE_RETRY_MS = 5_000L
        const val SCAN_NOT_STARTED = -1
    }
}
