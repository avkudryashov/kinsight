package app.kinsight.core.data

import app.kinsight.core.model.DeviceId

/**
 * What the alerts have already said (AlertPlanner): the highest notified seq per author, children reported as out of
 * contact, the parents a child already knows (null before the first run) and the date of the last summary.
 * Stored as text lines "n:id=seq,…", "c:id,…", "p:id,…", "s:date", "t:key|…", "i:1"; a malformed pair is skipped on its own.
 * Threshold keys are joined by '|', which a rule id never holds (ThresholdRule refuses it in a package name).
 */
data class AlertMarkers(
    val notifiedSeq: Map<DeviceId, Long> = emptyMap(),
    val noContact: Set<DeviceId> = emptySet(),
    val knownParents: Set<DeviceId>? = null,
    val lastSummaryDate: String? = null,
    /** False until the first plan ran: only then is history baselined (marked seen) instead of reported. */
    val initialized: Boolean = false,
    /** Thresholds already notified (stage 5b), as [thresholdKey]s: one per (rule, limit, the child's date). */
    val thresholdNotified: Set<String> = emptySet(),
) {
    fun encode(): String = buildList {
        add("n:" + notifiedSeq.entries.joinToString(",") { "${it.key.hex}=${it.value}" })
        add("c:" + noContact.joinToString(",") { it.hex })
        knownParents?.let { add("p:" + it.joinToString(",") { id -> id.hex }) }
        lastSummaryDate?.let { add("s:$it") }
        if (thresholdNotified.isNotEmpty()) add("t:" + thresholdNotified.sorted().joinToString("|"))
        if (initialized) add("i:1")
    }.joinToString("\n")

    companion object {
        private val HEX_ID = Regex("[0-9a-f]{32}")
        private val THRESHOLD_KEY = Regex("(.+)@(\\d{1,18})@(\\d{4}-\\d{2}-\\d{2})")

        /** `<rule id>@<limit ms>@<child's date>`: a changed limit is a new key, so it may notify once more. */
        fun thresholdKey(ruleId: String, limitMs: Long, date: java.time.LocalDate): String = "$ruleId@$limitMs@$date"

        /** The rule id and date of a [thresholdKey]; null when malformed. */
        fun parseThresholdKey(key: String): Pair<String, String>? =
            THRESHOLD_KEY.matchEntire(key)?.let { it.groupValues[1] to it.groupValues[3] }

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
                thresholdNotified = lines['t'].orEmpty().split('|').filter { parseThresholdKey(it) != null }.toSet(),
            )
            // Text written before the flag existed decodes as not initialized: one harmless re-baseline.
            return parsed.copy(initialized = lines['i'] == "1")
        }
    }
}
