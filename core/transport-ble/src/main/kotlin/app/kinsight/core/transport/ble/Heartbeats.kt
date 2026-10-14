package app.kinsight.core.transport.ble

import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.sync.LiveSet
import app.kinsight.core.sync.PROTOCOL_VERSION

/**
 * What [Heartbeats] needs of the own log: its stored seqs of every kind (ascending) and the seq of the own current
 * Heartbeat, which the next one supersedes.
 */
data class OwnLog(val liveSeqs: List<Long>, val heartbeatSeq: Long?)

/**
 * The own log of [self] in this store: every stored seq of any kind, and the seq of its current Heartbeat. Room only;
 * call it under the pipeline lock (inside UsagePipeline.appendOwn's build) so the live set it yields is exact.
 */
suspend fun RoomEntryStore.ownLog(self: DeviceId): OwnLog =
    OwnLog(ownLiveSeqs(self), heartbeats().lastOrNull { it.author == self }?.seq)

/**
 * Heartbeat entries (spec §4.3, superseded by the next one): when the radio starts and before sessions, at most once
 * per [INTERVAL_MS] unless the app or protocol version changed, again whenever the clock went backwards, and whenever
 * the own log moved past the current Heartbeat, at most once per [MOVED_INTERVAL_MS] (audit A-2: each one carries the
 * author's signed live set, which lets relays' receivers close gaps; a later statement is never stale, so the delay
 * costs nothing but a slower gap close). [append] is UsagePipeline.appendOwn: the same lock and own-log entry point as usage;
 * false means no group yet. [ownLog] reads the log of the author it gets, from Room only (never the pipeline); it runs
 * inside [append]'s build, under the pipeline lock: no own entry can be stored or superseded between reading the live
 * set and signing it, so a live entry is never declared dead. When neither the clock nor the log calls for one, the
 * build returns nothing and no Heartbeat is signed. Each carries the cloud storage generation ([cloudGeneration]); a new
 * generation is due at once.
 */
class Heartbeats(
    private val state: TransportStateStore,
    private val appVersion: String,
    private val append: suspend (suspend (EntryAuthor) -> List<DecodedEntry>) -> Boolean,
    private val ownLog: suspend (EntryAuthor) -> OwnLog = { OwnLog(emptyList(), null) },
    private val clock: () -> Long = System::currentTimeMillis,
    /** The storage generation of the last successful cloud sync (spec §7), 0 without the cloud. A new one makes a Heartbeat due. */
    private val cloudGeneration: suspend () -> Long = { 0L },
) {
    private val baseVersion = "$appVersion/$PROTOCOL_VERSION"

    suspend fun emitIfDue(): Boolean {
        val now = clock()
        val generation = cloudGeneration()
        // The generation is part of the mark: the first Heartbeat after a switch is due at once, which is how the phone
        // that replaced a token learns that everyone switched (spec §7.1 «Когда все активные подтвердили»).
        val version = if (generation == 0L) baseVersion else "$baseVersion/g$generation"
        val last = state.heartbeat()
        val timeDue = last == null || last.version != version || now - last.atMs !in 0 until INTERVAL_MS
        // Cheap checks first: inside the moved floor nothing is due, so neither the pipeline lock nor the own log is taken.
        if (!timeDue && now - last!!.atMs < MOVED_INTERVAL_MS) return false
        var signed = false
        val appended = append { author ->
            val log = ownLog(author)
            val moved = (log.liveSeqs.lastOrNull() ?: 0L) > (log.heartbeatSeq ?: 0L)
            if (!timeDue && !moved) return@append emptyList()
            signed = true
            listOf(author.create { it.copy(heartbeat = LiveSet.heartbeat(appVersion, PROTOCOL_VERSION, log.liveSeqs, log.heartbeatSeq, storageGeneration = generation)) })
        }
        if (!appended || !signed) return false
        state.setHeartbeat(HeartbeatMark(now, version))
        return true
    }

    companion object {
        const val INTERVAL_MS = 24 * 60 * 60_000L

        /** Least time between Heartbeats signed only because the own log moved (Publisher's hourly rhythm). */
        const val MOVED_INTERVAL_MS = 60 * 60_000L
    }
}
