package app.kinsight.core.crypto

object DiscoveryTags {
    const val WINDOW_MS: Long = 15 * 60 * 1000L
    private val GROUP_LABEL = "kinsight-grp".encodeToByteArray()
    private val DEVICE_LABEL = "kinsight-dev".encodeToByteArray()

    fun window(epochMs: Long): Long = Math.floorDiv(epochMs, WINDOW_MS)

    fun groupTag(discoveryKey: ByteArray, window: Long): ByteArray =
        Primitives.hmacSha256(discoveryKey, GROUP_LABEL, le64(window)).copyOf(4)

    fun deviceTag(discoveryKey: ByteArray, deviceId: ByteArray, window: Long): ByteArray =
        Primitives.hmacSha256(discoveryKey, DEVICE_LABEL, deviceId, le64(window)).copyOf(2)

    fun matchesGroup(discoveryKey: ByteArray, tag: ByteArray, nowMs: Long): Boolean {
        val w = window(nowMs)
        return (w - 1..w + 1).any { groupTag(discoveryKey, it).contentEquals(tag) }
    }

    private fun le64(v: Long) = ByteArray(8) { i -> (v ushr (8 * i)).toByte() }
}
