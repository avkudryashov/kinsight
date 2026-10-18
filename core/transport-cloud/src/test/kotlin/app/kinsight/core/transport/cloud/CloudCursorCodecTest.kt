package app.kinsight.core.transport.cloud

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
            locationDue = mapOf(CloudPaths.packName(1, 41) to 1_760_172_800_000L),
            failing = mapOf("p/q/r" to PackFailure(3, 1_760_000_123_000L)),
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

    /** Bytes [CloudCursorCodec.encode] never writes: a repeated map key, an optional flag other than 0 or 1. */
    @Test
    fun aRepeatedKeyOrANonCanonicalFlagIsAnIOException() {
        fun cursor(seenKeys: List<String>, flag: Int): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { o ->
                o.writeByte(2)
                o.writeLong(0)
                o.writeLong(0)
                o.writeInt(seenKeys.size)
                seenKeys.forEach { o.writeUTF(it); o.writeInt(1) }
                o.writeInt(0) // own packs
                o.writeInt(1) // one deferred pack
                o.writeUTF("d")
                o.writeInt(1)
                o.writeByte(flag) // failedKey absent
                o.writeByte(0) // membershipHash absent
                o.writeInt(0) // location due
            }
            return bytes.toByteArray()
        }
        assertEquals(mapOf("x" to 1, "y" to 1), CloudCursorCodec.decode(cursor(listOf("x", "y"), 0)).seen)
        assertFailsWith<IOException> { CloudCursorCodec.decode(cursor(listOf("x", "x"), 0)) }
        assertFailsWith<IOException> { CloudCursorCodec.decode(cursor(listOf("x"), 2)) }
    }

    /** A cursor written before Y4 (version 1) reads with no location-bearing own pack. */
    @Test
    fun aVersionOneCursorReadsWithoutLocationDue() {
        val c = CloudCursor(uploadedUpTo = 7, ownPacks = mapOf(CloudPaths.packName(1, 7) to KeyId(0, "00ff")))
        val v4 = CloudCursorCodec.encode(c)
        // Version 1 is version 4 without the trailing empty locationDue and failing counts.
        val v1 = v4.copyOf(v4.size - 8).also { it[0] = 1 }
        assertEquals(c, CloudCursorCodec.decode(v1))
    }

    /** A cursor written before Task 14 (version 2) reads with no pack failing. */
    @Test
    fun aVersionTwoCursorReadsWithoutFailingPacks() {
        val c = CloudCursor(uploadedUpTo = 7, locationDue = mapOf(CloudPaths.packName(1, 7) to 9L))
        val v4 = CloudCursorCodec.encode(c)
        // Version 2 is version 4 without the trailing empty failing count.
        val v2 = v4.copyOf(v4.size - 4).also { it[0] = 2 }
        assertEquals(c, CloudCursorCodec.decode(v2))
    }

    /** A cursor of Task 14 before its fix round (version 3) kept the last failure time: the next attempt is that plus the wait. */
    @Test
    fun aVersionThreeCursorReadsTheNextAttemptFromTheLastFailure() {
        val v4 = CloudCursorCodec.encode(CloudCursor(failing = mapOf("p/q/r" to PackFailure(2, 1_000L))))
        val v3 = v4.copyOf().also { it[0] = 3 }
        assertEquals(mapOf("p/q/r" to PackFailure(2, 1_000L + CloudSync.retryDelayMs(2))), CloudCursorCodec.decode(v3).failing)
    }
}
