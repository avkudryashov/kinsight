package app.kinsight.core.data

import android.content.Context
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.transport.ble.SyncGraph
import app.kinsight.core.transport.ble.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** How the screens drive the sync service; a fake in tests. */
interface SyncControl {
    /** Starts or nudges the service, honouring the user's Stop (REASON_APP_OPEN); [fast] = one minute of fast mode. */
    fun start(fast: Boolean)

    /** The «Start» button: clears the user's Stop (REASON_UI). */
    fun startByUser()

    /** «Stop» in the app: the same as the notification action. */
    fun requestStop()

    /** Stops the service without the Stop flag and waits (bounded) until it is gone: before a wipe. */
    suspend fun stopAndWait()

    /** The app came to the front: a running service posts its notification again if it can be seen now. */
    fun refreshNotification() {}

    /**
     * One fast-mode window without a UI start (IntegrityFastSync), on the same lane as [start] and [stopAndWait], so it
     * is ordered against a wipe's stop; [allowed] is read on that lane right before the start. True when a start or a
     * fast window went out (false: refused, no group, or nothing could start).
     */
    suspend fun requestFast(allowed: () -> Boolean = { true }): Boolean = false
}

/**
 * The group-file check is file I/O, so starts run on a serial IO lane (preflight C20, fix round 1); [stopAndWait]
 * and [requestFast] go through the same lane, so a start queued before the stop can never land after it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceSyncControl(context: Context) : SyncControl {
    private val app = context.applicationContext
    private val lane = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + lane)

    override fun start(fast: Boolean) {
        scope.launch { if (SyncGraph.groups(app).exists()) SyncService.start(app, SyncService.REASON_APP_OPEN, fast) }
    }

    override fun startByUser() {
        scope.launch { if (SyncGraph.groups(app).exists()) SyncService.start(app, SyncService.REASON_UI) }
    }

    override fun requestStop() = SyncService.requestStop(app)

    override fun refreshNotification() = SyncService.refreshNotification()

    override suspend fun requestFast(allowed: () -> Boolean): Boolean =
        withContext(lane) { allowed() && SyncGraph.groups(app).exists() && SyncService.requestFast(app) }

    override suspend fun stopAndWait() {
        withContext(lane) { SyncService.stop(app) }
        withTimeoutOrNull(5.seconds) { SyncGraph.snapshot.first { it.state == ServiceState.STOPPED } }
    }
}
