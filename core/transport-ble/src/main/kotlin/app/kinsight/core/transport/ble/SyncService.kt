package app.kinsight.core.transport.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import app.kinsight.core.keystore.DeviceIdentity
import app.kinsight.core.usage.UsageGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Foreground service of type connectedDevice (spec §8): advertising, GATT and L2CAP servers, the background scan and
 * the sync engine. Started from the UI, on BOOT_COMPLETED and MY_PACKAGE_REPLACED (only when a group exists, the
 * «Nearby devices» permissions are granted and the user has not stopped it). Without a group it stops itself; with a
 * key storage or group file error, a missing permission or Bluetooth off it stays in the foreground, idle, and says so
 * in the notification (no crash, so no START_STICKY restart loop). A radio that will not open with Bluetooth on
 * (RADIO_ERROR) is retried on [RadioRetrySchedule]; a failed scan start or a scan error is retried the same way, under
 * the scan throttle, and a standing scan or advertising failure is the RUNNING detail.
 *
 * Play policy (docs/research/2026-10-10-play-policy.md): the notification has a Stop action (remembered across boots
 * until the next start from the UI), a dismissed notification is posted again, and the snapshot reports whether
 * notifications can be seen at all. The group-visible events for both are stage 4.
 *
 * Concurrency: everything runs on one serial dispatcher, and every operation that can start or stop the radio
 * (start command, Stop, adapter broadcasts, syncNow) holds [controlLock] for its whole run, suspension points
 * included, so two of them never interleave (no second radio, no Stop racing a start). onDestroy tears the radio down
 * synchronously; a coroutine that was mid-start when the service died sees [destroyed] and closes its own radio.
 */
@SuppressLint("MissingPermission") // radio calls happen only after BlePermissions.missing() is empty
class SyncService : Service() {
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val crashGuard = CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught in the sync service", e) }

    /** Parent of the engine scope too: an exception escaping any coroutine here is logged, never a process crash. */
    private val scope = CoroutineScope(SupervisorJob() + serial + crashGuard)

    /** Not reentrant: taken only by the entry points (launch sites), never by the helpers they call. */
    private val controlLock = Mutex()

    // Written under controlLock on the serial dispatcher; volatile because onDestroy (main thread) tears them down.
    @Volatile
    private var radioScope: CoroutineScope? = null

    @Volatile
    private var radio: BleRadio? = null

    @Volatile
    private var engine: SyncEngine? = null

    @Volatile
    private var destroyed = false

    // Serial dispatcher only (stopping is also read on the main thread by repostForeground).
    @Volatile
    private var stopping = false
    private var fastUntilMono = 0L
    private var fastEnd: Job? = null
    private val scanThrottle = ScanThrottle.PROCESS // shared with pairing: Android counts scan starts per app
    private var lastVisible: Boolean? = null // serial dispatcher only
    private var retiredTried = false // serial dispatcher only: the other role's channel was asked to go once
    private var scanWanted: Boolean? = null
    private var scanActive: Boolean? = null
    private var scanRetry: Job? = null
    private val scanRecovery = ScanRecovery()
    private val radioRetrySchedule = RadioRetrySchedule()
    private var radioRetry: Job? = null

    /** Shown as the RUNNING detail (diagnostics) while they stand; cleared when the radio stops. */
    private var scanProblem: RadioProblem? = null
    private var advertProblem: RadioProblem? = null

    /** The problems [ServiceSnapshot.detail] names while RUNNING (serial dispatcher only). */
    private var shownProblems: List<RadioProblem> = emptyList()

    // Main thread only.
    private var foreground = false
    private val mainHandler by lazy { android.os.Handler(mainLooper) }

