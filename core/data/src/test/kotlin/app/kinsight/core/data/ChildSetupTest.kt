package app.kinsight.core.data

import app.kinsight.core.data.SetupItem.ACCESSIBILITY
import app.kinsight.core.data.SetupItem.BATTERY
import app.kinsight.core.data.SetupItem.LOCATION
import app.kinsight.core.data.SetupItem.LOCATION_BACKGROUND
import app.kinsight.core.data.SetupItem.USAGE_ACCESS
import app.kinsight.core.location.LocationPermissions.State
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.appRuleEntry
import app.kinsight.core.model.proto.AppRule
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.usage.UsageSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChildSetupTest {
    private val momKeys = DeviceKeys.generate()
    private val kidKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1, lastLamport = 1)

    /** Review M3: the accessibility step is asked only when the service would act, so the blocking consent counts too. */
    @Test
    fun enforcingNeedsTheBlockingConsent() {
        val ops = listOf(genesis.entry, mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD))
        val block = mom.appRuleEntry(kidKeys.deviceId.bytes(), AppRule.Target.APP, packageName = "com.example.game", mode = AppRule.Mode.BLOCK, rank = 1)
        val ready = GroupSnapshot.Ready(
            genesis.groupId, kidKeys.deviceId, Membership.compute(genesis.groupId, ops)!!,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), appRules = listOf(block),
        )
        assertTrue(ChildSetup.enforcing(ready, UsageSettings(consentAtMs = 1, consentVersion = 3)))
        assertFalse(ChildSetup.enforcing(ready, UsageSettings(consentAtMs = 1, consentVersion = 2)), "not re-agreed: the service would do nothing")
        assertFalse(ChildSetup.enforcing(ready, UsageSettings()), "never agreed")
        assertFalse(ChildSetup.enforcing(GroupSnapshot.NoGroup, UsageSettings(consentAtMs = 1)))
    }

    @Test
    fun nothingGrantedAsksEverythingInOrder() {
        assertEquals(listOf(USAGE_ACCESS, LOCATION, LOCATION_BACKGROUND, BATTERY), ChildSetup.pending(false, State.NONE, false, accessibility = true, enforcing = false))
    }

    @Test
    fun grantedStepsAreNotAskedAgain() {
        assertEquals(emptyList(), ChildSetup.pending(true, State.ALWAYS, true, accessibility = true, enforcing = false))
        assertEquals(listOf(LOCATION_BACKGROUND), ChildSetup.pending(true, State.WHILE_IN_USE, true, accessibility = true, enforcing = false))
    }

    @Test
    fun approximateOnlyStillAsksForPrecise() {
        assertEquals(listOf(LOCATION, LOCATION_BACKGROUND), ChildSetup.pending(true, State.COARSE_ONLY, true, accessibility = true, enforcing = false))
    }

    /** Review Focus 4: no background step while precise location is still missing. */
    @Test
    fun laterSkipsAStepAndTheBackgroundWaitsForPrecise() {
        val all = ChildSetup.pending(false, State.NONE, false, accessibility = true, enforcing = false)
        assertEquals(USAGE_ACCESS, ChildSetup.next(all, emptySet()))
        assertEquals(LOCATION, ChildSetup.next(all, setOf(USAGE_ACCESS)))
        assertEquals(BATTERY, ChildSetup.next(all, setOf(USAGE_ACCESS, LOCATION)))
        assertNull(ChildSetup.next(all, setOf(USAGE_ACCESS, LOCATION, BATTERY)))
        assertEquals(LOCATION_BACKGROUND, ChildSetup.next(ChildSetup.pending(true, State.WHILE_IN_USE, true, accessibility = true, enforcing = false), emptySet()))
    }

    /** A held-back background step is not a page: the last visible page reads «N of N». */
    @Test
    fun progressDoesNotCountTheHeldBackBackgroundStep() {
        val pending = listOf(LOCATION, LOCATION_BACKGROUND, BATTERY)
        assertEquals(1 to 2, ChildSetup.progress(0, pending, emptySet()), "location, then battery")
        assertEquals(2 to 2, ChildSetup.progress(1, pending, setOf(LOCATION)), "«Позже» on location: battery is the last page")
        // Precise location granted: the background step is a page again.
        assertEquals(1 to 2, ChildSetup.progress(0, listOf(LOCATION_BACKGROUND, BATTERY), emptySet()))
    }

    /** App blocking spec §5.4: asked last, and only while a rule needs the service (otherwise we ask for too much). */
    @Test
    fun accessibilityStepOnlyWithEnforcingRules() {
        assertEquals(emptyList(), ChildSetup.pending(true, State.ALWAYS, true, accessibility = false, enforcing = false))
        assertEquals(listOf(ACCESSIBILITY), ChildSetup.pending(true, State.ALWAYS, true, accessibility = false, enforcing = true))
        assertEquals(emptyList(), ChildSetup.pending(true, State.ALWAYS, true, accessibility = true, enforcing = true))
        assertEquals(
            listOf(USAGE_ACCESS, LOCATION, LOCATION_BACKGROUND, BATTERY, ACCESSIBILITY),
            ChildSetup.pending(false, State.NONE, false, accessibility = false, enforcing = true),
        )
    }
}
