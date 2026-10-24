package app.kinsight.core.blocking

import app.kinsight.core.data.GroupSnapshot
import app.kinsight.core.data.RuleMode
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.appRuleEntry
import app.kinsight.core.model.proto.AppRule
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.usage.UsageSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Review M2: the gate of the service's inputs, case by case. */
class EnforcerInputsTest {
    private val momKeys = DeviceKeys.generate()
    private val kidKeys = DeviceKeys.generate()
    private val siblingKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val gid = genesis.groupId
    private val mom = EntryAuthor(gid, momKeys, lastSeq = 1, lastLamport = 1)
    private val ops = listOf(
        genesis.entry,
        mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD),
        mom.addMember(siblingKeys.sign.publicKey, siblingKeys.dh.publicKey, "Sibling", DeviceRole.CHILD),
    )
    private val membership = Membership.compute(gid, ops)!!
    private val consented = UsageSettings(consentAtMs = 1, consentVersion = UsageSettings.CONSENT_VERSION)
    private var rank = 0L

    private fun rule(child: DeviceId, mode: AppRule.Mode, target: AppRule.Target = AppRule.Target.APP, blockOnLimit: Boolean = false) =
        mom.appRuleEntry(
            child.bytes(), target, packageName = if (target == AppRule.Target.APP) GAME else "", mode = mode,
            limitMs = if (mode == AppRule.Mode.LIMIT) HOUR else 0, blockOnLimit = blockOnLimit, rank = ++rank,
        )

    private fun block(child: DeviceId = kidKeys.deviceId) = rule(child, AppRule.Mode.BLOCK)

    private fun blockingLimit(child: DeviceId = kidKeys.deviceId) = rule(child, AppRule.Mode.LIMIT, AppRule.Target.TOTAL, blockOnLimit = true)

    private fun snapshot(self: DeviceId = kidKeys.deviceId, rules: List<DecodedEntry>, removed: Boolean = false) = GroupSnapshot.Ready(
        gid, self, if (removed) Membership.compute(gid, ops.take(1))!! else membership,
        summaries = emptyList(), catalog = emptyList(), events = emptyList(), heartbeats = emptyList(), peerSeen = emptyMap(), appRules = rules,
    )

    private fun of(s: GroupSnapshot, settings: UsageSettings = consented) = EnforcerInputs.of(s, settings)

    @Test
    fun offUnlessAnActiveChildWithTheBlockingConsentHasABlockingRule() {
        val off = listOf(
            "no group" to of(GroupSnapshot.NoGroup),
            "removed from the group" to of(snapshot(rules = listOf(block()), removed = true)),
            "a parent's own phone" to of(snapshot(self = momKeys.deviceId, rules = listOf(block(momKeys.deviceId)))),
            "never agreed" to of(snapshot(rules = listOf(block())), UsageSettings()),
            "agreed to the Bluetooth-only text" to of(snapshot(rules = listOf(block())), UsageSettings(consentAtMs = 1, consentVersion = 1)),
            "agreed to the Disk text, blocking not named" to of(snapshot(rules = listOf(block())), UsageSettings(consentAtMs = 1, consentVersion = 2)),
            "no enforcing rule" to of(snapshot(rules = listOf(rule(kidKeys.deviceId, AppRule.Mode.ALLOW), rule(kidKeys.deviceId, AppRule.Mode.LIMIT, AppRule.Target.TOTAL)))),
            "only another child's rules" to of(snapshot(rules = listOf(block(siblingKeys.deviceId), blockingLimit(siblingKeys.deviceId)))),
        )
        for ((case, inputs) in off) {
            assertEquals(case, false, inputs.enabled)
            assertEquals(case, emptyList<Any>(), inputs.rules)
            assertEquals(case, false, inputs.needsUsage)
        }
    }

    @Test
    fun onlyTheChildsOwnRulesAndUsageOnlyForABlockingLimit() {
        val blockOnly = of(snapshot(rules = listOf(block(), blockingLimit(siblingKeys.deviceId))))
        assertEquals(true, blockOnly.enabled)
        assertEquals(listOf(kidKeys.deviceId to RuleMode.BLOCK), blockOnly.rules.map { it.child to it.mode })
        assertEquals("a BLOCK never needs today's usage", false, blockOnly.needsUsage)
        val withLimit = of(snapshot(rules = listOf(block(), blockingLimit())))
        assertEquals(true, withLimit.enabled)
        assertEquals(setOf(RuleMode.BLOCK, RuleMode.LIMIT), withLimit.rules.map { it.mode }.toSet())
        assertEquals(true, withLimit.needsUsage)
    }

    private companion object {
        const val GAME = "com.example.game"
        const val HOUR = 3_600_000L
    }
}
