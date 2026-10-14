package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.toHex
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val smallPackBytes: Int = SMALL_PACK_BYTES,
) {
    private val self = keys.deviceId
    private val groupId = store.groupId

    private class PackItem(val name: String, val sha: String, val range: LongRange, val size: Long)

    /** The effective rotation op of the group's newest generation, if any: sent in clear next to each own pack. */
    private var rotationEntry: DecodedEntry? = null

    /** Set by [upload] when the Disk refused a pack for lack of space. */
    private var storageFull = false

    private fun keyUpdates(epoch: Int): List<DecodedEntry> =
        listOfNotNull(rotationEntry?.takeIf { it.body.membership_op!!.rotate!!.epoch == epoch })

    /** Runs never overlap: [rotationEntry], [storageFull] and the cursor belong to one run at a time. */
    private val running = Mutex()

    suspend fun run(forceCompaction: Boolean = false): CloudResult = running.withLock { guardedRun(forceCompaction) }

    private suspend fun guardedRun(forceCompaction: Boolean): CloudResult = try {
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
    } catch (e: RuntimeException) {
        // The local store failed while ingesting (not a hostile file): nothing was marked seen, the next run repeats it.
        log("cloud sync aborted by a local failure: $e")
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
        // Checked by the names we wrote, not by the ranges listed: a renamed file must not stand in for a lost one.
        val ownNames = listing[self].orEmpty().map { it.name }.toSet()
        if (cursor.uploadedUpTo > 0 && (cursor.ownPacks.isEmpty() || cursor.ownPacks.keys.any { it !in ownNames })) {
            log("own packs up to ${cursor.uploadedUpTo} are gone: uploading the whole log again")
            cursor = cursor.copy(uploadedUpTo = 0, ownPacks = emptyMap())
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

    /**
     * The newest generation, if it was sealed to us, joins the ring. It replaces a key we hold for that generation: when two
     * admins rotate at once, each holds its own key until it learns that Membership kept the other one.
     */
    private suspend fun absorbRotation(membership: MembershipState) {
        val (epoch, key) = GroupKey.open(keys, membership) ?: return
        val held = ring.keys()[epoch]
        if (held == null || !held.contentEquals(key)) {
            if (held != null) log("generation $epoch: another admin's key won, switching to it")
            ring.put(epoch, key)
        }
    }

    private suspend fun keyId(epoch: Int) = KeyId(epoch, GroupKey.fingerprint(ring.keys().getValue(epoch)))

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
                if (i.isDir) null else CloudPaths.parsePack(i.name)?.let { PackItem(i.name, versionOf(i), it, i.size) }
            }
        }
        return out
    }

    // ---- upload ----

    private suspend fun upload(epoch: Int, start: CloudCursor): Pair<Int, CloudCursor> {
        val upTo = store.versionVector()[self]
        if (upTo <= start.uploadedUpTo) return 0 to start
        val groupKey = ring.keys().getValue(epoch)
        val keyId = keyId(epoch)
        var cursor = start
        var packs = 0
        for ((range, entries) in chunks(start.uploadedUpTo + 1, upTo)) {
            val bytes = PackCodec.seal(keys, groupId, entries, range.first, range.last, epoch, groupKey, clock(), keyUpdates(epoch))
            val name = CloudPaths.packName(range.first, range.last)
            try {
                uploadPack(name, bytes)
            } catch (e: DiskException.StorageFull) {
                storageFull = true
                break
            }
            cursor = cursor.copy(uploadedUpTo = range.last, ownPacks = cursor.ownPacks + (name to keyId))
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

    /**
     * Spec §5: once a day the author merges its small files into one; after a key change it re-encrypts all of them with
     * the new key at this nearest compaction. Daily, only the tail of small packs (the newest ones, each under
     * [smallPackBytes]) is merged, so older big packs stay untouched and readers fetch kilobytes (spec §9). After a key change
     * (or when forced) all own live entries up to the cursor are re-sealed. New files first, then the replaced ones are
     * forgotten and deleted: a crash in between leaves duplicates, which readers drop as Duplicate.
     */
    private suspend fun compactIfDue(epoch: Int, start: CloudCursor, force: Boolean): CloudCursor {
        val now = clock()
        val current = keyId(epoch)
        // An older generation, or another key of this one (a concurrent rotation we lost): re-encrypt everything.
        val full = force || start.ownPacks.values.any { it != current }
        if (!full && now - start.lastCompactionMs < COMPACTION_INTERVAL_MS) return start
        val done = start.copy(lastCompactionMs = now)
        val dir = CloudPaths.deviceDir(groupId, self)
        val existing = try {
            disk.list(dir).mapNotNull { i -> if (i.isDir) null else CloudPaths.parsePack(i.name)?.let { PackItem(i.name, "", it, i.size) } }
        } catch (e: DiskException.NotFound) {
            emptyList()
        }
        if (start.uploadedUpTo == 0L) return done.also { cursors.save(it) }
        val replaced = if (full) {
            existing
        } else {
            existing.filter { it.range.last <= start.uploadedUpTo }
                .sortedWith(compareBy({ it.range.first }, { it.range.last }))
                .takeLastWhile { it.size < smallPackBytes }
        }
        if (!full && replaced.size < 2) return done.also { cursors.save(it) }
        val from = if (full) 1L else replaced.first().range.first
        val replacedNames = replaced.map { it.name }.toSet()
        val groupKey = ring.keys().getValue(epoch)
        val planned = chunks(from, start.uploadedUpTo).map { (r, entries) -> CloudPaths.packName(r.first, r.last) to (r to entries) }
        val written = mutableMapOf<String, KeyId>()
        try {
            for ((name, chunk) in planned) {
                // A merge keeps a tail file whose range comes out the same, but only one recorded with the current key: an
                // unrecorded file of that name is written again and recorded, or it would stay out of ownPacks for good.
                if (!full && name in replacedNames && start.ownPacks[name] == current) continue
                val (range, entries) = chunk
                uploadPack(name, PackCodec.seal(keys, groupId, entries, range.first, range.last, epoch, groupKey, now, keyUpdates(epoch)))
                written[name] = current
            }
        } catch (e: DiskException.StorageFull) {
            // Nothing is deleted and the cursor stays: compaction is retried at the next run, reading still goes on.
            storageFull = true
            return start
        }
        val obsolete = replacedNames - planned.map { it.first }.toSet()
        // After a full re-seal every own pack on the Disk is one just written.
        val compacted = done.copy(ownPacks = if (full) written else start.ownPacks + written - obsolete)
        cursors.save(compacted) // forgotten before deleted: a lost delete leaves a stray file, never a missing own name
        for (name in obsolete) {
            try {
                disk.delete("$dir/$name")
            } catch (e: DiskException.NotFound) {
                // already gone
            }
        }
        return compacted
    }

    // ---- download ----

    private class Fetched(val bytes: ByteArray, val epoch: Int?, val updates: List<DecodedEntry>)

    /**
     * Runs the hostile-file part of one pack. A failure of the file itself (garbage, a decoder error) marks it seen = -1 and
     * yields null; network, auth and quota trouble propagates (the run is retried), as does cancellation.
     */
    private inline fun <T : Any> guarded(
        seen: MutableMap<String, Int>,
        deferred: MutableMap<String, DeferredPack>,
        device: DeviceId,
        p: PackItem,
        block: () -> T?,
    ): T? = try {
        block()
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
        key(device, p).let { seen[it] = REJECTED; deferred.remove(it) }
        null
    }

    private suspend fun download(listing: Map<DeviceId, List<PackItem>>, start: MembershipState, cursor: CloudCursor): Pair<Int, CloudCursor> {
        var membership = start
        val seen = cursor.seen.toMutableMap()
        // Deferred packs (author, key generation or key unknown) are not final: kept apart from [seen], retried once the ring
        // holds a key that may open them.
        val deferred = cursor.deferred.toMutableMap()
        val vector = store.versionVector()
        val ringAtStart = ring.keys()
        // Spec §5: a covered pack's generation only decides which keys the ring keeps. Keys we write with or need for the
        // access are kept anyway, so it matters only while the ring holds another one.
        val pinned = setOfNotNull(writeEpoch(start), accessEpoch(start))
        fun generationMatters(held: Map<Int, ByteArray>) = (held.keys - pinned).isNotEmpty()
        var pending = listing.filterKeys { it != self }
            .flatMap { (device, items) -> items.map { device to it } }
            .filter { (device, p) ->
                val k = key(device, p)
                val unread = seen[k] == COVERED_UNREAD && generationMatters(ringAtStart)
                (k !in seen || unread) && retryNow(deferred[k], ringAtStart, membership)
            }
            .sortedWith(compareBy({ it.first }, { it.second.range.first }))
        var received = 0
        var round = 0
        while (pending.isNotEmpty() && round++ < MAX_ROUNDS) {
            val next = mutableListOf<Pair<DeviceId, PackItem>>()
            for ((device, p) in pending) {
                val k = key(device, p)
                // Everything in it is ours already (its name says so). Its generation only decides which keys the ring keeps,
                // which cannot matter while the ring holds one: skip the download and read it when the ring holds more.
                val covered = p.range.last <= vector[device]
                if (covered && !generationMatters(ring.keys())) {
                    seen[k] = COVERED_UNREAD
                    deferred.remove(k)
                    continue
                }
                // Only the hostile part of a file (download, decoding, opening) is guarded: a file that fails there is marked
                // seen = -1. Local store errors below propagate and abort the run, leaving the pack unseen.
                val fetched = guarded(seen, deferred, device, p) {
                    val bytes = try {
                        disk.download("${CloudPaths.deviceDir(groupId, device)}/${p.name}")
                    } catch (e: DiskException.NotFound) {
                        return@guarded null // compacted away since the listing
                    }
                    Fetched(bytes, PackCodec.epochOf(bytes), PackCodec.keyUpdates(bytes))
                } ?: continue
                if (!covered) membership = learnKeyUpdates(fetched.updates, membership)
                val held = ring.keys() // outside the guard: a ring failure is ours, not the file's
                val outcome = guarded(seen, deferred, device, p) {
                    PackCodec.open(fetched.bytes, held, groupId, device, membership, expected = p.range)
                } ?: continue
                when (outcome) {
                    is PackOutcome.UnknownAuthor -> {
                        deferred[k] = DeferredPack(fetched.epoch ?: -1, membershipHash = membershipHash(membership))
                        next += device to p
                    }
                    is PackOutcome.KeyUnknown -> if (covered) {
                        seen[k] = outcome.epoch // nothing to read in it, and a generation we lack pins nothing
                        deferred.remove(k)
                    } else {
                        deferred[k] = DeferredPack(outcome.epoch)
                        next += device to p
                    }
                    is PackOutcome.KeyMismatch -> {
                        log("pack ${device.hex.take(8)}/${p.name} does not open under our key of generation ${outcome.epoch}")
                        deferred[k] = DeferredPack(outcome.epoch, GroupKey.fingerprint(held.getValue(outcome.epoch)))
                        next += device to p
                    }
                    is PackOutcome.Rejected -> {
                        log("rejected pack ${device.hex.take(8)}/${p.name}: ${outcome.reason}")
                        seen[k] = REJECTED
                        deferred.remove(k)
                    }
                    is PackOutcome.Opened -> if (covered) {
                        // Nothing to ingest, but the file (signed, its range matching its name) pins its key generation.
                        seen[k] = outcome.pack.epoch
                        deferred.remove(k)
                    } else {
                        val r = ingest(outcome.pack, membership)
                        if (r.first !== membership) absorbRotation(r.first)
                        membership = r.first
                        received += r.second
                        seen[k] = outcome.pack.epoch
                        deferred.remove(k)
                    }
                }
            }
            // No progress (nothing opened this round): they wait in [deferred] for a key or a membership change.
            if (next.size == pending.size) break
            // Only what may open now goes round again: a key that did not open a pack still does not.
            val held = ring.keys()
            pending = next.filter { (device, p) -> retryNow(deferred[key(device, p)], held, membership) }
        }
        val listed = listing.filterKeys { it != self }.flatMap { (device, items) -> items.map { device to key(device, it) } }
        val allKeys = listed.map { it.second }.toSet()
        val keptSeen = seen.filterKeys { it in allKeys }
        val keptDeferred = deferred.filterKeys { it in allKeys && it !in keptSeen }
        // Spec §5: keep a previous generation only while files sealed with it remain (and the CloudAccess is sealed with it).
        // Only packs of members count: a folder planted by a token holder must not keep an old key alive. A covered pack
        // skipped unread has an unknown generation: it blocks dropping while the ring holds a key nothing pins.
        val members = membership.active.keys + membership.departed.keys
        val memberKeys = listed.filter { it.first in members }.map { it.second }.toSet()
        val keep = setOfNotNull(writeEpoch(membership), accessEpoch(membership))
        val unread = (ring.keys().keys - keep).isNotEmpty() && keptSeen.filterKeys { it in memberKeys }.values.any { it == COVERED_UNREAD }
        if (!unread && memberKeys.all { it in keptSeen || it in keptDeferred }) {
            val inUse = (keptSeen.filterKeys { it in memberKeys }.values + keptDeferred.filterKeys { it in memberKeys }.values.map { it.epoch })
                .filter { it >= 0 }.toSet()
            ring.retain(inUse + keep)
        }
        return received to cursor.copy(seen = keptSeen, deferred = keptDeferred)
    }

    /**
     * The key generation of the current owner's newest CloudAccess: readers need it to open the access token. Pages through
     * the owner's log only (a parent's: small), never the whole database.
     */
    private suspend fun accessEpoch(membership: MembershipState): Int? {
        val owner = membership.storageOwner ?: return null
        var newest: DecodedEntry? = null
        var after = 0L
        while (true) {
            val page = store.entriesAfter(owner.deviceId, after, PAGE)
            if (page.isEmpty()) break
            page.lastOrNull { it.kind == EntryKind.CLOUD_ACCESS }?.let { newest = it }
            after = page.last().seq
        }
        return newest?.body?.cloud_access?.key_epoch
    }

    private fun mayOpen(d: DeferredPack, ring: Map<Int, ByteArray>): Boolean {
        val key = ring[d.epoch] ?: return false
        return d.failedKey == null || GroupKey.fingerprint(key) != d.failedKey
    }

    /**
     * A pack sealed with a generation we lack may carry, in clear, the rotation op that introduced it. Signed and checked
     * against membership like any op (admin-only, HPKE-sealed keys), so it only helps a member it was sealed to. An op of
     * a generation we already hold still counts: another admin may have created it at the same time, and Membership may
     * keep that one.
     */
    private suspend fun learnKeyUpdates(offered: List<DecodedEntry>, membership: MembershipState): MembershipState {
        val current = GroupKey.currentEpoch(membership)
        val updates = offered.filter {
            MembershipLimits.withinLimits(it) && it.body.membership_op!!.rotate!!.epoch >= current && it !in membership.effectiveEntries
        }
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

    /**
     * What tells two versions of one pack apart: the Disk's sha256, else its md5, else its modified time. A listing with
     * none of them (never seen from Yandex) gets a key of this run: such a pack is read again on every run, never trusted
     * by its size.
     */
    private fun versionOf(i: DiskItem): String =
        i.sha256 ?: i.md5?.let { "md5:$it" } ?: i.modified?.let { "mod:$it" } ?: "run:${clock()}"

    private fun membershipHash(m: MembershipState): String = Membership.hash(groupId, m.effectiveEntries).copyOf(8).toHex()

    /** A deferred pack goes round again only when something changed that may open it. */
    private fun retryNow(d: DeferredPack?, held: Map<Int, ByteArray>, membership: MembershipState): Boolean = when {
        d == null -> true
        d.membershipHash != null -> d.membershipHash != membershipHash(membership)
        else -> mayOpen(d, held)
    }

    companion object {
        const val MAX_PACK_ENTRIES = 5_000
        const val MAX_PACK_BYTES = 4 shl 20
        const val PAGE = 500
        const val MAX_ROUNDS = 3
        const val COMPACTION_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** [CloudCursor.seen] value of a pack that did not open and never will. */
        const val REJECTED = -1

        /** [CloudCursor.seen] value of a pack skipped as covered by its name: its generation is unknown, read when it matters. */
        const val COVERED_UNREAD = -2

        /** Daily compaction merges the newest own packs below this size; bigger ones stay as they are. */
        const val SMALL_PACK_BYTES = 256 shl 10
    }
}
