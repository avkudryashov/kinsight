package app.kinsight.core.database

import app.kinsight.core.transport.cloud.CloudCursor
import app.kinsight.core.transport.cloud.CloudCursorCodec
import app.kinsight.core.transport.cloud.CloudCursorStore
import java.io.IOException

/**
 * CloudCursorStore on Room (cloud Y2): one row, written in its own transaction before save returns. A row that does not
 * decode reads as a fresh cursor: the next run uploads the own log again (spec §11), which readers take as duplicates.
 */
class RoomCloudCursorStore(private val db: KinsightDatabase, private val log: (String) -> Unit = {}) : CloudCursorStore {
    override suspend fun load(): CloudCursor {
        val bytes = db.cloudCursorDao().state() ?: return CloudCursor()
        return try {
            CloudCursorCodec.decode(bytes)
        } catch (e: IOException) {
            log("cloud cursor unreadable, starting over: $e")
            CloudCursor()
        }
    }

    override suspend fun save(cursor: CloudCursor) {
        db.cloudCursorDao().set(CloudCursorRow(state = CloudCursorCodec.encode(cursor)))
    }
}
