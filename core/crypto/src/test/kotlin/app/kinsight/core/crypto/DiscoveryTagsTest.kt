package app.kinsight.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryTagsTest {
    private val key = ByteArray(32) { it.toByte() }
    private val t0 = 1_791_547_677_000L

    @Test
    fun tagSizes() {
        val w = DiscoveryTags.window(t0)
        assertEquals(4, DiscoveryTags.groupTag(key, w).size)
        assertEquals(2, DiscoveryTags.deviceTag(key, ByteArray(16), w).size)
    }

    @Test
    fun tagRotatesBetweenWindows() {
        val w = DiscoveryTags.window(t0)
        assertFalse(DiscoveryTags.groupTag(key, w).contentEquals(DiscoveryTags.groupTag(key, w + 1)))
        assertContentEquals(DiscoveryTags.groupTag(key, w), DiscoveryTags.groupTag(key, w))
    }

    @Test
    fun matchesCurrentAndAdjacentWindowsOnly() {
        val w = DiscoveryTags.window(t0)
        val tag = DiscoveryTags.groupTag(key, w)
        assertTrue(DiscoveryTags.matchesGroup(key, tag, t0))
        assertTrue(DiscoveryTags.matchesGroup(key, tag, t0 + DiscoveryTags.WINDOW_MS))
        assertTrue(DiscoveryTags.matchesGroup(key, tag, t0 - DiscoveryTags.WINDOW_MS))
        assertFalse(DiscoveryTags.matchesGroup(key, tag, t0 + 3 * DiscoveryTags.WINDOW_MS))
        assertFalse(DiscoveryTags.matchesGroup(ByteArray(32), tag, t0))
    }

    @Test
    fun vectorTagChangesWithTheWindowSoItLinksNothingAcrossWindows() {
        val w = DiscoveryTags.window(t0)
        val vector = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(4, DiscoveryTags.vectorTag(key, w, vector).size)
        assertContentEquals(DiscoveryTags.vectorTag(key, w, vector), DiscoveryTags.vectorTag(key, w, vector.copyOf()))
        assertFalse(DiscoveryTags.vectorTag(key, w, vector).contentEquals(DiscoveryTags.vectorTag(key, w + 1, vector)))
        assertFalse(DiscoveryTags.vectorTag(key, w, vector).contentEquals(DiscoveryTags.vectorTag(ByteArray(32), w, vector)))
        assertFalse(DiscoveryTags.vectorTag(key, w, vector).contentEquals(DiscoveryTags.vectorTag(key, w, byteArrayOf(1, 2, 3, 4, 6))))
    }

    @Test
    fun vectorTagIsHmacOfLabelBigEndianWindowAndVector() {
        val w = 0x0102030405060708L
        val vector = byteArrayOf(9, 9)
        val expected = Primitives.hmacSha256(
            key,
            "kinsight-vec".encodeToByteArray(),
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            vector,
        ).copyOf(4)
        assertContentEquals(expected, DiscoveryTags.vectorTag(key, w, vector))
    }

    @Test
    fun matchingWindowNamesTheWindowTheGroupTagBelongsTo() {
        val w = DiscoveryTags.window(t0)
        assertEquals(w, DiscoveryTags.matchingWindow(key, DiscoveryTags.groupTag(key, w), t0))
        assertEquals(w - 1, DiscoveryTags.matchingWindow(key, DiscoveryTags.groupTag(key, w - 1), t0))
        assertEquals(w + 1, DiscoveryTags.matchingWindow(key, DiscoveryTags.groupTag(key, w + 1), t0))
        assertNull(DiscoveryTags.matchingWindow(key, DiscoveryTags.groupTag(key, w + 2), t0))
    }
}
