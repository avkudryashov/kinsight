package app.kinsight.core.sync

import app.kinsight.core.model.proto.Hello

/**
 * Protocol versions as ranges (audit A-1): each side offers [MIN_PROTOCOL_VERSION]..[PROTOCOL_VERSION], a session runs on
 * the highest version both speak and is refused only when the ranges do not overlap. A Hello without the range is a
 * peer that speaks exactly its one version (0.7.2 and older).
 */
object ProtocolVersions {
    val SUPPORTED: IntRange = MIN_PROTOCOL_VERSION..PROTOCOL_VERSION

    /**
     * The peer's range; null when the range fields are malformed: a minimum of 0 with a maximum, min > max, or
     * protocol_version other than min (a 0.7.2 peer would judge such a Hello by a version outside the range).
     */
    fun of(h: Hello): IntRange? {
        val min = h.min_protocol_version
        val max = h.max_protocol_version
        if (min == 0 && max == 0) return h.protocol_version..h.protocol_version
        if (min < 1 || min > max || h.protocol_version != min) return null
        return min..max
    }

    /** The highest version in both ranges; null when they do not overlap. */
    fun negotiate(ours: IntRange, theirs: IntRange): Int? {
        val v = minOf(ours.last, theirs.last)
        return if (v >= maxOf(ours.first, theirs.first)) v else null
    }
}
