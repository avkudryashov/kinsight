package app.kinsight.core.usage

import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.PendingEventRow
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.sync.observedLamport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

data class RunReport(val collect: CollectResult, val publish: PublishReport?)

/**
 * The whole usage pipeline behind one lock: collect into the queue, then publish if a group exists. Entry points:
 * the hourly worker, the day rollover, the time and zone broadcasts, and (stage 3c) every sync session.
 * Publish, collect and sharing changes all take the same [Mutex], so two EntryAuthors never race on appendOwn.
 */
class UsagePipeline(
    private val db: KinsightDatabase,
    private val collector: Collector,
    private val settings: SettingsStore,
    private val apps: AppInfoSource,
    private val target: suspend () -> PublishTarget?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val mutex = Mutex()
    private val queue = RoomUsageQueue(db)

    suspend fun collectAndPublish(force: Boolean = false): RunReport = mutex.withLock {
        val collected = collector.collect()
        RunReport(collected, publisher()?.publish(settings.current(), force))
    }

    /** Event first, then the setting: a failure in between repeats the event, but never leaves a pause unrecorded. */
    suspend fun setSharing(on: Boolean): Unit = mutex.withLock {
        if (settings.current().sharing == on) return@withLock
        val publisher = publisher()
        publisher?.recordSharingChange(on)
        settings.setSharing(on)
        if (on) publisher?.publish(settings.current(), force = true)
    }

    /**
     * Signs and stores own entries that are not usage (Heartbeat, MembershipOp) under the same lock and through the
     * same appendOwn as usage, so two EntryAuthors never race on a seq. [build] gets a fresh author right after the
     * stored author state and returns the entries to append (consecutive seqs). [to] overrides the target, for the
     * genesis of a group whose file does not exist yet. Returns false when there is no group.
     */
    suspend fun appendOwn(to: PublishTarget? = null, build: (EntryAuthor) -> List<DecodedEntry>): Boolean = mutex.withLock {
        val t = to ?: target() ?: return@withLock false
        val store = RoomEntryStore(db, t.groupId)
        val state = store.authorState(t.keys.deviceId)
        val observed = store.observedLamport()
        val entries = build(EntryAuthor(t.groupId, t.keys, state.lastSeq, clock, state.lastLamport) { observed })
        if (entries.isNotEmpty()) store.appendOwn(entries)
        true
    }

    /** Queues a local integrity event; it is signed with the others on the next publish while sharing is on. */
    suspend fun queueEvent(kind: StateEvent.Kind, fromMs: Long, toMs: Long): Unit = mutex.withLock {
        queue.addEvent(PendingEventRow(kind = kind.value, fromMs = fromMs, toMs = toMs))
    }

    suspend fun currentSettings(): UsageSettings = settings.current()

    private suspend fun publisher(): Publisher? {
        val t = target() ?: return null
        return Publisher(queue, RoomEntryStore(db, t.groupId), t.keys, apps, clock, today)
    }
}
