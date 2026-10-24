package app.kinsight.core.data

import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.usage.UsagePipeline
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App blocking spec §5.4 on a child's phone: BLOCKING_OFF, self-closing like LOCATION_DISABLED (core/transport-ble
 * LocationWatch). The accessibility service off while [rules] (this child's own effective rules) need it
 * (BlockDecision.enforcing) queues the open event (from = to = noticed) on [pipeline], signed at the next publish; on again,
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

    /** Returns true when an event was queued (the caller publishes and uploads it at once). */
    suspend fun onState(serviceEnabled: Boolean): Boolean = mutex.withLock {
        val since = prefs.blockingOffSince()
        val now = clock()
        val off = !serviceEnabled && BlockDecision.enforcing(rules())
        when {
            off && since == null -> pipeline.queueEvent(KIND, now, now).also { if (it) prefs.setBlockingOffSince(now) }
            !off && since != null -> pipeline.queueEvent(KIND, since, maxOf(since + 1, now)).also { if (it) prefs.setBlockingOffSince(null) }
            else -> false
        }
    }

    private companion object {
        val KIND = StateEvent.Kind.BLOCKING_OFF
    }
}
