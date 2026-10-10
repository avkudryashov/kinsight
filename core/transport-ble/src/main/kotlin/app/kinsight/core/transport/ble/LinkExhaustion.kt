package app.kinsight.core.transport.ble

/**
 * Dials that keep failing with «No resources Available» (emulator run 2026-10-10: 225 and 396 in a row once the L2CAP
 * listeners had died) mean our own Bluetooth side is stuck, not that the peer is away. [onFailure] answers true on the
 * [threshold]-th such failure in a row, and counts from zero again after that; a success or any other failure breaks
 * the run. Not thread-safe: the engine calls it from its session coroutine, one at a time (the session gate).
 */
class LinkExhaustion(private val threshold: Int = THRESHOLD) {
    private var run = 0

    fun onFailure(e: Throwable): Boolean {
        if (!isNoResources(e)) {
            run = 0
            return false
        }
        run++
        if (run < threshold) return false
        run = 0
        return true
    }

    fun onSuccess() {
        run = 0
    }

    private fun isNoResources(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(8).any { it.message?.contains(MARKER, ignoreCase = true) == true }

    companion object {
        const val THRESHOLD = 10
        private const val MARKER = "No resources"
    }
}
