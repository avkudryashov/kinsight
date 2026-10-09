package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryEntryStore(override val groupId: GroupId) : EntryStore {
    private val mutex = Mutex()
    private val byKey = HashMap<Pair<DeviceId, Long>, DecodedEntry>()
    private val bySupersede = HashMap<String, DecodedEntry>()
    private val maxSeq = HashMap<DeviceId, Long>()
    private var maxLamport = 0L

    override suspend fun versionVector() = mutex.withLock { VersionVector(maxSeq.toMap()) }

    override suspend fun membershipEntries() = mutex.withLock { byKey.values.filter { it.isMembershipRelevant } }

    override suspend fun entriesAfter(author: DeviceId, afterSeq: Long, limit: Int) = mutex.withLock {
        byKey.values.filter { it.author == author && it.seq > afterSeq }.sortedBy { it.seq }.take(limit)
    }

    override suspend fun applyBatch(entries: List<DecodedEntry>, seenOnly: Map<DeviceId, Long>) = mutex.withLock {
        for (e in entries) {
            insert(e)
            maxSeq.merge(e.author, e.seq, ::maxOf)
        }
        for ((author, seq) in seenOnly) maxSeq.merge(author, seq, ::maxOf)
    }

    override suspend fun storeMembership(entries: List<DecodedEntry>) = mutex.withLock {
        entries.forEach(::insert)
    }

    override suspend fun liveEntries() = mutex.withLock {
        byKey.values.sortedWith(compareBy({ it.author.hex }, { it.seq }))
    }

    override suspend fun maxLamport() = mutex.withLock { maxLamport }

    override suspend fun prune(policy: SyncPolicy) = mutex.withLock {
        val expired = byKey.values.filter(policy::isExpired)
        for (e in expired) {
            byKey.remove(e.author to e.seq)
            e.supersedeKey?.let { key -> if (bySupersede[key] == e) bySupersede.remove(key) }
        }
    }

    private fun insert(e: DecodedEntry) {
        maxLamport = maxOf(maxLamport, e.lamport)
        if (byKey.containsKey(e.author to e.seq)) return
        val key = e.supersedeKey
        if (key != null) {
            val old = bySupersede[key]
            if (old != null) {
                if (old.seq >= e.seq) return
                byKey.remove(old.author to old.seq)
            }
            bySupersede[key] = e
        }
        byKey[e.author to e.seq] = e
    }
}
