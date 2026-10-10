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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import app.kinsight.core.sync.ByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pairing over BLE (spec §6.4) with the patterns of BleRadio and BleConnector: an insecure L2CAP listener whose PSM is
 * readable from a GATT characteristic of the pairing service, and connectable low-latency advertising of the invite
 * tag as service data. The guest scans in the foreground with a callback (the screen is open) and dials the first
 * match. Its own UUIDs and PSM, so the sync radio (BleRadio, its PendingIntent scan) keeps running untouched.
 * Every radio call happens after BlePermissions.missing() came back empty; a permission revoked or Bluetooth turned
 * off later makes the calls throw, which becomes a PairingRadioException here, never a crash on a binder thread.
 */
@SuppressLint("MissingPermission")
class AndroidPairingLink(
    context: Context,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : PairingLink {
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)

    private fun adapter(): BluetoothAdapter {
        val missing = BlePermissions.missing(app)
        if (missing.isNotEmpty()) {
            throw PairingRadioException(
                PairingRadioException.Reason.NO_PERMISSION,
                "missing ${missing.joinToString { it.substringAfterLast('.') }}",
            )
        }
        val adapter = manager?.adapter ?: throw PairingRadioException(PairingRadioException.Reason.RADIO, "no Bluetooth adapter")
        if (!adapter.isEnabled) throw PairingRadioException(PairingRadioException.Reason.BLUETOOTH_OFF, "Bluetooth is off")
        return adapter
    }

    /** Any failure of a radio call as the exception the pairing screen understands. */
    private fun radioFailure(adapter: BluetoothAdapter, what: String, e: Throwable): PairingRadioException = when {
        e is PairingRadioException -> e
        e is SecurityException -> PairingRadioException(PairingRadioException.Reason.NO_PERMISSION, "$what: $e", e)
        runCatching { !adapter.isEnabled }.getOrDefault(false) ->
            PairingRadioException(PairingRadioException.Reason.BLUETOOTH_OFF, "$what: Bluetooth is off", e)
        else -> PairingRadioException(PairingRadioException.Reason.RADIO, "$what: $e", e)
    }

    override suspend fun host(scope: CoroutineScope, tag: ByteArray, onInbound: (ByteStream) -> Unit): PairingHostHandle {
        // C13: the advert is flags + service data (128-bit UUID, tag); a tag that breaks legacy advertising is a bug.
        require(PairingAdvert.advertisementBytes(tag.size) <= PairingAdvert.LEGACY_BYTES) {
            "pairing advert of ${PairingAdvert.advertisementBytes(tag.size)} bytes exceeds legacy advertising"
        }
        val adapter = adapter()
        val session = HostSession(adapter, tag.copyOf(), onInbound, scope)
        // First, so the end of [scope] closes whatever is open from here on, during setup included.
        session.watcher = scope.closeOnCancel(session)
        try {
            session.open()
        } catch (e: CancellationException) {
            session.close()
            throw e
        } catch (e: Exception) {
            session.close()
            throw radioFailure(adapter, "cannot open the pairing radio", e)
        }
        return session
    }

    /**
     * One open invite: L2CAP listener, GATT server with the PSM, the tag advert. Every resource is stored, then [closed]
     * is checked; [close] sets [closed], then reads the fields: one of the two sees the other, so nothing outlives it.
     */
    private inner class HostSession(
        private val adapter: BluetoothAdapter,
        private val tag: ByteArray,
        private val onInbound: (ByteStream) -> Unit,
        private val scope: CoroutineScope,
    ) : PairingHostHandle {
        private val closed = AtomicBoolean(false)
        private val ads = PairingAdvertProblems(scope, log)
        override val problem: StateFlow<RadioProblem?> get() = ads.problem

        @Volatile
        var watcher: Job? = null

        @Volatile
        private var server: BluetoothServerSocket? = null

        @Volatile
        private var gatt: BluetoothGattServer? = null

        @Volatile
        private var advertising = false

        @Volatile
        private var psm = -1

        private val serviceAdded = CompletableDeferred<Int>()

        private val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = ads.onStartSuccess()

            override fun onStartFailure(errorCode: Int) = ads.onStartFailure(errorCode)
        }

        private val gattCallback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (service.uuid == BleIds.PAIRING_SERVICE_UUID) serviceAdded.complete(status)
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val value = byteArrayOf((psm ushr 8).toByte(), psm.toByte())
                val response = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
                try {
                    gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
                } catch (e: Exception) {
                    log("pairing PSM response failed: $e") // binder thread: never let it escape
                }
            }
        }

        private fun ensureOpen() {
            if (closed.get()) throw IOException("pairing host closed during setup (its scope ended)")
        }

        suspend fun open() {
            val listener = adapter.listenUsingInsecureL2capChannel()
            server = listener
            ensureOpen()
            psm = listener.psm
            scope.launchResilientAcceptLoop(
                first = listener,
                accept = { it.accept() },
                reopen = {
                    // A new PSM: the guest reads it over GATT on every dial. close() sees either field write.
                    adapter.listenUsingInsecureL2capChannel().also {
                        server = it
                        psm = it.psm
                        if (closed.get()) runCatching { it.close() }
                    }
                },
                isClosed = closed::get,
                onSocket = { socket -> onInbound(socket.byteStream()) },
                discard = { socket -> socket.close() },
                onGiveUp = { ads.serviceProblem(RadioProblem(RadioProblem.Kind.PAIRING_LISTENER_FAILED, cause = it)) },
                log = log,
                what = "pairing",
            )

            val gattServer = manager.openGattServer(app, gattCallback) ?: throw IOException("openGattServer returned null")
            gatt = gattServer
            ensureOpen()
            val service = BluetoothGattService(BleIds.PAIRING_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    BleIds.PAIRING_PSM_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                ),
            )
            // Advertise only once the service is in the GATT database, else a quick guest reads no PSM. A refusal or
            // silence is shown on the invite screen; the advert still goes out (it may recover, it costs nothing).
            if (!gattServer.addService(service)) {
                ads.serviceProblem(RadioProblem(RadioProblem.Kind.PAIRING_SERVICE_FAILED, cause = "refused"))
            } else {
                when (val status = withTimeoutOrNull(SERVICE_ADDED_TIMEOUT) { serviceAdded.await() }) {
                    BluetoothGatt.GATT_SUCCESS -> Unit
                    null -> ads.serviceProblem(RadioProblem(RadioProblem.Kind.PAIRING_SERVICE_FAILED, cause = "not confirmed"))
                    else -> ads.serviceProblem(RadioProblem(RadioProblem.Kind.PAIRING_SERVICE_FAILED, code = status))
                }
            }
            ensureOpen()

            val advertiser = adapter.bluetoothLeAdvertiser ?: throw IOException("no advertiser")
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid(BleIds.PAIRING_SERVICE_UUID), tag)
                .build()
            val response = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(BleIds.PAIRING_SERVICE_UUID))
                .build()
            ads.begin {
                if (closed.get()) return@begin
                if (advertising) runCatching { advertiser.stopAdvertising(advertiseCallback) } // the retry
                advertiser.startAdvertising(settings, data, response, advertiseCallback)
                advertising = true
                if (closed.get()) runCatching { advertiser.stopAdvertising(advertiseCallback) }.also { advertising = false }
            }
            log("pairing host open, psm=$psm")
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            ads.stop()
            if (advertising) runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
            advertising = false
            runCatching { server?.close() }
            runCatching { gatt?.close() }
            server = null
            gatt = null
            watcher?.cancel()
            log("pairing host closed")
        }
    }

    override suspend fun dial(tag: ByteArray, timeout: Duration): ByteStream {
        val adapter = adapter()
        val device = try {
            withTimeoutOrNull(timeout) { find(adapter, tag.copyOf()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw radioFailure(adapter, "pairing scan failed", e)
        } ?: throw InviteHostNotFoundException("no device advertised the invite within $timeout")
        log("pairing host found")
        try {
            return BleConnector(app, log, BleIds.PAIRING_SERVICE_UUID, BleIds.PAIRING_PSM_UUID).connect(AndroidPeerAddress(device))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw radioFailure(adapter, "pairing connect failed", e)
        } catch (e: Exception) {
            // Bluetooth turned off mid-connect is the user's to fix; any other link failure stays as it is.
            if (runCatching { !adapter.isEnabled }.getOrDefault(false)) throw radioFailure(adapter, "pairing connect failed", e)
            throw e
        }
    }

    /** Waits for a scan-start slot (shared with every pairing dial in the process), then scans for [tag]. */
    private suspend fun find(adapter: BluetoothAdapter, tag: ByteArray): BluetoothDevice {
        while (true) {
            val wait = scanThrottle.tryStart(SystemClock.elapsedRealtime())
            if (wait == 0L) break
            log("pairing scan deferred ${wait}ms (throttle)")
            delay(wait)
        }
        return scan(adapter, tag)
    }

    private suspend fun scan(adapter: BluetoothAdapter, tag: ByteArray): BluetoothDevice = suspendCancellableCoroutine { cont ->
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            cont.resumeWithException(PairingRadioException(PairingRadioException.Reason.RADIO, "no scanner"))
            return@suspendCancellableCoroutine
        }
        val done = AtomicBoolean(false)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(ParcelUuid(BleIds.PAIRING_SERVICE_UUID))
                if (!PairingAdvert.carriesTag(data, tag)) return
                if (!done.compareAndSet(false, true)) return
                runCatching { scanner.stopScan(this) }
                cont.resume(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                if (!done.compareAndSet(false, true)) return
                runCatching { scanner.stopScan(this) }
                cont.resumeWithException(PairingRadioException(PairingRadioException.Reason.RADIO, "pairing scan failed: $errorCode"))
            }
        }
        // Service data matches by prefix: the 8-byte tag is the whole payload (checked exactly in the callback).
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(BleIds.PAIRING_SERVICE_UUID), tag).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: Exception) {
            if (done.compareAndSet(false, true)) {
                runCatching { scanner.stopScan(callback) }
                cont.resumeWithException(radioFailure(adapter, "cannot scan", e))
            }
            return@suspendCancellableCoroutine
        }
        log("pairing scan started")
        // Runs at once if the dial was already cancelled (timeout): the callback scan never outlives the dial.
        cont.invokeOnCancellation {
            if (done.compareAndSet(false, true)) runCatching { scanner.stopScan(callback) }
        }
    }

    private companion object {
        const val TAG = "KinsightPair"
        val SERVICE_ADDED_TIMEOUT = 5.seconds

        /**
         * Android counts scan starts per app (5 per 30 s): pairing takes its slots from the process budget the sync
         * service's PendingIntent scan uses too, and keeps to 2 of the 5 per window.
         */
        val scanThrottle = ScanThrottle.PROCESS.share(maxStarts = 2)
    }
}
