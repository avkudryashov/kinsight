package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.addMember
import app.kinsight.core.model.leaveGroupEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.sync.CategoryOverrideRules
import app.kinsight.core.sync.categoryOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CategoryOverridesTest {
    private val momKeys = DeviceKeys.generate()
    private val dadKeys = DeviceKeys.generate()
    private val granKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val gid = genesis.groupId
    private val mom = EntryAuthor(gid, momKeys, lastSeq = 1, lastLamport = 1)
    private val dad = EntryAuthor(gid, dadKeys, lastSeq = 0, lastLamport = 10)
    private val gran = EntryAuthor(gid, granKeys, lastSeq = 0, lastLamport = 10)
    private val ops = mutableListOf(
        genesis.entry,
        mom.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad", DeviceRole.PARENT),
        mom.addMember(granKeys.sign.publicKey, granKeys.dh.publicKey, "Gran", DeviceRole.PARENT),
    )

    private fun membership(): MembershipState = Membership.compute(gid, ops)!!

    private fun wire(c: AppCategory?) = CategoryOverrides.wire(c)

    @Test
    fun wireValuesMatchTheValidatorsRange() {
        assertEquals(CategoryOverrideRules.MAX_CATEGORY, AppCategory.entries.size)
        assertEquals(0, wire(null))
        assertEquals(1, wire(AppCategory.GAMES))
        assertEquals(CategoryOverrideRules.MAX_CATEGORY, wire(AppCategory.OTHER))
        AppCategory.entries.forEach { assertEquals(it, CategoryOverrides.fromWire(wire(it))) }
        assertNull(CategoryOverrides.fromWire(0))
        assertNull(CategoryOverrides.fromWire(CategoryOverrideRules.MAX_CATEGORY + 1))
    }

    @Test
    fun theHighestLamportWinsThenTheAuthor() {
        val a = mom.categoryOverride("com.game", wire(AppCategory.GAMES)) // lamport 4
        val b = EntryAuthor(gid, dadKeys, lastSeq = 0).categoryOverride("com.game", wire(AppCategory.PRODUCTIVITY)) // lamport 1
        assertEquals(mapOf("com.game" to AppCategory.GAMES), CategoryOverrides.effective(listOf(b, a), membership()))
        val later = EntryAuthor(gid, dadKeys, lastSeq = 1, lastLamport = 10).categoryOverride("com.game", wire(AppCategory.VIDEO))
        val eff = CategoryOverrides.effectiveWithAuthors(listOf(a, b, later), membership())
        assertEquals(EffectiveCategory(AppCategory.VIDEO, dadKeys.deviceId), eff["com.game"])

        // Equal Lamport: the larger author hex wins, whichever order the entries come in.
        val x = EntryAuthor(gid, momKeys, lastSeq = 5, lastLamport = 20).categoryOverride("com.tie", wire(AppCategory.NEWS))
        val y = EntryAuthor(gid, dadKeys, lastSeq = 5, lastLamport = 20).categoryOverride("com.tie", wire(AppCategory.MAPS))
        val winner = if (momKeys.deviceId.hex > dadKeys.deviceId.hex) AppCategory.NEWS else AppCategory.MAPS
        assertEquals(winner, CategoryOverrides.effective(listOf(x, y), membership())["com.tie"])
        assertEquals(winner, CategoryOverrides.effective(listOf(y, x), membership())["com.tie"])
    }

    @Test
    fun aClearedWinnerMeansNoOverride() {
        val set = EntryAuthor(gid, dadKeys, lastSeq = 0).categoryOverride("com.game", wire(AppCategory.GAMES)) // lamport 1
        val cleared = mom.categoryOverride("com.game", 0) // lamport 4
        assertEquals(emptyMap(), CategoryOverrides.effective(listOf(set, cleared), membership()))
        assertNull(CategoryOverrides.effectiveWithAuthors(listOf(set, cleared), membership())["com.game"])
    }

    @Test
    fun departedAndRemovedParentsAndChildrenDoNotCount() {
        val dadSays = dad.categoryOverride("com.a", wire(AppCategory.GAMES))
        val granSays = gran.categoryOverride("com.b", wire(AppCategory.VIDEO))
        val momOlder = mom.categoryOverride("com.a", wire(AppCategory.SOCIAL))
        val before = listOf(momOlder, dadSays, granSays)
        assertEquals(mapOf("com.a" to AppCategory.GAMES, "com.b" to AppCategory.VIDEO), CategoryOverrides.effective(before, membership()))

        ops += dad.leaveGroupEntry()
        ops += mom.removeMemberEntry(granKeys.deviceId, gran.lastSeq)
        // Dad left, Gran was removed: Mom's older choice applies again, Gran's is gone.
        assertEquals(mapOf("com.a" to AppCategory.SOCIAL), CategoryOverrides.effective(before, membership()))

        // A child author (never valid on receipt) and an unknown author are ignored as well.
        val kidKeys = DeviceKeys.generate()
        ops += mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)
        val kid = EntryAuthor(gid, kidKeys, lastSeq = 0, lastLamport = 100).categoryOverride("com.a", wire(AppCategory.NEWS))
        val stranger = EntryAuthor(gid, DeviceKeys.generate(), lastSeq = 0, lastLamport = 100).categoryOverride("com.a", wire(AppCategory.NEWS))
        assertEquals(mapOf("com.a" to AppCategory.SOCIAL), CategoryOverrides.effective(before + kid + stranger, membership()))
    }

    @Test
    fun otherKindsAndUnknownCategoriesAreIgnored() {
        val bad: DecodedEntry = mom.categoryOverride("com.a", CategoryOverrideRules.MAX_CATEGORY + 5)
        val usage = mom.create { it }
        assertEquals(emptyMap(), CategoryOverrides.effective(listOf(bad, usage), membership()))
    }
}
