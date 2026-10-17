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
        val v2 = CloudCursorCodec.encode(c)
        // Version 1 is version 2 without the trailing empty locationDue count.
        val v1 = v2.copyOf(v2.size - 4).also { it[0] = 1 }
        assertEquals(c, CloudCursorCodec.decode(v1))
    }
}
