package app.kinsight.core.data

import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.usage.UsagePipeline
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App blocking spec §5.4 on a child's phone: BLOCKING_OFF, self-closing like LOCATION_DISABLED (core/transport-ble
 * LocationWatch). The accessibility service off while [rules] (this child's own effective rules) need it
 * (BlockDecision.enforcing) for two observations at least [OFF_GRACE_MS] apart (the system fills its list of enabled
 * services asynchronously at process start, so a first «off» is often false; a candidate kept in memory, cleared by any
 * «on») queues the open event (from = to = the first off observation) on [pipeline], signed at the next publish; on again,
 * or no rule needing it any more, queues the closing one (from = noticed, to = now, always after from). The open one is
 * kept in AppPrefs.blockingOffSince; an off service with no such rule is no problem and signs nothing, as a permission
 * never granted is no warning in SetupWarnings. A refused event (not a consenting child) keeps no «since», so the next
 * check tries again. The caller (app BlockingWatch) reads the service state from core/blocking BlockingState.
 */
class BlockingStateFollow(
    private val prefs: AppPrefs,
    private val pipeline: UsagePipeline,
    private val rules: suspend () -> List<ThresholdRule>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var offCandidate: Long? = null

    /** True while a first «off» waits for its confirmation: the caller re-checks after [OFF_GRACE_MS]. */
    @Volatile
    var offPending: Boolean = false
        private set

    /** Returns true when an event was queued (the caller publishes and uploads it at once). */
    suspend fun onState(serviceEnabled: Boolean): Boolean = mutex.withLock {
        val since = prefs.blockingOffSince()
        val now = clock()
        val off = !serviceEnabled && BlockDecision.enforcing(rules())
        if (!off) offCandidate = null
        offPending = off && since == null
        when {
            off && since == null -> {
                val first = offCandidate ?: now.also { offCandidate = it }
                if (now - first < OFF_GRACE_MS) {
                    false
                } else {
                    pipeline.queueEvent(KIND, first, first).also { if (it) { prefs.setBlockingOffSince(first); offCandidate = null; offPending = false } }
                }
            }
            !off && since != null -> pipeline.queueEvent(KIND, since, maxOf(since + 1, now)).also { if (it) prefs.setBlockingOffSince(null) }
            else -> false
        }
    }

    companion object {
        /** The service must be seen off twice at least this far apart before BLOCKING_OFF opens. */
        const val OFF_GRACE_MS = 60_000L
        private val KIND = StateEvent.Kind.BLOCKING_OFF
    }
}
