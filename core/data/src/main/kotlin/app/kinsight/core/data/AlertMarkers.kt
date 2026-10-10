package app.kinsight.core.data

import app.kinsight.core.model.DeviceId

/**
 * What the alerts have already said (AlertPlanner): the highest notified seq per author, children reported as out of
 * contact, the parents a child already knows (null before the first run) and the date of the last summary.
 * Stored as text lines "n:id=seq,…", "c:id,…", "p:id,…", "s:date", "i:1"; a malformed pair is skipped on its own.
 */
data class AlertMarkers(
    val notifiedSeq: Map<DeviceId, Long> = emptyMap(),
    val noContact: Set<DeviceId> = emptySet(),
    val knownParents: Set<DeviceId>? = null,
    val lastSummaryDate: String? = null,
    /** False until the first plan ran: only then is history baselined (marked seen) instead of reported. */
    val initialized: Boolean = false,
) {
    fun encode(): String = buildList {
        add("n:" + notifiedSeq.entries.joinToString(",") { "${it.key.hex}=${it.value}" })
        add("c:" + noContact.joinToString(",") { it.hex })
        knownParents?.let { add("p:" + it.joinToString(",") { id -> id.hex }) }
        lastSummaryDate?.let { add("s:$it") }
        if (initialized) add("i:1")
    }.joinToString("\n")

    companion object {
        private val HEX_ID = Regex("[0-9a-f]{32}")

        /** Each pair is parsed on its own: a bad id, a missing or negative seq is skipped; an empty "s:" is no date. */
        fun decode(text: String?): AlertMarkers {
            val lines = text.orEmpty().lines().filter { it.length >= 2 && it[1] == ':' }.associate { it[0] to it.substring(2) }
            fun id(s: String): DeviceId? = if (HEX_ID.matches(s)) DeviceId(s) else null
            fun ids(s: String?): Set<DeviceId>? = s?.split(',')?.mapNotNull(::id)?.toSet()
            val parsed = AlertMarkers(
                notifiedSeq = lines['n'].orEmpty().split(',').mapNotNull { pair ->
                    val parts = pair.split('=')
                    if (parts.size != 2) return@mapNotNull null
                    val key = id(parts[0]) ?: return@mapNotNull null
                    val seq = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
                    key to seq
                }.toMap(),
                noContact = ids(lines['c']).orEmpty(),
                knownParents = ids(lines['p']),
                lastSummaryDate = lines['s']?.takeIf { it.isNotEmpty() },
            )
            // Text written before the flag existed decodes as not initialized: one harmless re-baseline.
            return parsed.copy(initialized = lines['i'] == "1")
        }
    }
}
