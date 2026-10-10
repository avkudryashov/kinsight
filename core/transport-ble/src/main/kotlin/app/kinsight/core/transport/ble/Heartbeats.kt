package app.kinsight.core.transport.ble

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.sync.PROTOCOL_VERSION

/**
 * Heartbeat entries (spec §4.3, superseded by the next one): when the radio starts and before sessions, at most once
 * per [INTERVAL_MS] unless the app or protocol version changed, and again whenever the clock went backwards.
 * [append] is UsagePipeline.appendOwn: the same lock and own-log entry point as usage; false means no group yet.
 */
class Heartbeats(
    private val state: TransportStateStore,
    private val appVersion: String,
    private val append: suspend ((EntryAuthor) -> List<DecodedEntry>) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val version = "$appVersion/$PROTOCOL_VERSION"

    suspend fun emitIfDue(): Boolean {
        val now = clock()
        val last = state.heartbeat()
        val due = last == null || last.version != version || now - last.atMs !in 0 until INTERVAL_MS
        if (!due) return false
        val appended = append { author ->
            listOf(author.create { it.copy(heartbeat = Heartbeat(app_version = appVersion, protocol_version = PROTOCOL_VERSION)) })
        }
        if (appended) state.setHeartbeat(HeartbeatMark(now, version))
        return appended
    }

    companion object {
        const val INTERVAL_MS = 24 * 60 * 60_000L
    }
}
