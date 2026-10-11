package app.kinsight.core.data

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.categoryOverride
import app.kinsight.core.transport.ble.GroupConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stage 5c: category overrides are signed own-log entries shared by the parents (spec §4.4). */
class CategorySharingTest {
    private val mom = TestDevice()
    private val devices = mutableListOf(mom)

    @AfterTest
    fun cleanUp() = devices.forEach { it.close() }

    private suspend fun overrideEntries(d: TestDevice) = d.store().categoryOverrides()

    private suspend fun UsageRepository.overridesWhere(p: (Map<String, AppCategory>) -> Boolean) =
        withTimeout(10_000) { categoryOverrides.first(p) }

    @Test
    fun setCategoryAppendsASignedEntryAndTheMapFollowsIt() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.usage.setCategory("com.game", AppCategory.GAMES)
        mom.usage.overridesWhere { it == mapOf("com.game" to AppCategory.GAMES) }
        val self = mom.keys.deviceId
        assertEquals(self, withTimeout(10_000) { mom.usage.categoryAuthor("com.game").first { it != null } })
        assertEquals(emptyMap(), mom.prefs.categoryOverrides.first(), "never written to the local prefs")

        mom.usage.setCategory("com.game", AppCategory.VIDEO)
        mom.usage.overridesWhere { it == mapOf("com.game" to AppCategory.VIDEO) }
        assertEquals(1, overrideEntries(mom).size, "the newer entry supersedes the older")

        mom.usage.setCategory("com.game", null)
        mom.usage.overridesWhere { it.isEmpty() }
        assertNull(withTimeout(10_000) { mom.usage.categoryAuthor("com.game").first { it == null } })
        assertEquals(0, overrideEntries(mom).single().body.category_override!!.category)

