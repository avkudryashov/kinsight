package app.kinsight.core.usage

import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.RoomUsageQueue
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

    private suspend fun publisher(): Publisher? {
        val t = target() ?: return null
        return Publisher(queue, RoomEntryStore(db, t.groupId), t.keys, apps, clock, today)
    }
}