    @Volatile
    private var snapshot = ServiceSnapshot(ServiceState.STARTING)

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> scope.launch { controlLock.withLock { reconcile(fast = false) } }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> scope.launch {
                    controlLock.withLock {
                        // A running radio, or one that failed to open (RADIO_ERROR: its retry is cancelled), goes to
                        // BLUETOOTH_OFF; a storage or permission problem stays on screen.
                        val radioState = snapshot.state == ServiceState.RUNNING || snapshot.state == ServiceState.RADIO_ERROR
                        if (!stopping && radioState) bluetoothOff()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // A child's pause shows in the notification at once (the switch lives in the usage settings, not here).
        scope.launch {
            UsageGraph.settings(applicationContext).settings
                .map { !it.sharing && it.consentAtMs != null }
                .distinctUntilChanged()
                .catch { e -> log("sharing state unavailable: $e") }
                .collect { paused ->
                    snapshot = snapshot.copy(sharingPaused = paused)
                    if (snapshot.state != ServiceState.STARTING) publish()
                }
        }
        // One sync status for both paths: a cloud run that finished (or the cloud turned on or off) updates the line.
        scope.launch {
            SyncGraph.cloudSync(applicationContext)
                .distinctUntilChanged()
                .catch { e -> log("cloud state unavailable: $e") }
                .collect { c ->
                    snapshot = snapshot.copy(cloudOn = c.on, cloudLastSyncMs = c.lastSyncMs)
                    if (snapshot.state != ServiceState.STARTING) publish()
                }
        }
        // At local midnight today's «в 15:41» becomes yesterday's: rebuild the line so it gets its date.
        scope.launch {
            while (true) {
                delay(StatusText.untilNextMidnightMs(System.currentTimeMillis(), ZoneId.systemDefault()) + MIDNIGHT_SLACK_MS)
                if (snapshot.state != ServiceState.STARTING) publish()
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        // A system broadcast still reaches a NOT_EXPORTED runtime receiver; nobody else can fake it.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(adapterReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(adapterReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fresh = !foreground
        val action = intent?.action
        if (action == ACTION_STOP) {
            // Sent by startService from the notification: no startForeground owed, and none is wanted.
            instance = this
            scope.launch { controlLock.withLock { stopByUser(startId) } }
            return START_NOT_STICKY
        }
        try {
            // Every other command: a startForegroundService() must be answered, and it re-posts a dismissed notification.
            startForeground(NOTIFICATION_ID, notification(snapshot), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            foreground = true
        } catch (e: Exception) {
            // API 34+ needs a granted Bluetooth permission for this type; a background start is refused too.
            Log.w(TAG, "cannot go foreground", e)
            if (!foreground) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        instance = this
        if (action == ACTION_REPOST && !fresh) {
            // After a Stop that a newer command (this one) kept alive, a repost must not revive the service.
            scope.launch {
                controlLock.withLock {
                    var stopped = false
                    quietly("read the stop flag") { stopped = SyncGraph.state(applicationContext).stoppedByUser() }
                    if (stopped) stopNow(startId) else publish()
                }
            }
            return START_STICKY
        }
        // A REPOST that reaches a fresh instance is an ordinary start (the stop flag is honoured).
        val byUser = intent?.getBooleanExtra(EXTRA_BY_USER, false) == true
        val fast = action == ACTION_SYNC_NOW
        scope.launch { controlLock.withLock { onStart(byUser, fast, startId) } }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        instance = null
        scope.cancel()
        runCatching { unregisterReceiver(adapterReceiver) }
        // Synchronous, so nothing of this instance outlives it and nothing of a next instance gets torn down.
        val r = radio
        val e = engine
        radioScope?.cancel()
        r?.close()
        radioScope = null
        radio = null
        engine = null
        if (e != null && EngineHolder.engine === e) EngineHolder.engine = null
        if (r != null) RotationReceiver.cancel(applicationContext)
        SyncGraph.publish(ServiceSnapshot(ServiceState.STOPPED, lastSyncWallMs = snapshot.lastSyncWallMs))
        super.onDestroy()
    }

    /** Debug and UI: one minute of fast advertising and scanning without the cooldown (spec §8 «Быстрый режим»). */
    fun syncNow(): String {
        scope.launch { controlLock.withLock { reconcile(fast = true) } }
        return "fast mode for ${FAST_MS / 1000} s"
    }

    /** [requestFast] on a running instance: the same fast mode as [syncNow], but never after the user's Stop. */
    private fun fastUnlessStopped() {
        scope.launch {
            controlLock.withLock {
                var stopped = false
                quietly("read the stop flag") { stopped = SyncGraph.state(applicationContext).stoppedByUser() }
                if (stopped) log("stopped by the user: no fast mode") else reconcile(fast = true)
            }
        }
    }

    private fun onRotationTick() {
        scope.launch { engine?.refreshAdvert() }
        onNotificationCheck() // the periodic visibility check
    }

    /**
     * Notifications may have been allowed since the last post (the app came to the front, an onboarding step closed,
     * the periodic tick): post the notification again, and tell the visibility tracker what the user sees now. A
     * foreground notification posted before POST_NOTIFICATIONS was granted is not shown once it is (emulator run
     * 2026-10-10, finding 3), so a hidden→visible change also re-enters the foreground (publish).
     */
    private fun onNotificationCheck() {
        scope.launch {
            if (stopping || destroyed) return@launch
            publish()
            quietly("notification check") {
                SyncGraph.onNotificationVisibility(applicationContext, SyncGraph.notificationVisibleNow(applicationContext))
            }
        }
    }

    /** startForeground again with the current notification (main thread, where [foreground] lives); never throws. */
    private fun repostForeground() {
        mainHandler.post {
            if (!foreground || destroyed || stopping) return@post
            try {
                startForeground(NOTIFICATION_ID, notification(snapshot), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                log("notification posted again")
            } catch (e: Exception) {
                log("cannot post the notification again: $e")
            }
        }
    }

    /** A scan error delivered to ScanReceiver (EXTRA_ERROR_CODE): the scan is not running, retry it. */
    private fun onScanError(code: Int) {
        scope.launch {
            if (radio == null) return@launch
            scanActive = null
            scanProblem = RadioProblem(RadioProblem.Kind.SCAN_FAILED, retrying = true, code = code)
            refreshRadioDetail()
            if (scanRetry?.isActive != true) scheduleScanRetry(scanRecovery.onError())
        }
    }

    /** A batch delivered without an error: the scan works, so its backoff and any scan problem are cleared. */
    private fun onScanOk() {
        scope.launch {
            if (radio == null) return@launch
            scanRecovery.onDelivered()
            if (scanProblem != null) {
                scanProblem = null
                refreshRadioDetail()
            }
        }
    }

    /** A start from the UI clears the user's Stop; any other start (boot, update, adb) honours it. Under the lock. */
    private suspend fun onStart(byUser: Boolean, fast: Boolean, startId: Int) {
        val state = SyncGraph.state(applicationContext)
        if (byUser) {
            stopping = false // a Stop that ran just before this start left the service alive for it
            quietly("record the stopped interval") { SyncGraph.onUserStart(applicationContext) }
        } else {
            var stopped = false
            quietly("read the stop flag") { stopped = state.stoppedByUser() }
            if (stopped) {
                log("stopped by the user: not starting")
                return stopNow(startId)
            }
        }
        reconcile(fast, startId)
    }

    /** Under the lock. A start command newer than [startId] keeps the service (its own onStart decides). */
    private suspend fun stopByUser(startId: Int) {
        stopRadio()
        quietly("persist the stop flag") { SyncGraph.onUserStop(applicationContext) }
        log("stopped by the user")
        stopNow(startId)
    }

    /**
     * Stops the radio and the service unless a newer start command arrived ([startId], null = stop regardless). When
     * the service really stops, the system removes the foreground notification with it.
     */
    private fun stopNow(startId: Int? = null) {
        stopRadio()
        stopping = true
        val stopped = if (startId == null) {
            stopSelf()
            true
        } else {
            stopSelfResult(startId)
        }
        if (stopped) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopping = false
            log("stop skipped: a newer start is queued")
        }
    }

    /** Under the lock. */
    private suspend fun reconcile(fast: Boolean, startId: Int? = null) {
        if (stopping || destroyed) return
        val c = applicationContext
        val group = try {
            SyncGraph.groups(c).read()
        } catch (e: GroupStoreException) {
            return problem(ServiceState.STORAGE_ERROR, e.message)
        }
        if (group == null) {
            show(ServiceState.NO_GROUP)
            return stopNow(startId)
        }
        // The role before any early return below (Bluetooth off, no permission, storage error): a child's phone never
        // shows Stop; until this read succeeds the role stays unknown, which fails closed (StatusText.childPhone).
        refreshPeers()
        try {
            DeviceIdentity.get(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return problem(ServiceState.STORAGE_ERROR, "${e::class.simpleName}: ${e.message}")
        }
        val missing = BlePermissions.missing(c)
        if (missing.isNotEmpty()) {
            quietly("nearby off") { SyncGraph.onNearbyPermission(c, granted = false) }
            return problem(ServiceState.NO_PERMISSION, missing.joinToString { it.substringAfterLast('.') })
        }
        quietly("nearby on") { SyncGraph.onNearbyPermission(c, granted = true) }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) return bluetoothOff()
        if (!startRadio(adapter)) return
        if (fast) goFast()
    }

    private suspend fun problem(state: ServiceState, detail: String?) {
        stopRadio()
        show(state, detail)
    }

    private suspend fun bluetoothOff(detail: String? = null) {
        stopRadio()
        radioRetrySchedule.reset() // a radio error before Bluetooth went off starts over once it is back
        quietly("bluetooth-off start") { SyncGraph.onBluetoothOff(applicationContext) }
        show(ServiceState.BLUETOOTH_OFF, detail)
    }

    /** Under the lock. True when the radio runs afterwards. */
    private suspend fun startRadio(adapter: BluetoothAdapter): Boolean {
        if (radio != null) {
            showRunning()
            return true
        }
        val c = applicationContext
        quietly("bluetooth-off event") { SyncGraph.onBluetoothOn(c) }
        if (stopping || destroyed) return false
        val rs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
        lateinit var r: BleRadio
        val e = SyncEngine(
            scope = rs,
            context = { SyncGraph.syncContext(c) },
            link = BleConnector(c, ::log),
            prepare = { SyncGraph.prepare(c) },
            monoClock = SystemClock::elapsedRealtime,
            log = ::log,
            onLinkExhausted = { restartRadio({ r }, "dials keep failing without radio resources") },
        )
        r = BleRadio(
            c,
            adapter,
            ::log,
            onAdvertProblem = { problem ->
                scope.launch {
                    if (radio !== r) return@launch // a callback of a radio already torn down
                    advertProblem = problem
                    refreshRadioDetail()
                }
            },
            onListenerLost = { why -> listenerLost(r, why) },
        )
        try {
            r.openServers(rs) { stream -> e.onInbound(stream) }
        } catch (ex: Exception) {
            rs.cancel()
            r.close()
            log("cannot open servers: $ex")
            // Bluetooth going off under us: the ordinary off path (tracker included). With Bluetooth on it is a radio
            // error, never a BLUETOOTH_OFF interval the family would see.
            if (adapter.isEnabled) {
                problem(ServiceState.RADIO_ERROR, "cannot open servers: ${ex.message}")
                scheduleRadioRetry()
            } else {
                bluetoothOff()
            }
            return false
        }
        radio = r
        engine = e
        radioScope = rs
        EngineHolder.engine = e
        // Pairs with onDestroy: it sets destroyed before reading radio, we wrote radio before reading destroyed.
        if (destroyed) return abandon(r, rs, e)
        rs.launch { e.advert.filterNotNull().collect { r.advertise(it, fast = isFast()) } }
        rs.launch { e.status.collect { onSyncStatus(it) } }
        e.refreshAdvert()
        if (radio !== r || destroyed) return abandon(r, rs, e) // torn down while the advert was computed
        requestScan(fast = false)
        RotationReceiver.schedule(c)
        refreshPeers()
        radioRetry?.cancel() // started by another path (adapter on, a start command) while a retry waited
        radioRetry = null
        radioRetrySchedule.reset()
        showRunning()
        rs.launch { quietly("heartbeat") { SyncGraph.emitHeartbeatIfDue(c) } }
        return true
    }

    /**
     * Our own side is stuck (LinkExhaustion): close the radio and open it again at once, under the lock, unless it was
     * already replaced or stopped. The fast window is kept (goFast again if it still runs).
     */
    private fun restartRadio(which: () -> BleRadio, why: String) {
        scope.launch {
            controlLock.withLock {
                val r = which()
                if (radio !== r || stopping || destroyed) return@withLock
                log("radio restart: $why")
                val wasFast = isFast()
                stopRadio()
                reconcile(fast = wasFast)
            }
        }
    }

    /**
     * The L2CAP listener died and the radio could not reopen it (1 s, 5 s, 30 s, 2 min): RADIO_ERROR, and the whole
     * radio is started again on [RadioRetrySchedule], like a radio that would not open.
     */
    private fun listenerLost(r: BleRadio, why: String) {
        scope.launch {
            controlLock.withLock {
                if (radio !== r || stopping || destroyed) return@withLock
                problem(ServiceState.RADIO_ERROR, why)
                scheduleRadioRetry()
            }
        }
    }

    /** Closes a radio this coroutine opened but no longer owns (close() is idempotent). */
    private fun abandon(r: BleRadio, rs: CoroutineScope, e: SyncEngine): Boolean {
        rs.cancel()
        r.close()
        if (EngineHolder.engine === e) EngineHolder.engine = null
        if (radio === r) {
            radio = null
            engine = null
            radioScope = null
        }
        return false
    }

    /**
     * RADIO_ERROR with the adapter on: the next start attempt after [RadioRetrySchedule] (5 s, 30 s, 2 min, then every
     * 15 min), through the normal start path under the lock. stopRadio cancels it, so Stop, onDestroy (scope),
     * Bluetooth off and any other problem end the retries; the job clears its own handle first so the start it runs
     * cannot cancel itself.
     */
    private fun scheduleRadioRetry() {
        radioRetry?.cancel()
        val wait = radioRetrySchedule.next()
        log("radio retry in ${wait / 1000} s")
        radioRetry = scope.launch {
            delay(wait)
            controlLock.withLock {
                radioRetry = null
                reconcile(fast = false)
            }
        }
    }

    /** Cancelling the scope and closing the radio always go together: the accept loop ends only on close(). */
    private fun stopRadio() {
        radioRetry?.cancel()
        radioRetry = null
        val r = radio
        val e = engine
        radioScope?.cancel()
        radioScope = null
        r?.close()
        radio = null
        engine = null
        if (e != null && EngineHolder.engine === e) EngineHolder.engine = null
        scanWanted = null
        scanActive = null
        scanRetry = null
        scanRecovery.reset()
        scanProblem = null
        advertProblem = null
        fastEnd = null
        fastUntilMono = 0L
        if (r != null) RotationReceiver.cancel(applicationContext)
    }

    private fun isFast() = SystemClock.elapsedRealtime() < fastUntilMono

    /** Repeated requests only extend the window: no scan or advert restart while fast mode is already on. */
    private fun goFast() {
        val e = engine ?: return
        val r = radio ?: return
        val wasFast = isFast()
        fastUntilMono = SystemClock.elapsedRealtime() + FAST_MS
        e.setFast(fastUntilMono)
        if (!wasFast) {
            requestScan(fast = true)
            e.advert.value?.let { r.advertise(it, fast = true) }
        }
        fastEnd?.cancel()
        fastEnd = radioScope?.launch {
            delay(FAST_MS)
            requestScan(fast = false)
            engine?.advert?.value?.let { radio?.advertise(it, fast = false) }
        }
    }

    /** Asks for a scan mode; the start goes out now or, under the throttle, once a slot frees (latest wish wins). */
    private fun requestScan(fast: Boolean) {
        scanWanted = fast
        if (scanRetry?.isActive == true) return
        applyScan()
    }

    private fun applyScan() {
        val r = radio ?: return
        val want = scanWanted ?: return
        if (scanActive == want) return
        val now = SystemClock.elapsedRealtime()
        val wait = scanThrottle.tryStart(now) // takes the slot when 0: pairing scans count against the same budget
        if (wait > 0) {
            log("scan restart deferred ${wait}ms (throttle)")
            scanRetry = radioScope?.launch {
                delay(wait)
                scanRetry = null
                applyScan()
            }
            return
        }
        val rc = r.startScan(want)
        val retryIn = scanRecovery.onStarted(rc)
        if (retryIn == null) {
            scanActive = want // accepted; only a delivered batch (onScanOk) proves it works and clears the problem
        } else {
            scanActive = null
            scanProblem = RadioProblem(RadioProblem.Kind.SCAN_START_FAILED, retrying = true, code = rc)
            refreshRadioDetail()
            scheduleScanRetry(retryIn)
        }
    }

    /** A failed scan start: again after the retry schedule, never sooner than the throttle allows. */
    private fun scheduleScanRetry(backoffMs: Long) {
        val wait = maxOf(backoffMs, scanThrottle.waitMs(SystemClock.elapsedRealtime()))
        log("scan retry in ${wait}ms")
        scanRetry = radioScope?.launch {
            delay(wait)
            scanRetry = null
            applyScan()
        }
    }

    private fun radioProblems(): List<RadioProblem> = listOfNotNull(scanProblem, advertProblem)

    private suspend fun showRunning() {
        val p = radioProblems()
        show(ServiceState.RUNNING, RadioProblem.detailOf(p))
        shownProblems = p
    }

    /** While RUNNING, the typed problems (and the detail naming them, for diagnostics) are the standing radio failures. */
    private fun refreshRadioDetail() {
        if (snapshot.state != ServiceState.RUNNING) return
        val p = radioProblems()
        if (shownProblems == p) return
        val d = RadioProblem.detailOf(p)
        shownProblems = p
        snapshot = snapshot.copy(detail = d)
        log("radio detail: ${d ?: "none"}")
        publish()
    }

    private suspend fun onSyncStatus(s: SyncStatus) {
        val last = s.lastSyncWallMs
        if (last != null && last != snapshot.lastSyncWallMs) quietly("last sync") { SyncGraph.state(applicationContext).setLastSyncMs(last) }
        val sessionEnded = s.finishedSessions != snapshot.sync.finishedSessions
        snapshot = snapshot.copy(sync = s, lastSyncWallMs = last ?: snapshot.lastSyncWallMs)
        if (sessionEnded) refreshPeers() // a session may have brought membership changes
        val peer = s.lastPeer
        val at = s.lastSyncWallMs
        if (sessionEnded && s.lastProblem == null && peer != null && at != null) {
            quietly("peer seen") { SyncGraph.onSessionCompleted(applicationContext, peer, at) }
        }
        if (sessionEnded) runCatching { SyncGraph.onSessionFinished(applicationContext) }
        publish()
    }

    private suspend fun refreshPeers() {
        quietly("peer names") {
            val p = SyncGraph.notificationPeers(applicationContext)
            snapshot = snapshot.copy(peers = p.names, peersAreParents = p.parentsOnly)
        }
    }

    private suspend fun show(state: ServiceState, detail: String? = null) {
        if (snapshot.lastSyncWallMs == null) {
            quietly("read last sync") {
                val last = SyncGraph.state(applicationContext).lastSyncMs()
                snapshot = snapshot.copy(lastSyncWallMs = snapshot.lastSyncWallMs ?: last)
            }
        }
        shownProblems = emptyList()
        snapshot = snapshot.copy(state = state, detail = detail)
        log("state $state${detail?.let { ": $it" } ?: ""}")
        publish()
    }

    /** Never after onDestroy or once stopping: a late notify would leave an orphan notification behind. */
    private fun publish() {
        if (destroyed) return
        val nm = getSystemService(NotificationManager::class.java)
        snapshot = snapshot.copy(
            notificationsEnabled = nm.areNotificationsEnabled(),
            channelImportance = nm.getNotificationChannel(SyncGraph.channelFor(nm, snapshot))?.importance,
        )
        SyncGraph.publish(snapshot)
        val visible = StatusText.notificationVisible(snapshot)
        if (visible != null && visible != lastVisible) {
            if (visible && lastVisible == false) repostForeground()
            lastVisible = visible
            scope.launch { quietly("notification visibility") { SyncGraph.onNotificationVisibility(applicationContext, visible) } }
        }
        if (stopping) return
        val retired = ServiceChannels.retired(snapshot.peersAreParents)?.takeIf { nm.getNotificationChannel(it) != null }
        if (retired == null || retiredTried || !foreground) {
            nm.notify(NOTIFICATION_ID, notification(snapshot))
            return
        }
        // Once the role is known the other channel goes. Android refuses to delete the channel its foreground
        // notification was started on, so the service goes foreground again on the new channel first. Tried once per
        // service instance: a refusal is logged once, not on every publish.
        retiredTried = true
        try {
            startForeground(NOTIFICATION_ID, notification(snapshot), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            nm.deleteNotificationChannel(retired)
        } catch (e: Exception) {
            log("channel $retired not deleted: $e")
            nm.notify(NOTIFICATION_ID, notification(snapshot))
        }
    }

    /** The channel [s] posts on, created when missing: the child's one as before, a parent's at the lowest importance. */
    private fun channel(s: ServiceSnapshot): String {
        val nm = getSystemService(NotificationManager::class.java)
        val id = SyncGraph.channelFor(nm, s)
        if (nm.getNotificationChannel(id) == null) {
            val importance = if (id == ServiceChannels.PARENT) NotificationManager.IMPORTANCE_MIN else NotificationManager.IMPORTANCE_LOW
            nm.createNotificationChannel(
                NotificationChannel(id, getString(R.string.kinsight_channel_service), importance).apply { setShowBadge(false) },
            )
        }
        return id
    }

    private fun statusText(line: StatusLine): String = when (line.kind) {
        StatusLine.Kind.STARTING -> getString(R.string.kinsight_status_starting)
        StatusLine.Kind.STORAGE_ERROR -> getString(R.string.kinsight_status_storage_error)
        StatusLine.Kind.NO_PERMISSION -> getString(R.string.kinsight_status_no_permission)
        StatusLine.Kind.BLUETOOTH_OFF -> getString(R.string.kinsight_status_bluetooth_off)
        StatusLine.Kind.RADIO_ERROR -> getString(R.string.kinsight_status_radio_error)
        StatusLine.Kind.PEER_NEEDS_UPDATE -> getString(R.string.kinsight_status_peer_needs_update)
        StatusLine.Kind.NEVER_SYNCED -> getString(R.string.kinsight_never_synced)
        StatusLine.Kind.SYNCED -> {
            val at = line.lastSyncWallMs ?: 0L
            val time = DateUtils.formatDateTime(this, at, DateUtils.FORMAT_SHOW_TIME)
            if (StatusText.sameDay(at, System.currentTimeMillis(), ZoneId.systemDefault())) {
                getString(R.string.kinsight_synced_at, time)
            } else {
                // «Синхронизировано 17 октября в 15:41»: the date without the year, as absoluteMoment shows it.
                val date = DateUtils.formatDateTime(this, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR)
                getString(R.string.kinsight_synced_on, date, time)
            }
        }
    }

    private fun partText(part: NotificationPart): String = when (part) {
        is NotificationPart.Status -> statusText(part.line)
        NotificationPart.ChildTitle -> getString(R.string.kinsight_child_title)
        NotificationPart.Paused -> getString(R.string.kinsight_sharing_paused)
        is NotificationPart.Peers -> getString(
            if (part.line == StatusText.PeerLine.VISIBLE_TO) R.string.kinsight_shared_with else R.string.kinsight_peers,
            part.names.joinToString(", "),
        )
    }

    private fun notification(s: ServiceSnapshot): Notification {
        val content = StatusText.content(s)
        val lines = content.lines.map(::partText)
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val stop = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.kinsight_action_stop),
            selfIntent(ACTION_STOP, REQUEST_STOP),
        ).build()
        val builder = Notification.Builder(this, channel(s))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(partText(content.title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setDeleteIntent(selfIntent(ACTION_REPOST, REQUEST_REPOST)) // swiped away (API 34+): it comes back
        // A parent's notification is the title alone; a child's lists its lines under the title.
        if (lines.isNotEmpty()) {
            builder.setContentText(lines.joinToString(" · ")).setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
        }
        if (content.stop) builder.addAction(stop)
        return builder.build()
    }

    private fun selfIntent(action: String, request: Int): PendingIntent = PendingIntent.getService(
        this,
        request,
        Intent(this, SyncService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what failed: $e")
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    companion object {
        const val ACTION_SYNC_NOW = "app.kinsight.SYNC_NOW"
        const val ACTION_STOP = "app.kinsight.STOP_SYNC"
        internal const val ACTION_REPOST = "app.kinsight.REPOST_NOTIFICATION"

        /** The reason a start from the app's own screen passes: only it clears the user's Stop. */
        const val REASON_UI = "ui"

        /** Opening the app: starts the service, but honours the user's Stop (only REASON_UI clears it). */
        const val REASON_APP_OPEN = "app_open"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_BY_USER = "by_user"
        private const val TAG = "KinsightSync"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_STOP = 10
        private const val REQUEST_REPOST = 11

        /** The midnight rebuild runs a little after midnight, so the new day has begun on every clock read. */
        private const val MIDNIGHT_SLACK_MS = 1_000L
        /** One fast-mode window after «Sync now» (also how long Overview says it is looking, U6). */
        const val FAST_MS = 60_000L

        @Volatile
        var instance: SyncService? = null
            private set

        /**
         * Safe from anywhere: without the «Nearby devices» permissions nothing starts (on API 34+ startForeground
         * would throw), and a refused start (from the background) is logged, never thrown. False in both cases.
         */
        fun start(context: Context, reason: String, fast: Boolean = false): Boolean {
            val missing = BlePermissions.missing(context)
            if (missing.isNotEmpty()) {
                Log.w(TAG, "not starting the sync service ($reason): missing ${missing.joinToString { it.substringAfterLast('.') }}")
                return false
            }
            val intent = Intent(context, SyncService::class.java)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_BY_USER, reason == REASON_UI)
            if (fast) intent.action = ACTION_SYNC_NOW
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "cannot start the sync service ($reason)", e)
                false
            }
        }

        /**
         * One fast-mode window (as «Sync now») without a UI start: a running instance goes fast in process (no
         * foreground-service start from the background), otherwise a REASON_APP_OPEN start. Both honour the user's
         * Stop. For IntegrityFastSync (core/data); the fast-mode dial rules are ConnectPolicy's as always. False when
         * nothing could start (see [start]), and when the running instance cannot go fast ([acceptsFast]: it is stopping
         * or gone, or its radio is not up), so the caller does not count a window that never went out.
         */
        fun requestFast(context: Context): Boolean {
            val running = instance ?: return start(context, REASON_APP_OPEN, fast = true)
            if (!acceptsFast(running.stopping, running.destroyed, running.snapshot.state)) return false
            running.fastUnlessStopped()
            return true
        }

        /** A running instance can take a fast window: not stopping or destroyed, and starting or running its radio. */
        internal fun acceptsFast(stopping: Boolean, destroyed: Boolean, state: ServiceState): Boolean =
            !stopping && !destroyed && (state == ServiceState.RUNNING || state == ServiceState.STARTING)

        /** The app came to the front (or an onboarding step closed): post the notification again if it can be seen now. */
        fun refreshNotification() {
            instance?.onNotificationCheck()
        }

        internal fun rotationTick() {
            instance?.onRotationTick()
        }

        internal fun scanError(code: Int) {
            instance?.onScanError(code)
        }

        internal fun scanOk() {
            instance?.onScanOk()
        }

        /** Stops the service without the user's Stop flag (a wipe of the group: nothing to sync). */
        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }

        /** «Stop» from the app's own screen: the same as the notification action (flag, SYNC_STOPPED on restart). */
        fun requestStop(context: Context) {
            try {
                context.startService(Intent(context, SyncService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "cannot request stop", e)
            }
        }
    }
}
