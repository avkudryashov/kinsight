package app.kinsight.core.database

data class UsageDelta(val date: String, val hour: Int, val packageName: String, val ms: Long, val capMs: Long)

data class HourDelta(val date: String, val hour: Int, val screenMs: Long, val unlocks: Int, val capMs: Long)

data class DayZone(val date: String, val zoneId: String)

/** Everything one collector run produces; applied in one transaction together with the new cursor. */
data class CollectBatch(
    val usage: List<UsageDelta> = emptyList(),
    val hours: List<HourDelta> = emptyList(),
    val days: List<DayZone> = emptyList(),
    val events: List<PendingEventRow> = emptyList(),
    val state: CollectorStateRow,
)

data class DayUsage(val apps: List<PendingUsageRow>, val hours: List<PendingHourRow>)

/**
 * The local unsigned usage queue (spec §4.6, §7). Counted always, whether sharing is on or not. Each cell is capped
 * at the length of its local hour, so re-read events (a clock set back) cannot inflate an hour past what it holds.
 */
class RoomUsageQueue(private val db: KinsightDatabase) {
    private val dao = db.usageDao()

    /** Test hook, called inside [commit] after the data and before the cursor is written. */
    internal var faultInjector: (() -> Unit)? = null

    suspend fun state(): CollectorStateRow? = dao.state()

    suspend fun commit(batch: CollectBatch): Unit = db.write {
        for (u in batch.usage) {
            dao.ensureUsage(u.date, u.hour, u.packageName)
            dao.addUsage(u.date, u.hour, u.packageName, u.ms, u.capMs)
        }
        for (h in batch.hours) {
            dao.ensureHour(h.date, h.hour)
            dao.addHour(h.date, h.hour, h.screenMs, h.unlocks, h.capMs)
        }
        for (d in batch.days) {
            dao.ensureDay(d.date, d.zoneId)
            dao.touchDay(d.date, d.zoneId)
        }
        for (e in batch.events) dao.insertEvent(e.copy(id = 0))
        faultInjector?.invoke()
        dao.setState(batch.state.copy(id = 0))
    }

    suspend fun changedDays(): List<PendingDayRow> = dao.changedDays()

    suspend fun dayUsage(date: String): DayUsage = DayUsage(dao.usage(date), dao.hours(date))

    /** Marks [date] published as of [version]; a stale version changes nothing, so the marker never moves back. */
    suspend fun markPublished(date: String, hash: String, version: Long) = dao.markPublished(date, hash, version)

    suspend fun pendingEvents(): List<PendingEventRow> = dao.events()

    suspend fun deleteEvent(id: Long) = dao.deleteEvent(id)

    suspend fun lastUsagePublishMs(): Long? = dao.lastUsagePublish()

    suspend fun setLastUsagePublishMs(ms: Long) = dao.setPublishState(PublishStateRow(lastUsagePublishMs = ms))

    suspend fun catalogHash(packageName: String): String? = dao.catalogHash(packageName)

    suspend fun setCatalogHash(packageName: String, hash: String) = dao.setCatalogHash(CatalogStateRow(packageName, hash))
}
