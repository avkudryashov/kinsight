package app.kinsight.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
