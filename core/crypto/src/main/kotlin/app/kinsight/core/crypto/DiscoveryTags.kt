package app.kinsight.core.crypto

object DiscoveryTags {
    const val WINDOW_MS: Long = 15 * 60 * 1000L
    private val GROUP_LABEL = "kinsight-grp".encodeToByteArray()
    private val DEVICE_LABEL = "kinsight-dev".encodeToByteArray()
    private val VECTOR_LABEL = "kinsight-vec".encodeToByteArray()

    fun window(epochMs: Long): Long = Math.floorDiv(epochMs, WINDOW_MS)

    fun groupTag(discoveryKey: ByteArray, window: Long): ByteArray =
        Primitives.hmacSha256(discoveryKey, GROUP_LABEL, le64(window)).copyOf(4)

    fun deviceTag(discoveryKey: ByteArray, deviceId: ByteArray, window: Long): ByteArray =
        Primitives.hmacSha256(discoveryKey, DEVICE_LABEL, deviceId, le64(window)).copyOf(2)

    /**
     * The advert's vector field (spec §8): HMAC-SHA256(key, "kinsight-vec" || window (8 B big-endian) || canonical
     * vector bytes), first 4 bytes. Keyed and bound to the window, so an unchanged vector does not link a device's
     * adverts across windows for an outsider (spec §6.1); members compare it within the window of the group tag.
     */
    fun vectorTag(discoveryKey: ByteArray, window: Long, canonicalVector: ByteArray): ByteArray =
        Primitives.hmacSha256(discoveryKey, VECTOR_LABEL, be64(window), canonicalVector).copyOf(4)

    fun matchesGroup(discoveryKey: ByteArray, tag: ByteArray, nowMs: Long): Boolean = matchingWindow(discoveryKey, tag, nowMs) != null

    /** The window (current, then the previous and the next one) whose group tag [tag] is, or null. */
    fun matchingWindow(discoveryKey: ByteArray, tag: ByteArray, nowMs: Long): Long? {
        val w = window(nowMs)
        return listOf(w, w - 1, w + 1).firstOrNull { groupTag(discoveryKey, it).contentEquals(tag) }
    }

    private fun le64(v: Long) = ByteArray(8) { i -> (v ushr (8 * i)).toByte() }

    private fun be64(v: Long) = ByteArray(8) { i -> (v ushr (8 * (7 - i))).toByte() }
}
