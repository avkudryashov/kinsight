package app.kinsight.core.transport.ble

/**
 * Turns adapter OFF/ON into one StateEvent BLUETOOTH_OFF(from = off, to = on) (spec §8). Nothing can be delivered
 * while Bluetooth is off, so the event is recorded when it comes back. [record] runs before the start is forgotten:
 * a crash in between repeats the event instead of losing it. Used only while a group exists.
 */
class BluetoothOffTracker(
    private val state: TransportStateStore,
    private val record: suspend (fromMs: Long, toMs: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun onOff() {
        if (state.bluetoothOffSinceMs() == null) state.setBluetoothOffSinceMs(clock())
    }

    suspend fun onOn() {
        val since = state.bluetoothOffSinceMs() ?: return
        record(since, maxOf(since, clock()))
        state.setBluetoothOffSinceMs(null)
    }
}