        // Choosing what is already effective, or clearing what is not set, signs nothing.
        val seq = mom.store().authorState(self).lastSeq
        mom.usage.setCategory("com.game", null)
        mom.usage.setCategory("com.other", null)
        assertEquals(seq, mom.store().authorState(self).lastSeq)
    }

    @Test
    fun invalidPackagesAreRefusedNotSigned() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val seq = mom.store().authorState(mom.keys.deviceId).lastSeq
        for (bad in listOf("", Categories.REMAINDER_PACKAGE, "a/b", "a|b", "a b", "é".repeat(128))) {
            mom.usage.setCategory(bad, AppCategory.GAMES)
        }
        assertEquals(seq, mom.store().authorState(mom.keys.deviceId).lastSeq)
        assertTrue(overrideEntries(mom).isEmpty())
    }

    @Test
    fun anotherParentsChoiceShowsWithItsAuthor() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val dadKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad", DeviceRole.PARENT)) }
        val dad = EntryAuthor(mom.groups.read()!!.groupId, dadKeys, lastSeq = 0, lastLamport = 49) // his entry: Lamport 50
        val dads = dad.categoryOverride("com.game", CategoryOverrides.wire(AppCategory.SOCIAL))
        assertEquals(50L, dads.lamport)
        mom.store().applyBatch(listOf(dads))
        mom.usage.overridesWhere { it == mapOf("com.game" to AppCategory.SOCIAL) }
        assertEquals(dadKeys.deviceId, withTimeout(10_000) { mom.usage.categoryAuthor("com.game").first { it != null } })
        assertEquals("Dad", withTimeout(10_000) { mom.usage.categoryChangedBy("com.game").first { it != null } })

        // Mom changes it back: her entry orders after Dad's (Lamport), so it wins.
        mom.usage.setCategory("com.game", AppCategory.GAMES)
        mom.usage.overridesWhere { it == mapOf("com.game" to AppCategory.GAMES) }
        assertEquals(mom.keys.deviceId, withTimeout(10_000) { mom.usage.categoryAuthor("com.game").first { it == mom.keys.deviceId } })
        assertNull(withTimeout(10_000) { mom.usage.categoryChangedBy("com.game").first { it == null } }, "her own choice names nobody")
        val hers = overrideEntries(mom).single { it.author == mom.keys.deviceId }
        assertEquals(51L, hers.lamport, "orders right after Dad's 50")
        assertEquals(51L, mom.store().authorState(mom.keys.deviceId).lastLamport)
        mom.usage.setCategory("com.next", AppCategory.NEWS)
        val next = overrideEntries(mom).single { it.author == mom.keys.deviceId && it.body.category_override!!.package_name == "com.next" }
        assertTrue(next.lamport >= 52L, "her next own entry follows: ${next.lamport}")
    }

    @Test
    fun aChildsPhoneRefusesToSign() = runBlocking<Unit> {
        val kid = TestDevice().also { devices += it }
        val genesis = GroupGenesis.create(mom.keys, "Mom")
        val momAuthor = EntryAuthor(genesis.groupId, mom.keys, lastSeq = 1, lastLamport = 1)
        val add = momAuthor.addMember(kid.keys.sign.publicKey, kid.keys.dh.publicKey, "Kid", DeviceRole.CHILD)
        kid.groups.write(GroupConfig(genesis.groupId, ByteArray(32)))
        kid.store().applyBatch(listOf(genesis.entry, add))
        kid.prefs.setCategory("com.game", AppCategory.GAMES) // a stray local choice: not published, not cleared

        kid.usage.setCategory("com.game", AppCategory.GAMES)
        kid.usage.migrateLocalCategories()
        assertTrue(overrideEntries(kid).isEmpty())
        assertEquals(0L, kid.store().authorState(kid.keys.deviceId).lastSeq)
        assertEquals(mapOf("com.game" to AppCategory.GAMES), kid.prefs.categoryOverrides.first())
    }

    @Test
    fun withoutAGroupNothingIsSignedAndPrefsStay() = runBlocking<Unit> {
        mom.prefs.setCategory("com.game", AppCategory.GAMES)
        mom.usage.setCategory("com.game", AppCategory.VIDEO)
        mom.usage.migrateLocalCategories()
        assertEquals(mapOf("com.game" to AppCategory.GAMES), mom.prefs.categoryOverrides.first())
    }

    @Test
    fun migrationPublishesLocalChoicesOnceThenClearsPrefs() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val dadKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad", DeviceRole.PARENT)) }
        val dad = EntryAuthor(mom.groups.read()!!.groupId, dadKeys, lastSeq = 0, lastLamport = 50)
        // Dad already set two apps (one as Mom did, one differently) and deliberately cleared a third: a parent's shared
        // decision is never overridden by Mom's old local choice, so only the app no parent has touched is published.
        mom.store().applyBatch(
            listOf(
                dad.categoryOverride("com.same", CategoryOverrides.wire(AppCategory.NEWS)),
                dad.categoryOverride("com.diff", CategoryOverrides.wire(AppCategory.VIDEO)),
                dad.categoryOverride("com.cleared", 0),
            ),
        )
        mom.prefs.setCategory("com.game", AppCategory.GAMES)
        mom.prefs.setCategory("com.diff", AppCategory.MAPS)
        mom.prefs.setCategory("com.cleared", AppCategory.SOCIAL)
        mom.prefs.setCategory("com.same", AppCategory.NEWS)
        mom.prefs.setCategory("~other", AppCategory.VIDEO) // never valid: dropped, not signed

        mom.usage.migrateLocalCategories()
        val mine = overrideEntries(mom).filter { it.author == mom.keys.deviceId }
        assertEquals(listOf("com.game"), mine.sortedBy { it.seq }.map { it.body.category_override!!.package_name })
        assertEquals(emptyMap(), mom.prefs.categoryOverrides.first())
        mom.usage.overridesWhere {
            it == mapOf("com.game" to AppCategory.GAMES, "com.same" to AppCategory.NEWS, "com.diff" to AppCategory.VIDEO)
        }

        val seq = mom.store().authorState(mom.keys.deviceId).lastSeq
        mom.usage.migrateLocalCategories()
        assertEquals(seq, mom.store().authorState(mom.keys.deviceId).lastSeq, "a second run signs nothing")
    }

    @Test
    fun aCrashBeforePrefsAreClearedNeitherDuplicatesNorLoses() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.prefs.setCategory("com.game", AppCategory.GAMES)
        // The previous run appended the entry and died before clearing prefs.
        mom.pipeline.appendOwn { a -> listOf(a.categoryOverride("com.game", CategoryOverrides.wire(AppCategory.GAMES))) }
        // Since then Dad chose another category: the rerun must not undo his later choice.
        val dadKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad", DeviceRole.PARENT)) }
        val dad = EntryAuthor(mom.groups.read()!!.groupId, dadKeys, lastSeq = 0, lastLamport = 50)
        mom.store().applyBatch(listOf(dad.categoryOverride("com.game", CategoryOverrides.wire(AppCategory.VIDEO))))
        val seq = mom.store().authorState(mom.keys.deviceId).lastSeq

        mom.usage.migrateLocalCategories()
        assertEquals(seq, mom.store().authorState(mom.keys.deviceId).lastSeq, "own entry already says so: not signed again")
        assertEquals(emptyMap(), mom.prefs.categoryOverrides.first())
        mom.usage.overridesWhere { it == mapOf("com.game" to AppCategory.VIDEO) }
    }

    @Test
    fun aFailedAppendKeepsPrefs() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.prefs.setCategory("com.game", AppCategory.GAMES)
        val failing = UsageRepository(mom.snapshots, mom.prefs, appendOwn = { throw IllegalStateException("disk full") })
        runCatching { failing.migrateLocalCategories() }
        assertEquals(mapOf("com.game" to AppCategory.GAMES), mom.prefs.categoryOverrides.first())
        assertTrue(overrideEntries(mom).none { it.kind == EntryKind.CATEGORY_OVERRIDE })

        mom.usage.migrateLocalCategories() // the next start retries
        assertEquals(emptyMap(), mom.prefs.categoryOverrides.first())
        mom.usage.overridesWhere { it == mapOf("com.game" to AppCategory.GAMES) }
    }

    @Test
    fun aChoiceMadeBeforeMigrationIsNotUndoneByIt() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.prefs.setCategory("com.game", AppCategory.GAMES)
        mom.usage.setCategory("com.game", AppCategory.VIDEO) // migrates first, then signs the new choice
        mom.usage.migrateLocalCategories()
        mom.usage.overridesWhere { it == mapOf("com.game" to AppCategory.VIDEO) }
        assertEquals(emptyMap(), mom.prefs.categoryOverrides.first())
    }
}
