package app.kinsight.core.data

import android.content.Context
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.transport.ble.SyncGraph
import app.kinsight.core.transport.ble.SyncService
import kotlinx.coroutines.flow.first
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
}

class ServiceSyncControl(context: Context) : SyncControl {
    private val app = context.applicationContext

    override fun start(fast: Boolean) {
        if (SyncGraph.groups(app).exists()) SyncService.start(app, SyncService.REASON_APP_OPEN, fast)
    }

    override fun startByUser() {
        if (SyncGraph.groups(app).exists()) SyncService.start(app, SyncService.REASON_UI)
    }

    override fun requestStop() = SyncService.requestStop(app)

    override suspend fun stopAndWait() {
        SyncService.stop(app)
        withTimeoutOrNull(5.seconds) { SyncGraph.snapshot.first { it.state == ServiceState.STOPPED } }
    }
}
