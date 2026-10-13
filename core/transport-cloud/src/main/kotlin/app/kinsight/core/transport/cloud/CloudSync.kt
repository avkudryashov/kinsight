package app.kinsight.core.transport.cloud

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipLimits
import app.kinsight.core.model.MembershipState
import app.kinsight.core.sync.EntryStore
import app.kinsight.core.sync.EntryValidator
import app.kinsight.core.sync.LiveSet
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.Verdict
import app.kinsight.core.sync.accountLiveSet
import kotlinx.coroutines.CancellationException
import java.io.IOException

sealed interface CloudResult {
    data class Synced(val uploadedPacks: Int, val received: Int) : CloudResult
    data object NotMember : CloudResult

    /** The ring holds no generation of the group key: nothing can be sealed or read (Y2 seeds it at pairing). */
    data object NoGroupKey : CloudResult
    data object Unauthorized : CloudResult
    data object StorageFull : CloudResult
    data class Retry(val cause: Throwable) : CloudResult
}

/**
 * One cloud sync of this device (spec §5): upload own entries above the cursor as one pack, compact own packs once a day
 * or after a key change, then read every other device's new packs. Received entries take the Bluetooth path: membership
 * first (computed from the signed log, as SyncSession does), then EntryValidator and EntryStore.applyBatch, then author
 * completion from the pack's signed range.
 */
