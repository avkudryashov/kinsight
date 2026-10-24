package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.appRuleEntry
import app.kinsight.core.model.proto.AppRule
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.AppRuleRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppRulesTest {
    private val momKeys = DeviceKeys.generate()
    private val dadKeys = DeviceKeys.generate()
    private val kidKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val gid = genesis.groupId
    private val mom = EntryAuthor(gid, momKeys, lastSeq = 1, lastLamport = 1)
    private val dad = EntryAuthor(gid, dadKeys, lastSeq = 0, lastLamport = 10)
    private val kid = EntryAuthor(gid, kidKeys, lastSeq = 0, lastLamport = 10)
    private val ops = listOf(
        genesis.entry,
        mom.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad", DeviceRole.PARENT),
        mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD),
    )
    private val child = kidKeys.deviceId
    private val min = 60_000L

    private fun membership() = Membership.compute(gid, ops)!!

    private fun EntryAuthor.limit(rank: Long, ms: Long, deleted: Boolean = false) = appRuleEntry(
        child.bytes(), AppRule.Target.APP, packageName = "com.x", mode = AppRule.Mode.LIMIT, limitMs = ms, rank = rank, deleted = deleted,
    )

    @Test
    fun theHighestRankWinsThenTheAuthor() {
        val first = mom.limit(1, 30 * min)
        val edit = dad.limit(AppRules.nextRank(first), 60 * min)
        assertEquals(listOf(60 * min), AppRules.effective(listOf(first, edit), membership()).map { it.limitMs })
        val tieMom = EntryAuthor(gid, momKeys, lastSeq = 5).limit(7, 15 * min)
        val tieDad = EntryAuthor(gid, dadKeys, lastSeq = 5).limit(7, 45 * min)
        val winner = if (momKeys.deviceId.hex > dadKeys.deviceId.hex) 15 * min else 45 * min
        assertEquals(winner, AppRules.effective(listOf(tieMom, tieDad), membership()).single().limitMs)
        assertEquals(winner, AppRules.effective(listOf(tieDad, tieMom), membership()).single().limitMs)
        assertEquals(1L, AppRules.nextRank(null))
        assertEquals(AppRuleRules.MAX_RANK, AppRules.nextRank(mom.limit(AppRuleRules.MAX_RANK, 15 * min)))
    }

    @Test
    fun aDeletedRuleIsHidden() {
        val first = mom.limit(1, 30 * min)
        val gone = mom.limit(2, 30 * min, deleted = true)
        assertEquals(emptyList(), AppRules.effective(listOf(first, gone), membership()))
        assertEquals(setOf(ThresholdRule.idOf(child, ThresholdTarget.App("com.x"))), AppRules.winners(listOf(first, gone), membership()).keys)
    }

    @Test
    fun childrenNeverSetARule() {
        val kids = kid.limit(9, 30 * min)
        val dads = dad.appRuleEntry(child.bytes(), AppRule.Target.TOTAL, mode = AppRule.Mode.LIMIT, limitMs = 60 * min, rank = 1)
        assertEquals(listOf(ThresholdTarget.Total), AppRules.effective(listOf(kids, dads), membership()).map { it.target })
    }

    @Test
    fun wireAndToRuleRoundTrip() {
        val rules = listOf(
            ThresholdRule.of(child, ThresholdTarget.Category(AppCategory.GAMES), 60 * min, blockOnLimit = true)!!,
            ThresholdRule.block(child, ThresholdTarget.Category(AppCategory.VIDEO))!!,
            ThresholdRule.allow(child, "com.x")!!,
        )
        for (r in rules) {
            val w = AppRules.wire(r)
            val e = mom.appRuleEntry(
                w.child_id.toByteArray(), w.target, w.package_name, w.category, w.mode, w.limit_ms, w.block_on_limit, rank = 1,
            )
            assertEquals(r.copy(author = momKeys.deviceId), AppRules.toRule(e), "${r.mode}")
        }
    }

    @Test
    fun aMalformedShapeReadsAsNoRule() {
        val bad = mom.appRuleEntry(child.bytes(), AppRule.Target.TOTAL, mode = AppRule.Mode.BLOCK, rank = 1)
        assertNull(AppRules.toRule(bad))
        assertEquals(emptyList(), AppRules.effective(listOf(bad), membership()))
        assertNotNull(AppRules.toRule(mom.limit(1, 15 * min)))
    }
}
