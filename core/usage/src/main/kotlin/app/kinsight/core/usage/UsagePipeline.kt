package app.kinsight.core.usage

import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.PendingEventRow
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.WireConstants
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.sync.observedLamport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

data class RunReport(val collect: CollectResult, val publish: PublishReport?)

/**
 * The whole usage pipeline behind one lock: collect into the queue, then publish if a group exists. Entry points:
 * the hourly worker, the day rollover, the time and zone broadcasts, every sync session, and the app (sharing,
 * reconcile, membership ops, wipes through [exclusive]). Everything takes the same [Mutex], so two EntryAuthors
 * never race on appendOwn. [ownUsage] says whether this device may read and publish its own usage at all: in the app
 * only a consenting CHILD (spec §4.8, [ownUsageAllowed]); tests default to true.
 *
 * [onOwnStateEvent] hears every own StateEvent this pipeline signs, and every event [queueEvent] accepts while
 * sharing is on (the next session signs it first: SyncGraph.prepare), so the app can bring a parent in range the
 * news at once (IntegrityFastSync, core/data). It runs inside the pipeline lock: it must not block or call back.
 *
 * [usagePaused] is the system pause (S-M3: the child's sync notification is hidden): while it holds, publishing signs
 * the queued integrity events but no usage (Publisher). Inside the pipeline lock: DataStore or Room reads only.
 */
class UsagePipeline(
    private val db: KinsightDatabase,
    private val collector: Collector,
    private val settings: SettingsStore,
    private val apps: AppInfoSource,
    private val target: suspend () -> PublishTarget?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    private val ownUsage: suspend (PublishTarget?) -> Boolean = { true },
    private val onOwnStateEvent: (StateEvent.Kind) -> Unit = {},
    private val usagePaused: suspend () -> Boolean = { false },
) {
    private val mutex = Mutex()
    private val queue = RoomUsageQueue(db)

    suspend fun collectAndPublish(force: Boolean = false): RunReport = mutex.withLock {
        val t = target()
        if (!ownUsage(t)) return@withLock RunReport(CollectResult.NOT_ALLOWED, null)
        val collected = collector.collect()
        pruneQueue()
        RunReport(collected, publisher(t)?.publish(settings.current(), force))
    }

    /**
     * Event first, then the setting: a failure in between repeats the event, but never leaves a pause unrecorded
     * (and [reconcileSharing] repairs the setting at the next start). Turning on needs [ownUsage]; without it,
     * turning off just writes the setting and signs nothing.
     */
    suspend fun setSharing(on: Boolean): Unit = mutex.withLock {
        if (settings.current().sharing == on) return@withLock
        val t = target()
        val allowed = ownUsage(t)
        check(!on || allowed) { "only a consenting child device shares its usage (spec §4.8)" }
        // A device that may not share only writes the setting: it never signs SHARING_PAUSED.
        val publisher = if (allowed) publisher(t) else null
        publisher?.recordSharingChange(on)
        settings.setSharing(on)
        if (on) publisher?.publish(settings.current(), force = true)
    }

    /**
     * Makes the «share» switch agree with what this device last signed (spec §7, stage 4 tasks): after a crash
     * between a signed SHARING_PAUSED and the setting write the switch goes off, after one between SHARING_RESUMED
     * and the write it goes on; with no signed SHARING_* yet but the switch on (it was turned on before the group
     * existed) SHARING_RESUMED is signed now. A device that may not share only ends with the switch off and signs
     * nothing (a parent publishes no StateEvent but LEFT_GROUP, spec §4.8). Without a group nothing
     * happens. Called at app start and after joining.
     */
    suspend fun reconcileSharing(): Unit = mutex.withLock {
        val t = target() ?: return@withLock
        val on = settings.current().sharing
        if (!ownUsage(t)) {
            if (on) settings.setSharing(false)
            return@withLock
        }
        val last = RoomEntryStore(db, t.groupId).stateEvents()
            .lastOrNull { it.author == t.keys.deviceId && it.body.state_event!!.kind in WireConstants.SHARING_KINDS }
            ?.body?.state_event?.kind
        val publisher = publisher(t)!!
        when {
            last == StateEvent.Kind.SHARING_PAUSED && on -> settings.setSharing(false)
            last == StateEvent.Kind.SHARING_RESUMED && !on -> {
                settings.setSharing(true)
                publisher.publish(settings.current(), force = true)
            }
            last == null && on -> {
                publisher.recordSharingChange(true)
                publisher.publish(settings.current(), force = true)
            }
        }
    }

    /**
     * Signs and stores own entries that are not usage (Heartbeat, MembershipOp, LEFT_GROUP) under the same lock and
     * through the same appendOwn as usage, so two EntryAuthors never race on a seq. [build] gets a fresh author right
     * after the stored author state and returns the entries to append (consecutive seqs). [build] runs under the lock,
     * so what it reads of the own log (a Heartbeat's live set, A-2) stays true until its entries are stored; it may
     * read Room but must not call back into this pipeline. [to] overrides the target, for the genesis of a group whose
     * file does not exist yet. Returns false when there is no group.
     */
    suspend fun appendOwn(to: PublishTarget? = null, build: suspend (EntryAuthor) -> List<DecodedEntry>): Boolean = mutex.withLock {
        val t = to ?: target() ?: return@withLock false
        val store = RoomEntryStore(db, t.groupId)
        val state = store.authorState(t.keys.deviceId)
        val observed = store.observedLamport()
        val entries = build(EntryAuthor(t.groupId, t.keys, state.lastSeq, clock, state.lastLamport) { observed })
        if (entries.isNotEmpty()) store.appendOwn(entries)
        entries.forEach { e -> e.body.state_event?.let { onOwnStateEvent(it.kind) } }
        true
    }

    /**
     * Queues a local integrity event (signed on the next publish while sharing is on). False when it was dropped
     * because [ownUsage] refused: the caller keeps its own state then, so nothing is marked as reported.
     */
    suspend fun queueEvent(kind: StateEvent.Kind, fromMs: Long, toMs: Long): Boolean = mutex.withLock {
        if (!ownUsage(target())) return@withLock false
        queue.addEvent(PendingEventRow(kind = kind.value, fromMs = fromMs, toMs = toMs))
        if (settings.current().sharing) onOwnStateEvent(kind)
        true
    }

    /**
     * Runs [block] holding the pipeline lock (local wipes, identity reset). The lock is not reentrant: [block] must
     * not call this pipeline, or it deadlocks.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun currentSettings(): UsageSettings = settings.current()

    private suspend fun pruneQueue() {
        val days = settings.current().summaryRetentionDays.toLong()
        queue.prune(beforeDate = today().minusDays(days).toString(), beforeMs = clock() - days * DAY_MS)
    }

    private fun publisher(t: PublishTarget?): Publisher? =
        t?.let { Publisher(queue, RoomEntryStore(db, it.groupId), it.keys, apps, clock, today, onStateEvent = onOwnStateEvent, usagePaused = usagePaused) }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