class CloudSync(
    private val store: EntryStore,
    private val keys: DeviceKeys,
    private val policy: SyncPolicy,
    private val disk: DiskApi,
    private val ring: GroupKeyRing,
    private val cursors: CloudCursorStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val maxPackEntries: Int = MAX_PACK_ENTRIES,
) {
    private val self = keys.deviceId
    private val groupId = store.groupId

    private class PackItem(val name: String, val sha: String, val range: LongRange)

    /** The effective rotation op of the group's newest generation, if any: sent in clear next to each own pack. */
    private var rotationEntry: DecodedEntry? = null

    /** Set by [upload] when the Disk refused a pack for lack of space. */
    private var storageFull = false

    private fun keyUpdates(epoch: Int): List<DecodedEntry> =
        listOfNotNull(rotationEntry?.takeIf { it.body.membership_op!!.rotate!!.epoch == epoch })

    suspend fun run(forceCompaction: Boolean = false): CloudResult = try {
        exchange(forceCompaction)
    } catch (e: DiskException.Unauthorized) {
        CloudResult.Unauthorized
    } catch (e: DiskException.StorageFull) {
        CloudResult.StorageFull
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        log("cloud sync failed: $e")
        CloudResult.Retry(e)
    }

    private suspend fun exchange(forceCompaction: Boolean): CloudResult {
        val membership = Membership.compute(groupId, store.membershipEntries())
        if (membership == null || self !in membership.active) return CloudResult.NotMember
        absorbRotation(membership)
        val epoch = writeEpoch(membership) ?: return CloudResult.NoGroupKey
        var cursor = cursors.load()
        val listing = listAll()
        // Spec §11: our folder lost what we uploaded (deleted in the Disk UI, or a new storage): upload everything again.
        if (cursor.uploadedUpTo > 0 && listing[self].orEmpty().none { it.range.last >= cursor.uploadedUpTo }) {
            log("own packs up to ${cursor.uploadedUpTo} are gone: uploading the whole log again")
            cursor = cursor.copy(uploadedUpTo = 0, ownEpochs = emptySet())
            cursors.save(cursor)
        }
        rotationEntry = membership.effectiveEntries.firstOrNull { it.body.membership_op?.rotate?.epoch == GroupKey.currentEpoch(membership) && it.body.membership_op?.rotate?.epoch != 0 }
        storageFull = false
        val (uploaded, afterUpload) = upload(epoch, cursor)
        // A full Disk still lets us read the others.
        val afterCompaction = if (storageFull) afterUpload else compactIfDue(epoch, afterUpload, forceCompaction)
        val (received, afterDownload) = download(listing, membership, afterCompaction)
        cursors.save(afterDownload)
        return if (storageFull) CloudResult.StorageFull else CloudResult.Synced(uploaded, received)
    }

    /** The newest generation, if it was sealed to us, joins the ring. */
    private suspend fun absorbRotation(membership: MembershipState) {
        val (epoch, key) = GroupKey.open(keys, membership) ?: return
        if (epoch !in ring.keys()) ring.put(epoch, key)
    }

    /** Seal with the group's newest generation when we hold it, else with the newest we hold. */
    private suspend fun writeEpoch(membership: MembershipState): Int? {
        val held = ring.keys().keys
        val current = GroupKey.currentEpoch(membership)
        return if (current in held) current else held.maxOrNull()
    }

    private suspend fun listAll(): Map<DeviceId, List<PackItem>> {
        val root = CloudPaths.root(groupId)
        val top = try {
            disk.list(root)
        } catch (e: DiskException.NotFound) {
            return emptyMap()
        }
        val out = HashMap<DeviceId, List<PackItem>>()
        for (dir in top) {
            if (!dir.isDir) continue
            val device = CloudPaths.parseDevice(dir.name) ?: continue
            val items = try {
                disk.list("$root/${dir.name}")
            } catch (e: DiskException.NotFound) {
                continue
            }
            out[device] = items.mapNotNull { i ->
                if (i.isDir) null else CloudPaths.parsePack(i.name)?.let { PackItem(i.name, i.sha256 ?: "size${i.size}", it) }
            }
        }
        return out
    }

    // ---- upload ----

    private suspend fun upload(epoch: Int, start: CloudCursor): Pair<Int, CloudCursor> {
        val upTo = store.versionVector()[self]
        if (upTo <= start.uploadedUpTo) return 0 to start
        val groupKey = ring.keys().getValue(epoch)
        var cursor = start
        var packs = 0
        for ((range, entries) in chunks(start.uploadedUpTo + 1, upTo)) {
            val bytes = PackCodec.seal(keys, groupId, entries, range.first, range.last, epoch, groupKey, clock(), keyUpdates(epoch))
            try {
                uploadPack(CloudPaths.packName(range.first, range.last), bytes)
            } catch (e: DiskException.StorageFull) {
                storageFull = true
                break
            }
            cursor = cursor.copy(uploadedUpTo = range.last, ownEpochs = cursor.ownEpochs + epoch)
            cursors.save(cursor)
            packs++
        }
        return packs to cursor
    }

    /**
     * Own live entries with seq in [from]..[upTo], cut into packs of at most [maxPackEntries] entries and
     * [MAX_PACK_BYTES]; consecutive ranges, the last ending at [upTo]. Own seqs up to [upTo] are accounted for here, so a
     * seq of the range without an entry is superseded or pruned: dead, as the signed range states.
     */
    private suspend fun chunks(from: Long, upTo: Long): List<Pair<LongRange, List<DecodedEntry>>> {
        val all = mutableListOf<DecodedEntry>()
        var after = from - 1
        while (after < upTo) {
            val page = store.entriesAfter(self, after, PAGE)
            if (page.isEmpty()) break
            all += page.filter { it.seq <= upTo }
            after = page.last().seq
        }
        val out = mutableListOf<Pair<LongRange, List<DecodedEntry>>>()
        var start = from
        var current = mutableListOf<DecodedEntry>()
        var bytes = 0
        for (e in all) {
            if (current.isNotEmpty() && (current.size >= maxPackEntries || bytes + e.bytes.size > MAX_PACK_BYTES)) {
                out += (start..current.last().seq) to current
                start = current.last().seq + 1
                current = mutableListOf()
                bytes = 0
            }
            current += e
            bytes += e.bytes.size
        }
        out += (start..upTo) to current
        return out
    }

    private suspend fun uploadPack(name: String, bytes: ByteArray) {
        val dir = CloudPaths.deviceDir(groupId, self)
        try {
            disk.upload("$dir/$name", bytes)
        } catch (e: DiskException.Conflict) {
            for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(groupId), dir)) disk.mkdir(d)
            disk.upload("$dir/$name", bytes)
        }
    }

    // ---- compaction (Task 8) ----

    private suspend fun compactIfDue(epoch: Int, start: CloudCursor, force: Boolean): CloudCursor = start

    // ---- download ----

    private suspend fun download(listing: Map<DeviceId, List<PackItem>>, start: MembershipState, cursor: CloudCursor): Pair<Int, CloudCursor> {
        var membership = start
        val seen = cursor.seen.toMutableMap()
        // Deferred packs (author or key generation unknown) are not final: kept apart from [seen], retried once the ring holds their epoch.
        val deferred = cursor.deferred.toMutableMap()
        val vector = store.versionVector()
        var pending = listing.filterKeys { it != self }
            .flatMap { (device, items) -> items.map { device to it } }
            .filter { (device, p) ->
                val k = key(device, p)
                k !in seen && (k !in deferred || deferred.getValue(k) in ring.keys())
            }
            .sortedWith(compareBy({ it.first }, { it.second.range.first }))
        var received = 0
        var round = 0
        while (pending.isNotEmpty() && round++ < MAX_ROUNDS) {
            val next = mutableListOf<Pair<DeviceId, PackItem>>()
            for ((device, p) in pending) {
                val k = key(device, p)
                try {
                    val bytes = try {
                        disk.download("${CloudPaths.deviceDir(groupId, device)}/${p.name}")
                    } catch (e: DiskException.NotFound) {
                        continue // compacted away since the listing
                    }
                    if (p.range.last <= vector[device]) {
                        // Nothing to ingest, but the file still pins its key generation.
                        seen[k] = PackCodec.epochOf(bytes) ?: -1
                        deferred.remove(k)
                        continue
                    }
                    membership = learnKeyUpdates(bytes, membership)
                    when (val o = PackCodec.open(bytes, ring.keys(), groupId, device, membership)) {
                        is PackOutcome.UnknownAuthor -> {
                            deferred[k] = PackCodec.epochOf(bytes) ?: -1
                            next += device to p
                        }
                        is PackOutcome.KeyUnknown -> {
                            deferred[k] = o.epoch
                            next += device to p
                        }
                        is PackOutcome.Rejected -> {
                            log("rejected pack ${device.hex.take(8)}/${p.name}: ${o.reason}")
                            seen[k] = -1
                            deferred.remove(k)
                        }
                        is PackOutcome.Opened -> {
                            val r = ingest(o.pack, membership)
                            if (r.first !== membership) absorbRotation(r.first)
                            membership = r.first
                            received += r.second
                            seen[k] = o.pack.epoch
                            deferred.remove(k)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DiskException.Unauthorized) {
                    throw e
                } catch (e: DiskException.StorageFull) {
                    throw e
                } catch (e: IOException) {
                    throw e // network trouble: the whole run is retried
                } catch (e: Exception) {
                    log("pack ${device.hex.take(8)}/${p.name} skipped: $e")
                    seen[k] = -1
                    deferred.remove(k)
                }
            }
            // No progress (nothing opened this round): they wait in [deferred] for a key or a membership change.
            if (next.size == pending.size) break
            pending = next
        }
        val present = listing.filterKeys { it != self }.flatMap { (device, items) -> items.map { key(device, it) } }.toSet()
        val keptSeen = seen.filterKeys { it in present }
        val keptDeferred = deferred.filterKeys { it in present && it !in keptSeen }
        // Spec §5: keep a previous generation only while files sealed with it remain (and the CloudAccess is sealed with it).
        if (present.all { it in keptSeen || it in keptDeferred }) {
            val inUse = (keptSeen.values + keptDeferred.values).filter { it >= 0 }.toSet()
            ring.retain(inUse + setOfNotNull(writeEpoch(membership), accessEpoch(membership)))
        }
        return received to cursor.copy(seen = keptSeen, deferred = keptDeferred)
    }

    /** The key generation of the current owner's newest CloudAccess: readers need it to open the access token. */
    private suspend fun accessEpoch(membership: MembershipState): Int? {
        val owner = membership.storageOwner ?: return null
        return store.liveEntries().filter { it.author == owner.deviceId && it.kind == EntryKind.CLOUD_ACCESS }
            .maxByOrNull { it.seq }?.body?.cloud_access?.key_epoch
    }

    /**
     * A pack sealed with a generation we lack may carry, in clear, the rotation op that introduced it. Signed and checked
     * against membership like any op (admin-only, HPKE-sealed keys), so it only helps a member it was sealed to.
     */
    private suspend fun learnKeyUpdates(file: ByteArray, membership: MembershipState): MembershipState {
        val held = ring.keys().keys
        val updates = PackCodec.keyUpdates(file).filter { MembershipLimits.withinLimits(it) && it.body.membership_op!!.rotate!!.epoch !in held }
        if (updates.isEmpty()) return membership
        val next = Membership.compute(groupId, store.membershipEntries() + updates) ?: return membership
        if (next.discoveryRotation == membership.discoveryRotation) return membership
        store.storeMembership(next.effectiveEntries.toList())
        absorbRotation(next)
        return next
    }

    private suspend fun ingest(pack: OpenedPack, start: MembershipState): Pair<MembershipState, Int> {
        val author = pack.author
        val entries = pack.entries.mapNotNull { raw -> runCatching { DecodedEntry.decode(raw) }.getOrNull() }
            .filter { it.author == author && it.seq in pack.from..pack.to }
        var membership = start
        val ops = entries.filter { it.isMembershipRelevant && MembershipLimits.withinLimits(it) }
        if (ops.isNotEmpty()) {
            Membership.compute(groupId, store.membershipEntries() + ops)?.let {
                store.storeMembership(it.effectiveEntries.toList())
                membership = it
            }
        }
        val validator = EntryValidator(groupId, membership, policy)
        val accepted = mutableListOf<DecodedEntry>()
        val expired = mutableSetOf<Long>()
        var liveSet: LiveSet? = null
        fun noteLiveSet(e: DecodedEntry) {
            val s = LiveSet.of(e) ?: return
            if (liveSet == null || s.upTo > liveSet!!.upTo) liveSet = s
        }
        for (e in entries) {
            when (val v = validator.check(e, alreadyAccounted = store.isAccounted(e.author, e.seq))) {
                Verdict.Accept -> {
                    accepted += e
                    noteLiveSet(e)
                }
                Verdict.Expired -> expired += e.seq
                Verdict.Duplicate -> noteLiveSet(e)
                Verdict.Unwanted -> Unit
                is Verdict.Invalid -> log("invalid ${author.hex.take(8)}#${e.seq} in pack: ${v.reason}")
                is Verdict.Skipped -> log("skipped ${author.hex.take(8)}#${e.seq} in pack: ${v.reason}")
            }
        }
        store.applyBatch(accepted, if (expired.isEmpty()) emptyMap() else mapOf(author to expired))
        liveSet?.let { store.accountLiveSet(author, it) }
        // The author signed that this range is complete; it closes our gap only if we hold everything below it.
        if (policy.showOthers && store.versionVector()[author] >= pack.from - 1) store.completeAuthor(author, pack.to)
        return membership to accepted.size
    }

    private fun key(device: DeviceId, p: PackItem) = "${device.hex}/${p.name}/${p.sha}"

    companion object {
        const val MAX_PACK_ENTRIES = 5_000
        const val MAX_PACK_BYTES = 4 shl 20
        const val PAGE = 500
        const val MAX_ROUNDS = 3
        const val COMPACTION_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
