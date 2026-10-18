package app.kinsight.core.database

import app.kinsight.core.transport.cloud.CloudCursor
import app.kinsight.core.transport.cloud.CloudCursorCodec
import app.kinsight.core.transport.cloud.CloudCursorStore
import java.io.IOException

/**
 * CloudCursorStore on Room (cloud Y2): one row, written in its own transaction before save returns. A row that does not
 * decode, for whatever reason, reads as a fresh cursor: the next run uploads the own log again (spec §11), which readers
 * take as duplicates.
 */
class RoomCloudCursorStore internal constructor(
    private val db: KinsightDatabase,
    private val log: (String) -> Unit,
    private val decode: (ByteArray) -> CloudCursor,
) : CloudCursorStore {
    constructor(db: KinsightDatabase, log: (String) -> Unit = {}) : this(db, log, CloudCursorCodec::decode)

    override suspend fun load(): CloudCursor {
        val bytes = db.cloudCursorDao().state() ?: return CloudCursor()
        return try {
            decode(bytes)
        } catch (e: IOException) {
            log("cloud cursor unreadable, starting over: $e")
            CloudCursor()
        } catch (e: RuntimeException) {
            // Not the codec's contract (it throws IOException), but a codec bug must not stop every cloud run for good.
            log("cloud cursor decode failed, starting over: ${e.javaClass.simpleName}")
            CloudCursor()
        }
    }

    override suspend fun save(cursor: CloudCursor) {
        db.cloudCursorDao().set(CloudCursorRow(state = CloudCursorCodec.encode(cursor)))
    }
}
