package app.kinsight.core.transport.ble

/**
 * Android silently drops the sixth scan start within 30 s per app (it logs "App is scanning too frequently" and
 * delivers nothing). Every startScan in the process goes through one budget, [PROCESS]: the sync service uses it
 * directly, pairing through [share] with a cap of its own inside it. [tryStart] checks and takes a slot atomically on
 * this budget and every budget above it, or says how long to wait; a refused start takes no slot anywhere.
 * Monotonic clock only. Thread-safe: every budget of one tree locks the same root.
 */
class ScanThrottle private constructor(
    private val maxStarts: Int,
    private val windowMs: Long,
    private val parent: ScanThrottle?,
) {
    constructor(maxStarts: Int = MAX_STARTS, windowMs: Long = WINDOW_MS) : this(maxStarts, windowMs, null)

    private val starts = ArrayDeque<Long>()
    private val lock: Any = parent?.lock ?: this

    /** A budget of at most [maxStarts] starts per window that also counts against (and waits for) this one. */
    fun share(maxStarts: Int): ScanThrottle = ScanThrottle(maxStarts, windowMs, this)

    /** How long until a start may go out here and in every budget above; 0 when it may go now. */
    fun waitMs(nowMono: Long): Long = synchronized(lock) {
        var wait = 0L
        var t: ScanThrottle? = this
        while (t != null) {
            wait = maxOf(wait, t.ownWaitMs(nowMono))
            t = t.parent
        }
        wait
    }

    /** Counts a start here and in every budget above. */
    fun record(nowMono: Long): Unit = synchronized(lock) {
        var t: ScanThrottle? = this
        while (t != null) {
            t.ownRecord(nowMono)
            t = t.parent
        }
    }

    /** Takes a slot now (returns 0) or returns how long to wait, taking nothing; one atomic step for all users. */
    fun tryStart(nowMono: Long): Long = synchronized(lock) {
        waitMs(nowMono).also { if (it == 0L) record(nowMono) }
    }

    private fun ownWaitMs(nowMono: Long): Long {
        while (starts.isNotEmpty() && nowMono - starts.first() >= windowMs) starts.removeFirst()
        if (starts.size < maxStarts) return 0L
        return (starts.first() + windowMs - nowMono).coerceIn(0L, windowMs)
    }

    private fun ownRecord(nowMono: Long) {
        starts.addLast(nowMono)
        while (starts.size > maxStarts) starts.removeFirst()
    }

    companion object {
        const val MAX_STARTS = 5
        const val WINDOW_MS = 30_000L

        /** The app's one scan-start budget (Android counts per app, not per scanner): sync and pairing share it. */
        val PROCESS = ScanThrottle()
    }
}
