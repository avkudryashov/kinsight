package app.kinsight.core.database

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.addMember
import app.kinsight.core.model.locationEntry
import app.kinsight.core.model.safeZoneEntry
import app.kinsight.core.model.proto.Location
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.proto.UsageSummary
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.model.storageHandoverEntry
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.categoryOverride
import app.kinsight.core.sync.detail
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomEntryStoreTest {
    private val dir = Files.createTempDirectory("kinsight-store").toFile()
    private val path = File(dir, "store.db").absolutePath
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "a")
    private val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1)
    private val opened = mutableListOf<KinsightDatabase>()

    private fun open(): RoomEntryStore {
        val db = KinsightDatabaseFactory.open(HostContext(), path)
        opened += db
        return RoomEntryStore(db, genesis.groupId)
    }

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    @Test
    fun failedBatchLeavesNoTrace() = runTest {
        val store = open()
        store.applyBatch(listOf(genesis.entry))
        val e2 = author.summary("2026-10-08", 1)
        val e3 = author.summary("2026-10-09", 1)
        store.faultInjector = { i -> if (i == 1) throw IllegalStateException("injected") }
        val r = runCatching { store.applyBatch(listOf(e2, e3), seen = mapOf(keys.deviceId to setOf(9L))) }
        store.faultInjector = null
        assertTrue(r.isFailure)
        assertEquals(1L, store.versionVector()[keys.deviceId])
        assertFalse(store.isAccounted(keys.deviceId, 2))
        assertFalse(store.isAccounted(keys.deviceId, 9))
        assertEquals(listOf(genesis.entry), store.liveEntries())
        // The same batch goes through once the fault is gone.
        store.applyBatch(listOf(e2, e3))
        assertEquals(3L, store.versionVector()[keys.deviceId])
    }

    @Test
    fun faultAfterSeenAccountingRollsBackTheSeenSeqs() = runTest {
        val store = open()
        store.applyBatch(listOf(genesis.entry))
        val e2 = author.summary("2026-10-08", 1)
        store.faultInjector = { i -> if (i < 0) throw IllegalStateException("injected after seen") }
        val r = runCatching { store.applyBatch(listOf(e2), seen = mapOf(keys.deviceId to setOf(9L))) }
        store.faultInjector = null
        assertTrue(r.isFailure)
        assertFalse(store.isAccounted(keys.deviceId, 9))
        assertFalse(store.isAccounted(keys.deviceId, 2))
        assertEquals(1L, store.versionVector()[keys.deviceId])
        assertEquals(listOf(genesis.entry), store.liveEntries())
    }

    @Test
    fun reopenKeepsEntriesVectorAndSparseAccounting() = runTest {
        val first = open()
        val e2 = author.summary("2026-10-07", 1)
        val e3 = author.summary("2026-10-08", 1)
        first.applyBatch(listOf(genesis.entry, e3), seen = mapOf(keys.deviceId to setOf(5L)))
        opened.removeLast().close()

        val store = open()
        assertEquals(1L, store.versionVector()[keys.deviceId])
        assertTrue(store.isAccounted(keys.deviceId, 3))
        assertTrue(store.isAccounted(keys.deviceId, 5))
        assertFalse(store.isAccounted(keys.deviceId, 4))
        assertEquals(listOf(genesis.entry, e3), store.liveEntries())
        store.applyBatch(listOf(e2))
        assertEquals(3L, store.versionVector()[keys.deviceId])
        store.completeAuthor(keys.deviceId, 4)
        assertEquals(5L, store.versionVector()[keys.deviceId])
    }

    @Test
    fun detailsReadsOneAuthorsDatesInRange() = runTest {
        val store = open()
        val other = EntryAuthor(genesis.groupId, DeviceKeys.generate(), lastSeq = 0)
        store.applyBatch(
            listOf(
                genesis.entry,
                author.detail("2026-09-30"), author.detail("2026-10-01"), author.summary("2026-10-02", 1),
                author.detail("2026-10-07"), author.detail("2026-10-08"), other.detail("2026-10-03"),
            ),
        )
        val got = store.details(keys.deviceId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7))
        assertEquals(listOf("2026-10-01", "2026-10-07"), got.map { it.body.usage_detail!!.local_date })
    }

    @Test
    fun detailsReturnsAMalformedDateInRangeAndOnlyTheNewestVersion() = runTest {
        val store = open()
        val old = author.detail("2026-10-03")
        val bad = author.detail("2026-10-01x") // sorts inside the key range; the read model drops it (usageDate null)
        val newer = author.detail("2026-10-03")
        store.applyBatch(listOf(genesis.entry, old, bad, newer))
        val got = store.details(keys.deviceId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7))
        assertEquals(listOf("2026-10-01x", "2026-10-03"), got.map { it.body.usage_detail!!.local_date })
        assertEquals(null, got[0].usageDate)
        assertEquals(newer.seq, got[1].seq)
    }

    @Test
    fun categoryOverridesSupersedePerAuthorAndPackageAndNeverPrune() = runTest {
        val store = open()
        val other = EntryAuthor(genesis.groupId, DeviceKeys.generate(), lastSeq = 0)
        val first = author.categoryOverride("com.example.game", 1)
        val catalog = author.create { it.copy(app_catalog = AppCatalog(package_name = "com.example.game", label = "Game")) }
        val theirs = other.categoryOverride("com.example.game", 3)
        val second = author.categoryOverride("com.example.game", 0) // cleared: still the author's live entry
        val otherPkg = author.categoryOverride("com.example.video", 3)
        store.applyBatch(listOf(genesis.entry, first, catalog, theirs, second, otherPkg))
        // An older copy arriving late never replaces the newer one.
        store.applyBatch(listOf(first))

        val expected = setOf(second, otherPkg, theirs)
        assertEquals(expected, store.categoryOverrides().toSet())
        assertTrue(first !in store.liveEntries())
        assertTrue(catalog in store.catalog())

        store.prune(SyncPolicy(keys.deviceId, detailRetentionDays = 0, summaryRetentionDays = 0, today = { LocalDate.of(2100, 1, 1) }))
        opened.removeLast().close()
        assertEquals(expected, open().categoryOverrides().toSet())
    }

    @Test
    fun ownLiveSeqsListsOnlyThatAuthorsStoredSeqs() = runTest {
        val store = open()
        store.applyBatch(listOf(genesis.entry))
        author.summary("2026-10-08", 1) // 2, never stored (superseded at the author)
        val s3 = author.summary("2026-10-08", 2)
        val d4 = author.detail("2026-10-08")
        store.applyBatch(listOf(s3, d4))
        val other = DeviceKeys.generate()
        store.applyBatch(listOf(EntryAuthor(genesis.groupId, other, lastSeq = 0).summary("2026-10-08", 9)))
        assertEquals(listOf(1L, 3L, 4L), store.ownLiveSeqs(keys.deviceId))
    }

    @Test
    fun historyPruningKeepsTheNewestEventPerKindAndCatalogStillNamed() = runTest {
        val db = KinsightDatabaseFactory.open(HostContext(), path).also { opened += it }
        val store = RoomEntryStore(db, genesis.groupId)
        val day = 86_400_000L
        val now = 400 * day + 20 * 3_600_000L // 20:00 UTC
        val cutoffDay = 370 * day // retention below: 30 days, the oldest kept day starts at its midnight
        val old = now - 40 * day
        fun event(kind: StateEvent.Kind, to: Long) = author.create { it.copy(state_event = StateEvent(kind = kind, from_ms = to, to_ms = to)) }
        val consent = event(StateEvent.Kind.SHARING_RESUMED, old - 2 * day) // old, but the earliest SHARING_*: kept
        val oldPause = event(StateEvent.Kind.SHARING_PAUSED, old) // a newer pause follows: pruned
        val oldResume = event(StateEvent.Kind.SHARING_RESUMED, old) // neither earliest nor newest: pruned
        val oldBt = event(StateEvent.Kind.BLUETOOTH_OFF, old) // a newer one follows: pruned
        val newestPause = event(StateEvent.Kind.SHARING_PAUSED, old + day) // old, but the newest pause: kept
        val newestResume = event(StateEvent.Kind.SHARING_RESUMED, old + day) // the newest resume: kept
        val left = event(StateEvent.Kind.LEFT_GROUP, old - day) // membership: never pruned
        val oldestDayBt = event(StateEvent.Kind.BLUETOOTH_OFF, cutoffDay + 3_600_000L) // on the oldest kept day: kept
        val recentBt = event(StateEvent.Kind.BLUETOOTH_OFF, now - day)

        val kidKeys = DeviceKeys.generate()
        var t = old
        val kid = EntryAuthor(genesis.groupId, kidKeys, lastSeq = 0, clock = { t })
        val summary = kid.create {
            it.copy(usage_summary = UsageSummary(local_date = "2026-10-08", zone_id = "UTC", total_ms = 60_000, apps = listOf(AppTotal("com.example.maps", 60_000))))
        }
        fun catalog(pkg: String) = kid.create { it.copy(app_catalog = AppCatalog(package_name = pkg, label = pkg)) }
        val maps = catalog("com.example.maps") // old but still named by a summary: kept
        val chat = catalog("com.example.chat") // old and named by nothing: pruned
        t = now - day
        val game = catalog("com.example.game") // named by nothing but recent: kept
        store.applyBatch(
            listOf(genesis.entry, consent, oldPause, oldResume, oldBt, newestPause, newestResume, left, oldestDayBt, recentBt, summary, maps, chat, game),
        )
        db.usageDao().setCatalogHash(CatalogStateRow("com.example.chat", "h1"))
        db.usageDao().setCatalogHash(CatalogStateRow("com.example.maps", "h2"))

        store.pruneHistory(summaryRetentionDays = 30, nowMs = now, self = kidKeys.deviceId, zone = ZoneOffset.UTC)

        assertEquals(listOf(consent, newestPause, newestResume, left, oldestDayBt, recentBt), store.stateEvents())
        assertEquals(listOf(maps, game), store.catalog())
        assertEquals(null, db.usageDao().catalogHash("com.example.chat")) // described again if the app comes back
        assertEquals("h2", db.usageDao().catalogHash("com.example.maps"))
        assertTrue(store.isAccounted(keys.deviceId, oldPause.seq)) // a peer never sends it again
    }

    /** Another active author re-publishes only on a hash change, so only self's and departed authors' catalogs go. */
    @Test
    fun historyPruningKeepsTheCatalogOfOtherActiveAuthors() = runTest {
        val db = KinsightDatabaseFactory.open(HostContext(), path).also { opened += it }
        val store = RoomEntryStore(db, genesis.groupId)
        val day = 86_400_000L
        val now = 400 * day
        val otherKeys = DeviceKeys.generate()
        val goneKeys = DeviceKeys.generate()
        val addOther = author.addMember(otherKeys.sign.publicKey, otherKeys.dh.publicKey, "Other", DeviceRole.CHILD)
        val addGone = author.addMember(goneKeys.sign.publicKey, goneKeys.dh.publicKey, "Gone", DeviceRole.CHILD)
        fun oldCatalog(k: DeviceKeys) = EntryAuthor(genesis.groupId, k, lastSeq = 0, clock = { now - 100 * day })
            .create { it.copy(app_catalog = AppCatalog(package_name = "com.example.chat", label = "Chat")) }
        val otherChat = oldCatalog(otherKeys) // active author, old and unnamed: kept
        val goneChat = oldCatalog(goneKeys) // departed author, old and unnamed: pruned
        val removeGone = author.removeMemberEntry(goneKeys.deviceId, lastSeq = 1)
        store.applyBatch(listOf(genesis.entry, addOther, addGone, otherChat, goneChat, removeGone))

        store.pruneHistory(summaryRetentionDays = 30, nowMs = now, self = keys.deviceId, zone = ZoneOffset.UTC)

        assertEquals(listOf(otherChat), store.catalog())
    }

    @Test
    fun storageHandoversAreReadByKindAndTheNewestReplacesTheOlder() = runTest {
        val store = open()
        val dad = DeviceKeys.generate().deviceId
        val first = author.storageHandoverEntry(dad, 1)
        store.applyBatch(listOf(first))
        assertEquals(listOf(first), store.storageHandovers())
        val second = author.storageHandoverEntry(dad, 2)
        store.applyBatch(listOf(second))
        assertEquals(listOf(second), store.storageHandovers())
        assertEquals(11, EntryKinds.code(EntryKind.STORAGE_HANDOVER))
    }

    @Test
    fun locationsAndSafeZonesAreReadByKindAndAZoneEditReplacesItsVersion() = runTest {
        val store = open()
        val zoneId = ByteArray(16) { 3 }
        val first = author.safeZoneEntry(zoneId, "Home", 1, 2, 100, rank = 1)
        val point = author.locationEntry(1, 2, 10, 1_000, Location.Provider.GPS)
        store.applyBatch(listOf(first, point))
        assertEquals(listOf(first), store.safeZones())
        assertEquals(listOf(point), store.locations())
        val edit = author.safeZoneEntry(zoneId, "Home", 1, 2, 200, rank = 2)
        store.applyBatch(listOf(edit))
        assertEquals(listOf(edit), store.safeZones())
        assertEquals(9, EntryKinds.code(EntryKind.LOCATION))
        assertEquals(10, EntryKinds.code(EntryKind.SAFE_ZONE))
    }
}
