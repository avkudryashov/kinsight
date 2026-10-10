package app.kinsight.core.data

import app.kinsight.core.model.DeviceId

/**
 * What the alerts have already said (AlertPlanner): the highest notified seq per author, children reported as out of
 * contact, the parents a child already knows (null before the first run) and the date of the last summary.
 * Stored as text lines "n:id=seq,…", "c:id,…", "p:id,…", "s:date"; anything malformed reads as the default.
 */
data class AlertMarkers(
    val notifiedSeq: Map<DeviceId, Long> = emptyMap(),
    val noContact: Set<DeviceId> = emptySet(),
    val knownParents: Set<DeviceId>? = null,
    val lastSummaryDate: String? = null,
) {
    fun encode(): String = buildList {
        add("n:" + notifiedSeq.entries.joinToString(",") { "${it.key.hex}=${it.value}" })
        add("c:" + noContact.joinToString(",") { it.hex })
        knownParents?.let { add("p:" + it.joinToString(",") { id -> id.hex }) }
        lastSummaryDate?.let { add("s:$it") }
    }.joinToString("\n")

    companion object {
        fun decode(text: String?): AlertMarkers = try {
            val lines = text.orEmpty().lines().filter { it.length >= 2 && it[1] == ':' }.associate { it[0] to it.substring(2) }
            fun ids(s: String?): Set<DeviceId>? = s?.split(',')?.filter { it.isNotEmpty() }?.map(::DeviceId)?.toSet()
            AlertMarkers(
                notifiedSeq = lines['n'].orEmpty().split(',').filter { it.isNotEmpty() }.associate {
                    val (id, seq) = it.split('=')
                    DeviceId(id) to seq.toLong()
                },
                noContact = ids(lines['c']).orEmpty(),
                knownParents = ids(lines['p']),
                lastSummaryDate = lines['s'],
            )
        } catch (e: Exception) {
            AlertMarkers()
        }
    }
}
