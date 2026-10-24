package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.addMember
import app.kinsight.core.model.appRuleEntry
import app.kinsight.core.model.proto.AppRule
import app.kinsight.core.model.proto.DeviceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppRuleRulesTest {
    private val momKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1)
    private val kidKeys = DeviceKeys.generate()
    private val kid = EntryAuthor(genesis.groupId, kidKeys, lastSeq = 0)
    private val addKid = mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)
    private val state: MembershipState = Membership.compute(genesis.groupId, listOf(genesis.entry, addKid))!!

    private fun reason(e: DecodedEntry) = AppRuleRules.violation(e, state)

    private fun EntryAuthor.rule(
        target: AppRule.Target = AppRule.Target.APP,
        mode: AppRule.Mode = AppRule.Mode.BLOCK,
        packageName: String = if (target == AppRule.Target.APP) "com.example.game" else "",
        category: Int = if (target == AppRule.Target.CATEGORY) 3 else 0,
        limitMs: Long = 0,
        blockOnLimit: Boolean = false,
        rank: Long = 1,
        childId: ByteArray = ByteArray(16) { 5 },
    ) = appRuleEntry(childId, target, packageName, category, mode, limitMs, blockOnLimit, rank)

    private fun verdict(e: DecodedEntry, showOthers: Boolean = false) =
        EntryValidator(genesis.groupId, state, SyncPolicy(kidKeys.deviceId, showOthers)).check(e, alreadyAccounted = false)

    @Test
    fun aParentsAppRuleIsValidAndTheChildTakesIt() {
        val e = mom.rule()
        assertEquals(EntryKind.APP_RULE, e.kind)
        assertNull(reason(e))
        assertEquals(Verdict.Accept, verdict(e))
    }

    @Test
    fun theValidShapes() {
        val ok = listOf(
            mom.rule(AppRule.Target.TOTAL, AppRule.Mode.LIMIT, limitMs = AppRuleRules.MAX_MS),
            mom.rule(AppRule.Target.CATEGORY, AppRule.Mode.LIMIT, limitMs = AppRuleRules.MIN_MS, blockOnLimit = true),
            mom.rule(AppRule.Target.CATEGORY, AppRule.Mode.BLOCK),
            mom.rule(AppRule.Target.APP, AppRule.Mode.ALLOW),
            mom.rule(AppRule.Target.CATEGORY, AppRule.Mode.BLOCK, category = CategoryOverrideRules.MAX_CATEGORY),
            mom.rule(rank = 0),
            mom.rule(rank = AppRuleRules.MAX_RANK),
        )
        for (e in ok) assertNull(reason(e), e.body.app_rule.toString())
    }

    @Test
    fun aMalformedRuleIsSkippedWithItsReason() {
        assertEquals("child id of 15 bytes", reason(mom.rule(childId = ByteArray(15))))
        assertEquals("app rule from a child", reason(kid.rule()))
        assertEquals("total only limits", reason(mom.rule(AppRule.Target.TOTAL, AppRule.Mode.BLOCK)))
        assertEquals("allow only apps", reason(mom.rule(AppRule.Target.CATEGORY, AppRule.Mode.ALLOW)))
        assertEquals("limit off the step", reason(mom.rule(mode = AppRule.Mode.LIMIT, limitMs = 16 * 60_000L)))
        assertEquals("limit off the step", reason(mom.rule(mode = AppRule.Mode.LIMIT, limitMs = 0)))
        assertEquals("limit off the step", reason(mom.rule(mode = AppRule.Mode.LIMIT, limitMs = AppRuleRules.MAX_MS + AppRuleRules.STEP_MS)))
        assertEquals("limit without LIMIT", reason(mom.rule(mode = AppRule.Mode.BLOCK, limitMs = AppRuleRules.STEP_MS)))
        assertEquals("block on limit without LIMIT", reason(mom.rule(mode = AppRule.Mode.BLOCK, blockOnLimit = true)))
        assertEquals("bad package", reason(mom.rule(packageName = "~other")))
        assertEquals("bad package", reason(mom.rule(packageName = "")))
        assertEquals("package without APP", reason(mom.rule(AppRule.Target.CATEGORY, AppRule.Mode.BLOCK, packageName = "com.example.game")))
        assertEquals("category out of range", reason(mom.rule(AppRule.Target.CATEGORY, AppRule.Mode.BLOCK, category = 0)))
        assertEquals("category out of range", reason(mom.rule(AppRule.Target.CATEGORY, AppRule.Mode.BLOCK, category = CategoryOverrideRules.MAX_CATEGORY + 1)))
        assertEquals("category without CATEGORY", reason(mom.rule(AppRule.Target.APP, category = 3)))
        assertEquals("rank ${AppRuleRules.MAX_RANK + 1} out of range", reason(mom.rule(rank = AppRuleRules.MAX_RANK + 1)))
        assertEquals("rank -1 out of range", reason(mom.rule(rank = -1)))
        assertEquals("unknown target", reason(mom.rule(AppRule.Target.TARGET_UNSPECIFIED, AppRule.Mode.BLOCK)))
        assertEquals("unknown mode", reason(mom.rule(mode = AppRule.Mode.MODE_UNSPECIFIED)))
        assertEquals(Verdict.Skipped("limit off the step"), verdict(mom.rule(mode = AppRule.Mode.LIMIT, limitMs = 16 * 60_000L)))
    }

    @Test
    fun theBodyCapHolds() {
        val big = mom.rule(packageName = "a".repeat(AppRuleRules.MAX_BODY_BYTES))
        assertEquals("app rule over ${AppRuleRules.MAX_BODY_BYTES} bytes", reason(big))
    }

    @Test
    fun shapeOkAgreesWithViolation() {
        assertEquals(true, AppRuleRules.shapeOk(AppRule.Target.APP, AppRule.Mode.BLOCK, "com.example.game", 0, 0, false))
        assertEquals(false, AppRuleRules.shapeOk(AppRule.Target.TOTAL, AppRule.Mode.ALLOW, "", 0, 0, false))
    }
}
