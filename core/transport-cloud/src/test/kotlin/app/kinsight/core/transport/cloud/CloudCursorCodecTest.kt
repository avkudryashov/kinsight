package app.kinsight.core.transport.cloud

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CloudCursorCodecTest {
    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        val c = CloudCursor(
            uploadedUpTo = 41,
            seen = mapOf("a/b/c" to 0, "d/e/f" to CloudSync.COVERED_UNREAD),
            lastCompactionMs = 1_760_000_000_000L,
            ownPacks = mapOf(CloudPaths.packName(1, 41) to KeyId(2, "00ff00ff00ff00ff")),
            deferred = mapOf(
                "g/h/i" to DeferredPack(1, failedKey = "abcd"),
                "j/k/l" to DeferredPack(-1, membershipHash = "0123456789abcdef"),
                "m/n/o" to DeferredPack(3),
            ),
        )
        assertEquals(c, CloudCursorCodec.decode(CloudCursorCodec.encode(c)))
        assertEquals(CloudCursor(), CloudCursorCodec.decode(CloudCursorCodec.encode(CloudCursor())))
    }

    @Test
    fun anythingElseIsAnIOException() {
        assertFailsWith<IOException> { CloudCursorCodec.decode(ByteArray(0)) }
        assertFailsWith<IOException> { CloudCursorCodec.decode(byteArrayOf(9, 0, 0, 0)) }
        val good = CloudCursorCodec.encode(CloudCursor(uploadedUpTo = 3, seen = mapOf("x" to 1)))
        assertFailsWith<IOException> { CloudCursorCodec.decode(good.copyOf(good.size - 1)) }
    }
}
