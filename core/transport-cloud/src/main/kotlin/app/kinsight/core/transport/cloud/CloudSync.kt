package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.Primitives
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
import app.kinsight.core.sync.SyncSession
import app.kinsight.core.sync.Verdict
import app.kinsight.core.sync.accountLiveSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

sealed interface CloudResult {
    /**
     * [skipped]: packs of others (or own kept ones) the Disk would not hand out in this run (Task 14). They are read again
     * at later runs. [retryAtMs]: on the sync's clock, the earliest time a pack that failed may be tried again (its backoff,
     * or a few minutes after a network drop); the caller schedules a follow-up run for it. Null when none waits.
     */
    data class Synced(val uploadedPacks: Int, val received: Int, val skipped: Int = 0, val retryAtMs: Long? = null) : CloudResult
    data object NotMember : CloudResult

    /** The ring holds no generation of the group key: nothing can be sealed or read (Y2 seeds it at pairing). */
    data object NoGroupKey : CloudResult
    data object Unauthorized : CloudResult
    /** The Disk refused an upload; the others were still read, [received] entries of theirs (alerts follow them). */
    data class StorageFull(val received: Int = 0) : CloudResult
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
    /**
     * Runs never overlap: [rotationEntry], [storageFull] and the cursor belong to one run at a time. The app passes one lock
     * for the whole process, so two instances over the same cursor store never run at once either (Y1 review).
     */
    private val running: Mutex = Mutex(),
    /**
     * The storage owner's storage id (spec §11 «storage.id пересоздаёт владелец»): when the root listing lacks
     * `storage.id`, the run writes it again. Null on every other device: only the owner writes it. One that is not 16
     * bytes is a wiring error: told once and ignored, never a run retried forever.
     */
    ownedStorageId: ByteArray? = null,
    /** The jitter factor of a pack's backoff (Task 14), within 1 ± [RETRY_JITTER]. */
    private val jitter: () -> Double = { Random.nextDouble(1 - RETRY_JITTER, 1 + RETRY_JITTER) },
) {
    private val self = keys.deviceId
    private val groupId = store.groupId
    private val ownedStorageId: ByteArray? = ownedStorageId?.takeIf { it.size == STORAGE_ID_BYTES }

    init {
        if (ownedStorageId != null && this.ownedStorageId == null) log("owned storage id of ${ownedStorageId.size} bytes ignored")
    }

    private class PackItem(val name: String, val sha: String, val range: LongRange, val size: Long)

    /** The effective rotation op of the group's newest generation, if any: sent in clear next to each own pack. */
    private var rotationEntry: DecodedEntry? = null

    /** Set by [upload] when the Disk refused a pack for lack of space. */
    private var storageFull = false

    /** Set by [listAll] when the Disk refused `storage.id` for lack of space: the run still reads the others. */
    private var storageIdFull = false

    /** Packs the Disk would not hand out in this run (Task 14): each one is skipped, the run goes on. */
    private var skipped = 0

    /** Keys of the packs skipped in this run. */
    private val skippedKeys = HashSet<String>()

    /** A read of this run dropped without an HTTP status (the network went away): no more downloads in this run. */
    private var networkLost = false

    /** When a pack skipped for a network drop in this run may be tried again. */
    private var droppedRetryAtMs: Long? = null

    /** The version key of this run for a listing without any version (see [versionOf]); new for every exchange. */
    private var runVersion = ""

    private fun keyUpdates(epoch: Int): List<DecodedEntry> =
        listOfNotNull(rotationEntry?.takeIf { it.body.membership_op!!.rotate!!.epoch == epoch })

    suspend fun run(forceCompaction: Boolean = false): CloudResult = running.withLock { guardedRun(forceCompaction) }

    private suspend fun guardedRun(forceCompaction: Boolean): CloudResult = try {
        exchange(forceCompaction)
    } catch (e: DiskException.Unauthorized) {
        CloudResult.Unauthorized
    } catch (e: DiskException.StorageFull) {
        CloudResult.StorageFull()
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
        val before = Membership.compute(groupId, store.membershipEntries())
        if (before == null || self !in before.active) return CloudResult.NotMember
        // The counter, not the clock alone: two runs at one clock reading (a fixed test clock) must not share the key.
        runVersion = "run:${clock()}:${RUNS.incrementAndGet()}"
        skipped = 0
        skippedKeys.clear()
        networkLost = false
        droppedRetryAtMs = null
        absorbRotation(before)
        writeEpoch(before) ?: return CloudResult.NoGroupKey
        var cursor = cursors.load()
        storageIdFull = false
        val listing = listAll()
        val (lifted, membership) = liftOwn(listing[self].orEmpty(), before, cursor)
        cursor = lifted
        // Own membership ops lost with the database came back with the lift: this run already seals and reads with them.
        if (membership !== before) absorbRotation(membership)
        val epoch = writeEpoch(membership) ?: return CloudResult.NoGroupKey
        // Spec §11: our folder lost what we uploaded (deleted in the Disk UI, or a new storage): upload everything again.
        // Checked by the names we wrote, not by the ranges listed: a renamed file must not stand in for a lost one.
        // A kept pack (A-4) is never written by us: one gone from the Disk leaves its range a hole, never re-sealed empty.
        val ownNames = listing[self].orEmpty().map { it.name }.toSet()
        val written = cursor.ownPacks.filterValues { !it.kept }
        if (cursor.uploadedUpTo > 0 && (cursor.ownPacks.isEmpty() || written.keys.any { it !in ownNames })) {
            log("own packs up to ${cursor.uploadedUpTo} are gone: uploading the whole log again")
            cursor = cursor.copy(uploadedUpTo = 0, ownPacks = cursor.ownPacks - written.keys, locationDue = cursor.locationDue - written.keys)
            cursors.save(cursor)
        }
        rotationEntry = membership.effectiveEntries.firstOrNull { it.body.membership_op?.rotate?.epoch == GroupKey.currentEpoch(membership) && it.body.membership_op?.rotate?.epoch != 0 }
        storageFull = storageIdFull
        val (uploaded, afterUpload) = upload(epoch, cursor)
        // A full Disk still lets us read the others.
        val afterCompaction = if (storageFull) afterUpload else compactIfDue(epoch, afterUpload, forceCompaction)
        val afterExpiry = if (storageFull) afterCompaction else expireLocations(epoch, afterCompaction)
        val (received, afterDownload) = download(listing, membership, afterExpiry)
        cursors.save(afterDownload)
        return if (storageFull) CloudResult.StorageFull(received) else CloudResult.Synced(uploaded, received, skipped, retryAt(afterDownload))
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
            ownedStorageId?.let { writeStorageId(it) }
            return emptyMap()
        }
        ownedStorageId?.let { id ->
            if (top.none { !it.isDir && it.name == CloudPaths.STORAGE_ID && it.size == id.size.toLong() }) {
                log("storage.id is missing: writing it again")
                writeStorageId(id)
            }
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

    /** A full Disk refusing it does not end the run: the others are still read, and the run says StorageFull. */
    private suspend fun writeStorageId(id: ByteArray) {
        try {
            StorageMove.writeStorageId(disk, groupId, id)
        } catch (e: DiskException.StorageFull) {
            log("storage.id not written: the Disk is full")
            storageIdFull = true
        }
    }

    // ---- upload ----

    private suspend fun upload(epoch: Int, start: CloudCursor): Pair<Int, CloudCursor> {
        val upTo = store.versionVector()[self]
        if (upTo <= start.uploadedUpTo) return 0 to start
        val groupKey = ring.keys().getValue(epoch)
        val keyId = keyId(epoch)
        var cursor = start
        var packs = 0
        for ((range, entries) in ownChunks(start.uploadedUpTo + 1, upTo, start)) {
            val bytes = PackCodec.seal(keys, groupId, entries, range.first, range.last, epoch, groupKey, clock(), keyUpdates(epoch))
            val name = CloudPaths.packName(range.first, range.last)
            try {
                uploadPack(name, bytes)
            } catch (e: DiskException.StorageFull) {
                storageFull = true
                break
            }
            cursor = cursor.copy(uploadedUpTo = range.last, ownPacks = cursor.ownPacks + (name to keyId), locationDue = cursor.locationDue.due(name, entries))
            cursors.save(cursor)
            packs++
        }
        // What is left above the last pack written is kept packs: on the Disk already.
        if (!storageFull && cursor.uploadedUpTo < upTo) cursor = cursor.copy(uploadedUpTo = upTo).also { cursors.save(it) }
        return packs to cursor
    }

    /**
     * Own live entries with seq in [from]..[upTo], cut into packs of at most [maxPackEntries] entries and
     * [MAX_PACK_BYTES]; consecutive ranges, the last ending at [upTo]. Own seqs up to [upTo] are accounted for here, so a
     * seq of the range without an entry is superseded or pruned: dead, as the signed range states. Spec §8: a LOCATION
     * past the 48 h retention never goes into a pack (the local prune may not have run yet), and a pack holds LOCATION
     * entries whose fix times span at most [LOCATION_SPAN_MS] (min to max), so its one re-seal ([expireLocations]) comes
     * at most that long after its oldest point expired.
     */
    private suspend fun chunks(from: Long, upTo: Long): List<Pair<LongRange, List<DecodedEntry>>> {
        val all = live(from, upTo)
        val out = mutableListOf<Pair<LongRange, List<DecodedEntry>>>()
        var start = from
        var current = mutableListOf<DecodedEntry>()
        var bytes = 0
        var minFix: Long? = null
        var maxFix: Long? = null
        for (e in all) {
            val fix = e.body.location?.fix_time_ms
            // The pack's whole min..max of fix times, so points out of time order cannot stretch it.
            val tooLong = fix != null && minFix != null && maxOf(maxFix!!, fix) - minOf(minFix, fix) > LOCATION_SPAN_MS
            if (current.isNotEmpty() && (current.size >= maxPackEntries || bytes + e.bytes.size > MAX_PACK_BYTES || tooLong)) {
                out += (start..current.last().seq) to current
                start = current.last().seq + 1
                current = mutableListOf()
                bytes = 0
                minFix = null
                maxFix = null
            }
            current += e
            bytes += e.bytes.size
            if (fix != null) {
                minFix = minOf(minFix ?: fix, fix)
                maxFix = maxOf(maxFix ?: fix, fix)
            }
        }
        out += (start..upTo) to current
        return out
    }

    /** Own entries with seq in [from]..[upTo] that may go to the Disk: all but a LOCATION past retention (spec §8). */
    private suspend fun live(from: Long, upTo: Long): List<DecodedEntry> {
        val now = clock()
        val all = mutableListOf<DecodedEntry>()
        var after = from - 1
        while (after < upTo) {
            val page = store.entriesAfter(self, after, PAGE)
            if (page.isEmpty()) break
            all += page.filter { it.seq <= upTo && !expiredLocation(it, now) }
            after = page.last().seq
        }
        return all
    }

    /** SyncPolicy.isExpired for LOCATION, on this sync's clock: older than the retention, or that far in the future. */
    private fun expiredLocation(e: DecodedEntry, now: Long): Boolean {
        val fix = e.body.location?.fix_time_ms ?: return false
        return fix < now - SyncPolicy.LOCATION_RETENTION_MS || fix > now + SyncPolicy.LOCATION_RETENTION_MS
    }

    /** [this] with own pack [name]'s re-seal time for [entries]: when the newest LOCATION is past retention, or none. */
    private fun Map<String, Long>.due(name: String, entries: List<DecodedEntry>): Map<String, Long> {
        val newest = entries.mapNotNull { it.body.location?.fix_time_ms }.maxOrNull() ?: return this - name
        return this + (name to newest + SyncPolicy.LOCATION_RETENTION_MS + LOCATION_MARGIN_MS)
    }

    /**
     * Spec §8 on the Disk (Y4 final review Important 4): every own pack whose LOCATION entries are all past retention is
     * re-sealed once, with its signed range kept and those entries left out (readers treat a missing seq of a signed range
     * as dead, like a pruned one), and leaves [CloudCursor.locationDue]. Only a child signs LOCATION, so only a child's
     * packs are ever due. Every run, not only at the daily compaction: a big pack the merge never takes is cleaned too.
     * A full Disk keeps the rest due for the next run.
     */
    private suspend fun expireLocations(epoch: Int, start: CloudCursor): CloudCursor {
        val now = clock()
        val due = start.locationDue.filter { (name, at) -> at <= now && start.ownPacks[name]?.kept == false }.keys.sorted()
        // A pack forgotten since (merged away, or kept) is no longer ours to clean.
        val stale = start.locationDue.keys.filter { it !in start.ownPacks || start.ownPacks.getValue(it).kept }
        var cursor = if (stale.isEmpty()) start else start.copy(locationDue = start.locationDue - stale.toSet()).also { cursors.save(it) }
        if (due.isEmpty()) return cursor
        val groupKey = ring.keys().getValue(epoch)
        val current = keyId(epoch)
        for (name in due) {
            val range = CloudPaths.parsePack(name) ?: continue
            val entries = live(range.first, range.last)
            try {
                uploadPack(name, PackCodec.seal(keys, groupId, entries, range.first, range.last, epoch, groupKey, now, keyUpdates(epoch)))
            } catch (e: DiskException.StorageFull) {
                storageFull = true
                break
            }
            cursor = cursor.copy(ownPacks = cursor.ownPacks + (name to current), locationDue = cursor.locationDue.due(name, entries))
            cursors.save(cursor)
        }
        return cursor
    }

    /**
     * A-4 (Y1 review): our database lost entries while our keys survived, and our own folder holds packs above what we
     * hold. Each one is checked (our signature, the range in its name): one we can open gives its entries back to the
     * store; one sealed with a key we lack still proves the range is ours and is kept as it is ([KeyId.kept]: never
     * overwritten, re-sealed or deleted). Those seqs are accounted, so they are never signed again, and they count as
     * uploaded, so no compaction re-seals the range from an emptied log. Bounded like Bluetooth: a pack whose name ends
     * more than SyncSession.MAX_OWN_SEQ_JUMP above what we hold is ignored (told once per name). A file that is not ours
     * is remembered by its version in [CloudCursor.seen], so it is not downloaded again on every run. A kept pack is
     * read again once the ring holds a key of its generation that may open it; its entries then come back.
     * Returns the cursor and the membership with what the lift ingested (own membership ops lost with the database).
     */
    private suspend fun liftOwn(own: List<PackItem>, start: MembershipState, cursor: CloudCursor): Pair<CloudCursor, MembershipState> {
        val held = store.versionVector()[self]
        val seen = cursor.seen.toMutableMap()
        // Names are not verified yet: only the ones over the bound are dropped, the lift goes on from the others.
        val (above, far) = own.filter { it.range.last > held }.partition { it.range.last - held <= SyncSession.MAX_OWN_SEQ_JUMP }
        for (p in far) {
            if (seen.put(key(self, p), FAR) != FAR) log("own folder: ${p.name} is too far above $held, ignored")
        }
        val ring0 = ring.keys()
        val now = clock()
        val failing = cursor.failing.toMutableMap()
        val retry = own.filter { p ->
            val k = cursor.ownPacks[p.name]?.takeIf { it.kept } ?: return@filter false
            p.range.last <= held && seen[key(self, p)] != REJECTED && mayOpen(DeferredPack(k.epoch, k.failedKey), ring0) &&
                !backedOff(failing[key(self, p)], now)
        }.toSet()
        val sorted = (above.filter { seen[key(self, it)] != REJECTED } + retry).sortedBy { it.range.first }
        var membership = start
        var lifted = held
        var ownPacks = cursor.ownPacks
        var due = cursor.locationDue
        val dir = CloudPaths.deviceDir(groupId, self)
        for (p in sorted) {
            val bytes = try {
                disk.download("$dir/${p.name}")
            } catch (e: DiskException.NotFound) {
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Task 14: a kept pack retried is read again later, as one of the others. A pack above what we hold is
                // not: its range must be known before we sign again (A-4), so its failure still ends the run.
                if (p !in retry || (e is IOException && !perFile(e))) throw e
                skip(failing, key(self, p), "$dir/${p.name}", e, now)
                continue
            }
            failing.remove(key(self, p))
            val keys = ring.keys()
            when (val outcome = PackCodec.open(bytes, keys, groupId, self, membership, expected = p.range)) {
                is PackOutcome.Opened -> {
                    // A kept pack read now: its seqs are accounted since the lift, its entries are not in the store.
                    membership = ingest(outcome.pack, membership, restore = p.range.last <= held).membership
                    lifted = maxOf(lifted, outcome.pack.to)
                    ownPacks = ownPacks + (p.name to KeyId(outcome.pack.epoch, GroupKey.fingerprint(keys.getValue(outcome.pack.epoch))))
                    // Its LOCATION entries leave the Disk like those of a pack sealed here (spec §8).
                    due = due.due(p.name, outcome.pack.entries.mapNotNull { runCatching { DecodedEntry.decode(it) }.getOrNull() })
                }
                // The signature is checked before the key: the range is ours even when we cannot read it. Kept as it is.
                is PackOutcome.KeyUnknown -> {
                    lifted = maxOf(lifted, p.range.last)
                    ownPacks = ownPacks + (p.name to KeyId.kept(outcome.epoch))
                }
                is PackOutcome.KeyMismatch -> {
                    lifted = maxOf(lifted, p.range.last)
                    ownPacks = ownPacks + (p.name to KeyId.kept(outcome.epoch, GroupKey.fingerprint(keys.getValue(outcome.epoch))))
                }
                is PackOutcome.UnknownAuthor, is PackOutcome.Rejected -> {
                    log("own folder: ${p.name} is not ours, ignored")
                    seen[key(self, p)] = REJECTED
                }
            }
        }
        val next = cursor.copy(seen = seen, ownPacks = ownPacks, locationDue = due, failing = failing)
        if (lifted > held) {
            store.completeAuthor(self, lifted)
            log("own log found on the Disk up to $lifted, held $held: those seqs are not reused (A-4)")
        }
        if (next == cursor) return cursor to membership
        // Only own packs found above what we hold count as uploaded (review Critical): a marker, a retried kept pack or a
        // due change leaves the upload cursor alone, or entries signed here and not uploaded yet would be skipped.
        val uploaded = if (lifted > held) maxOf(cursor.uploadedUpTo, lifted) else cursor.uploadedUpTo
        return next.copy(uploadedUpTo = uploaded).also { cursors.save(it) } to membership
    }

    /** The ranges of [cursor]'s kept own packs (A-4): never sealed by us. */
    private fun keptRanges(cursor: CloudCursor): List<LongRange> = keptPacks(cursor).keys.mapNotNull { CloudPaths.parsePack(it) }

    /**
     * [cursor]'s kept own packs (A-4) that hold seqs we lack. One whose range our opened own packs cover (a compaction cut
     * short left an old copy next to the new one) is no hole: it is replaced and deleted like any other own pack.
     */
    private fun keptPacks(cursor: CloudCursor): Map<String, KeyId> {
        val opened = cursor.ownPacks.filterValues { !it.kept }.keys.mapNotNull { CloudPaths.parsePack(it) }.sortedBy { it.first }
        val merged = mutableListOf<LongRange>()
        for (r in opened) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.size - 1] = last.first..maxOf(last.last, r.last) else merged += r
        }
        return cursor.ownPacks.filter { (name, id) ->
            val r = CloudPaths.parsePack(name)
            id.kept && (r == null || merged.none { r.first >= it.first && r.last <= it.last })
        }
    }

    /** [chunks] of [from]..[upTo] minus the kept ranges: a range whose entries we do not hold is never sealed. */
    private suspend fun ownChunks(from: Long, upTo: Long, cursor: CloudCursor): List<Pair<LongRange, List<DecodedEntry>>> {
        val out = mutableListOf<Pair<LongRange, List<DecodedEntry>>>()
        var next = from
        for (k in keptRanges(cursor).sortedBy { it.first }) {
            if (k.last < next) continue
            if (k.first > upTo) break
            if (k.first > next) out += chunks(next, k.first - 1)
            next = k.last + 1
        }
        if (next <= upTo) out += chunks(next, upTo)
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
        val full = force || start.ownPacks.values.any { !it.kept && it != current }
        if (!full && now - start.lastCompactionMs < COMPACTION_INTERVAL_MS) return start
        val done = start.copy(lastCompactionMs = now)
        val dir = CloudPaths.deviceDir(groupId, self)
        val kept = keptPacks(start)
        val keptTop = keptRanges(start).maxOfOrNull { it.last } ?: 0L
        // Kept packs (A-4) are never replaced; a daily merge stays above them.
        val existing = try {
            disk.list(dir).mapNotNull { i -> if (i.isDir) null else CloudPaths.parsePack(i.name)?.let { PackItem(i.name, "", it, i.size) } }
        } catch (e: DiskException.NotFound) {
            emptyList()
        }.filter { it.name !in kept }
        if (start.uploadedUpTo == 0L) return done.also { cursors.save(it) }
        val replaced = if (full) {
            existing
        } else {
            existing.filter { it.range.last <= start.uploadedUpTo && it.range.first > keptTop }
                .sortedWith(compareBy({ it.range.first }, { it.range.last }))
                .takeLastWhile { it.size < smallPackBytes }
        }
        if (!full && replaced.size < 2) return done.also { cursors.save(it) }
        val from = if (full) 1L else replaced.first().range.first
        val replacedNames = replaced.map { it.name }.toSet()
        val groupKey = ring.keys().getValue(epoch)
        val planned = ownChunks(from, start.uploadedUpTo, start).map { (r, entries) -> CloudPaths.packName(r.first, r.last) to (r to entries) }
        val written = mutableMapOf<String, KeyId>()
        val writtenDue = mutableMapOf<String, List<DecodedEntry>>()
        try {
            for ((name, chunk) in planned) {
                // A merge keeps a tail file whose range comes out the same, but only one recorded with the current key: an
                // unrecorded file of that name is written again and recorded, or it would stay out of ownPacks for good.
                if (!full && name in replacedNames && start.ownPacks[name] == current) continue
                val (range, entries) = chunk
                uploadPack(name, PackCodec.seal(keys, groupId, entries, range.first, range.last, epoch, groupKey, now, keyUpdates(epoch)))
                written[name] = current
                writtenDue[name] = entries
            }
        } catch (e: DiskException.StorageFull) {
            // Nothing is deleted and the cursor stays: compaction is retried at the next run, reading still goes on.
            storageFull = true
            return start
        }
        val obsolete = replacedNames - planned.map { it.first }.toSet()
        // After a full re-seal every own pack on the Disk is one just written.
        val ownPacks = if (full) kept + written else start.ownPacks + written - obsolete
        var due = start.locationDue.filterKeys { it in ownPacks }
        for ((name, entries) in writtenDue) due = due.due(name, entries)
        val compacted = done.copy(ownPacks = ownPacks, locationDue = due)
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
     * yields null, unless its bytes do not match the listing ([damaged]): then it is skipped for this run (Task 14). Auth
     * and quota trouble propagates (the run is retried), as does cancellation.
     */
    private inline fun <T : Any> guarded(
        seen: MutableMap<String, Int>,
        deferred: MutableMap<String, DeferredPack>,
        failing: MutableMap<String, PackFailure>,
        device: DeviceId,
        p: PackItem,
        bytes: ByteArray,
        now: Long,
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
        if (damaged(p, bytes)) {
            skip(failing, key(device, p), "${CloudPaths.deviceDir(groupId, device)}/${p.name}", null, now)
        } else {
            log("pack ${device.hex.take(8)}/${p.name} skipped: $e")
            key(device, p).let { seen[it] = REJECTED; deferred.remove(it) }
        }
        null
    }

    /**
     * Task 14: a failure of one file the Disk would not hand out (a storage 5xx, a dropped read, an unexpected answer), not
     * of the run. A refused token, a full Disk and a rate limit still end the whole run (spec §11).
     */
    private fun perFile(e: IOException) =
        e !is DiskException.Unauthorized && e !is DiskException.StorageFull && e !is DiskException.RateLimited

    /**
     * A pack that failed waits until its [PackFailure.nextAtMs]. One whose wait is longer than any backoff could give (the
     * clock went back) is due.
     */
    private fun backedOff(f: PackFailure?, now: Long) =
        f != null && now < f.nextAtMs && f.nextAtMs - now <= (MAX_RETRY_MS * (1 + RETRY_JITTER)).toLong()

    /** A read that ended without an HTTP status: the network went away, not the file. Never counted against the pack. */
    private fun dropped(e: Exception) = e is IOException && e !is DiskException

    /**
     * One line per skipped pack: its app-folder path and the reason. [e] null: bytes that do not match the listing. A network
     * drop ([dropped]) leaves the pack's count alone and stops the downloads of this run; anything else counts a failure and
     * sets the pack's next attempt [retryDelayMs] (with jitter) from [now].
     */
    private fun skip(failing: MutableMap<String, PackFailure>, k: String, path: String, e: Exception?, now: Long) {
        skipped++
        skippedKeys += k
        if (e != null && dropped(e)) {
            networkLost = true
            droppedRetryAtMs = now + FIRST_RETRY_MS
            log("pack $path skipped this run (${reasonOf(e)}): the network dropped, not counted")
            return
        }
        val n = (failing[k]?.count ?: 0) + 1
        val wait = (retryDelayMs(n) * jitter()).toLong()
        failing[k] = PackFailure(n, now + wait)
        log("pack $path skipped this run (${e?.let(::reasonOf) ?: DAMAGED}), failure $n, next try in ${wait / 60_000} min")
    }

    /**
     * The reason told for [e]: a Server error by its status and Yandex's error name (its path is told once, by [skip]),
     * another DiskException by its message (the path only, no token), anything else by its class only (a plain
     * IOException's message may carry a download link).
     */
    private fun reasonOf(e: Exception): String = when (e) {
        is DiskException.Server -> "server error ${e.code}" + (e.error?.let { ": $it" } ?: "")
        is DiskException -> e.message ?: e.javaClass.simpleName
        else -> e.javaClass.simpleName
    }

    /**
     * The earliest time a pack that failed may be tried again: a backoff still ahead (one already past whose pack was not
     * tried waits for something else, a key or a membership change, never for a follow-up run), or a network drop's retry.
     */
    private fun retryAt(cursor: CloudCursor): Long? {
        val now = clock()
        val ahead = cursor.failing.values.map { it.nextAtMs }.filter { it > now }
        return (ahead + listOfNotNull(droppedRetryAtMs)).minOrNull()
    }

    /**
     * Bytes that do not match the sha256 the listing gave (damaged on the way, or rewritten since the listing): asked only of
     * a pack that did not open, so a listing whose sha256 means something else can never stop a pack that opens.
     */
    private fun damaged(p: PackItem, bytes: ByteArray): Boolean =
        SHA256_HEX.matches(p.sha) && !Primitives.sha256(bytes).toHex().equals(p.sha, ignoreCase = true)

    private suspend fun download(listing: Map<DeviceId, List<PackItem>>, start: MembershipState, cursor: CloudCursor): Pair<Int, CloudCursor> {
        var membership = start
        val seen = cursor.seen.toMutableMap()
        val now = clock()
        // Task 14: packs the Disk would not hand out; a pack failing again and again waits (see [backedOff]).
        val failing = cursor.failing.toMutableMap()
        // Deferred packs (author, key generation or key unknown) are not final: kept apart from [seen], retried once the ring
        // holds a key that may open them.
        val deferred = cursor.deferred.toMutableMap()
        val vector = store.versionVector()
        val ringAtStart = ring.keys()
        // Spec §5: a covered pack's generation only decides which keys the ring keeps. Keys we write with or need for the
        // access are kept anyway, so it matters only while the ring holds another one.
        val startAccess = accessEpoch(start)
        val pinned = setOfNotNull(writeEpoch(start), startAccess)
        fun generationMatters(held: Map<Int, ByteArray>) = (held.keys - pinned).isNotEmpty()
        var pending = listing.filterKeys { it != self }
            .flatMap { (device, items) -> items.map { device to it } }
            .filter { (device, p) ->
                val k = key(device, p)
                val unread = seen[k] == COVERED_UNREAD && generationMatters(ringAtStart)
                (k !in seen || unread) && retryNow(deferred[k], ringAtStart, membership) && !backedOff(failing[k], now)
            }
            .sortedWith(compareBy({ it.first }, { it.second.range.first }))
        var received = 0
        var ingested = false
        val fetchedOk = HashSet<String>()
        var round = 0
        rounds@ while (!networkLost && pending.isNotEmpty() && round++ < MAX_ROUNDS) {
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
                // Task 14: a file the Disk would not hand out is skipped for this run, never the run itself.
                val path = "${CloudPaths.deviceDir(groupId, device)}/${p.name}"
                val bytes = try {
                    disk.download(path)
                } catch (e: DiskException.NotFound) {
                    continue // compacted away since the listing
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A RuntimeException of the HTTP layer is the file's trouble too, never a run retried forever.
                    if (e is IOException && !perFile(e)) throw e
                    skip(failing, k, path, e, now)
                    if (networkLost) break@rounds
                    continue
                }
                fetchedOk += k
                // Only the hostile part of a file (decoding, opening) is guarded: a file that fails there is marked
                // seen = -1. Local store errors below propagate and abort the run, leaving the pack unseen.
                val fetched = guarded(seen, deferred, failing, device, p, bytes, now) {
                    Fetched(bytes, PackCodec.epochOf(bytes), PackCodec.keyUpdates(bytes))
                } ?: continue
                if (!covered) membership = learnKeyUpdates(fetched.updates, membership)
                val held = ring.keys() // outside the guard: a ring failure is ours, not the file's
                val outcome = guarded(seen, deferred, failing, device, p, bytes, now) {
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
                    // Bytes damaged on the way look like a wrong key or a forgery: read again, never deferred or rejected.
                    is PackOutcome.KeyMismatch, is PackOutcome.Rejected -> if (damaged(p, fetched.bytes)) {
                        skip(failing, k, path, null, now)
                    } else if (outcome is PackOutcome.KeyMismatch) {
                        log("pack ${device.hex.take(8)}/${p.name} does not open under our key of generation ${outcome.epoch}")
                        deferred[k] = DeferredPack(outcome.epoch, GroupKey.fingerprint(held.getValue(outcome.epoch)))
                        next += device to p
                    } else {
                        log("rejected pack ${device.hex.take(8)}/${p.name}: ${(outcome as PackOutcome.Rejected).reason}")
                        seen[k] = REJECTED
                        deferred.remove(k)
                    }
                    is PackOutcome.Opened -> if (covered) {
                        // Nothing to ingest, but the file (signed, its range matching its name) pins its key generation.
                        seen[k] = outcome.pack.epoch
                        deferred.remove(k)
                    } else {
                        val r = ingest(outcome.pack, membership)
                        if (r.membership !== membership) absorbRotation(r.membership)
                        membership = r.membership
                        received += r.accepted
                        ingested = true
                        deferred.remove(k)
                        if (r.doubtful && failing.keys.any { it.startsWith("${device.hex}/") }) {
                            // Judged before an earlier pack of its author (skipped) was read: not seen, so it is read and
                            // judged again once that one is in, and the gap completion never makes a refusal final.
                            log("pack ${device.hex.take(8)}/${p.name} read ahead of a missing one of its author: read again later")
                        } else {
                            seen[k] = outcome.pack.epoch
                        }
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
        // Own files remembered by liftOwn (not ours, or too far) stay while they are listed.
        val allKeys = listed.map { it.second }.toSet() + listing[self].orEmpty().map { key(self, it) }
        val keptSeen = seen.filterKeys { it in allKeys }
        val keptDeferred = deferred.filterKeys { it in allKeys && it !in keptSeen }
        // A failure is forgotten at the first good download of the pack, or once the pack is gone or settled for good; a pack
        // still waiting (deferred, or covered and unread) keeps it, so its backoff holds.
        val resolved = fetchedOk - skippedKeys
        val keptFailing = failing.filterKeys { it in allKeys && it !in resolved && (keptSeen[it] ?: COVERED_UNREAD) == COVERED_UNREAD }
        // A pack read after a skipped one of its author could not complete the author's log; read now, the gap may be gone.
        if (ingested) completeHealedGaps(listing, keptSeen)
        // Spec §5: keep a previous generation only while files sealed with it remain (and the CloudAccess is sealed with it).
        // Only packs of members count: a folder planted by a token holder must not keep an old key alive. A covered pack
        // skipped unread has an unknown generation: it blocks dropping while the ring holds a key nothing pins.
        val members = membership.active.keys + membership.departed.keys
        val memberKeys = listed.filter { it.first in members }.map { it.second }.toSet()
        // Our own kept packs (A-4) are files sealed with their generation too: a key of it that arrives is kept to read them,
        // only while the file is still on the Disk (spec §5: while files sealed with it remain).
        val ownListed = listing[self].orEmpty().map { it.name }.toSet()
        val keptEpochs = keptPacks(cursor).filterKeys { it in ownListed }.values.map { it.epoch }
        // The owner's log is paged again only when this run may have changed it: nothing ingested keeps the start's answer.
        val access = if (received == 0 && membership === start) startAccess else accessEpoch(membership)
        val keep = setOfNotNull(writeEpoch(membership), access) + keptEpochs
        val unread = (ring.keys().keys - keep).isNotEmpty() && keptSeen.filterKeys { it in memberKeys }.values.any { it == COVERED_UNREAD }
        if (!unread && memberKeys.all { it in keptSeen || it in keptDeferred }) {
            val inUse = (keptSeen.filterKeys { it in memberKeys }.values + keptDeferred.filterKeys { it in memberKeys }.values.map { it.epoch })
                .filter { it >= 0 }.toSet()
            ring.retain(inUse + keep)
        }
        return received to cursor.copy(seen = keptSeen, deferred = keptDeferred, failing = keptFailing)
    }

    /**
     * Task 14: a pack opened while an earlier one of its author was skipped was ingested, but its signed range could not
     * complete the author (we did not hold everything below it). Walks each author's opened packs in order and completes
     * every signed range that now starts right above what we hold, as [ingest] would have.
     */
    private suspend fun completeHealedGaps(listing: Map<DeviceId, List<PackItem>>, seen: Map<String, Int>) {
        if (!policy.showOthers) return
        val vector = store.versionVector()
        for ((device, items) in listing) {
            if (device == self) continue
            var top = vector[device]
            for (p in items.sortedBy { it.range.first }) {
                if ((seen[key(device, p)] ?: continue) < 0) continue
                if (p.range.first - 1 <= top && p.range.last > top) {
                    store.completeAuthor(device, p.range.last)
                    top = p.range.last
                }
            }
        }
    }

    /**
     * The key generation of the current owner's newest CloudAccess: readers need it to open the access token. Pages through
     * the owner's log only (a parent's: small), never the whole database.
     */
    private suspend fun accessEpoch(membership: MembershipState): Int? =
        CloudAccessView.newestAccessEntry(store, membership)?.body?.cloud_access?.key_epoch

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

    /**
     * [accepted]: entries stored. [doubtful]: the pack was read while we lack seqs of its author below it (an earlier pack
     * skipped, Task 14) and some entry of it was refused (Invalid, Skipped), maybe for lack of what that earlier pack holds.
     */
    private class Ingested(val membership: MembershipState, val accepted: Int, val doubtful: Boolean)

    /** [restore]: our own kept pack read late, whose seqs were accounted at the lift (A-4) while its entries are missing. */
    private suspend fun ingest(pack: OpenedPack, start: MembershipState, restore: Boolean = false): Ingested {
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
        var refused = false
        fun noteLiveSet(e: DecodedEntry) {
            val s = LiveSet.of(e) ?: return
            if (liveSet == null || s.upTo > liveSet!!.upTo) liveSet = s
        }
        for (e in entries) {
            when (val v = validator.check(e, alreadyAccounted = !restore && store.isAccounted(e.author, e.seq))) {
                Verdict.Accept -> {
                    accepted += e
                    noteLiveSet(e)
                }
                Verdict.Expired -> expired += e.seq
                Verdict.Duplicate -> noteLiveSet(e)
                Verdict.Unwanted -> Unit
                is Verdict.Invalid -> log("invalid ${author.hex.take(8)}#${e.seq} in pack: ${v.reason}").also { refused = true }
                is Verdict.Skipped -> log("skipped ${author.hex.take(8)}#${e.seq} in pack: ${v.reason}").also { refused = true }
            }
        }
        store.applyBatch(accepted, if (expired.isEmpty()) emptyMap() else mapOf(author to expired))
        liveSet?.let { store.accountLiveSet(author, it) }
        // The author signed that this range is complete; it closes our gap only if we hold everything below it.
        val inOrder = store.versionVector()[author] >= pack.from - 1
        if (policy.showOthers && inOrder) store.completeAuthor(author, pack.to)
        return Ingested(membership, accepted.size, doubtful = refused && !inOrder)
    }

    private fun key(device: DeviceId, p: PackItem) = "${device.hex}/${p.name}/${p.sha}"

    /**
     * What tells two versions of one pack apart: the Disk's sha256, else its md5, else its modified time. A listing with
     * none of them (never seen from Yandex) gets a key of this run: such a pack is read again on every run, never trusted
     * by its size.
     */
    private fun versionOf(i: DiskItem): String =
        i.sha256 ?: i.md5?.let { "md5:$it" } ?: i.modified?.let { "mod:$it" } ?: runVersion

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

        /** The fix times one own pack's LOCATION entries span at most (spec §8: each leaves the Disk soon after 48 h). */
        const val LOCATION_SPAN_MS = 60L * 60 * 1000

        /** After its newest LOCATION is past retention, a pack is re-sealed this much later (a clock step, a late run). */
        const val LOCATION_MARGIN_MS = 60L * 60 * 1000

        /** Task 14: the first wait of a pack the Disk would not hand out; it doubles per failure up to [MAX_RETRY_MS]. */
        const val FIRST_RETRY_MS = 5L * 60 * 1000
        const val MAX_RETRY_MS = 60L * 60 * 1000

        /** Each wait is spread by up to this fraction either way, so the packs of a bad hour do not all come due at once. */
        const val RETRY_JITTER = 0.1

        /** The wait after [failures] failures in a row, before jitter: 5, 10, 20, 40, then 60 minutes (like ImagePullBackOff). */
        fun retryDelayMs(failures: Int): Long =
            if (failures >= 5) MAX_RETRY_MS else minOf(FIRST_RETRY_MS shl (maxOf(failures, 1) - 1), MAX_RETRY_MS)

        private const val DAMAGED = "bytes do not match the listing"
        private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

        /** [CloudCursor.seen] value of a pack that did not open and never will. */
        const val REJECTED = -1

        /** [CloudCursor.seen] value of a pack skipped as covered by its name: its generation is unknown, read when it matters. */
        const val COVERED_UNREAD = -2

        /** [CloudCursor.seen] value of an own-folder name too far above our log (A-4): ignored, told once. */
        const val FAR = -3

        private const val STORAGE_ID_BYTES = 16

        /** Daily compaction merges the newest own packs below this size; bigger ones stay as they are. */
        const val SMALL_PACK_BYTES = 256 shl 10

        /** Runs of this process, for [runVersion]: every instance's run gets its own. */
        private val RUNS = AtomicLong()
    }
}
