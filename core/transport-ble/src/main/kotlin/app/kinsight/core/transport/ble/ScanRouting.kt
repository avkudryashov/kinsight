package app.kinsight.core.transport.ble

/**
 * What ScanReceiver does with a delivered batch. The background scan runs through a PendingIntent, so it outlives the
 * process: a batch that finds no service at all belongs to a scan nobody owns any more, and it is stopped instead of
 * waking the app every batch. A service without an engine (starting, or idle on a problem) owns the scan itself.
 */
internal object ScanRouting {
    enum class Action { DELIVER, DROP, STOP_ORPHAN_SCAN }

    fun route(engineRunning: Boolean, serviceRunning: Boolean): Action = when {
        engineRunning -> Action.DELIVER
        serviceRunning -> Action.DROP
        else -> Action.STOP_ORPHAN_SCAN
    }
}
