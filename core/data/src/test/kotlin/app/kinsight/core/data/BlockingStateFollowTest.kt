package app.kinsight.core.data

import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** App blocking spec §5.4: BLOCKING_OFF is queued like LOCATION_DISABLED, only while a rule needs the service. */
class BlockingStateFollowTest {
    private val mom = TestDevice()
    private val kid = TestDevice()

    @AfterTest
    fun cleanUp() {
        mom.close()
        kid.close()
    }

    private suspend fun queued() = RoomUsageQueue(kid.db).pendingEvents().map { Triple(StateEvent.Kind.fromValue(it.kind), it.fromMs, it.toMs) }

    @Test
    fun signsOffOnceAfterTheGraceAndOnOnce() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        val block = ThresholdRule.block(kid.keys.deviceId, ThresholdTarget.App("com.game"))!!
        var rules = emptyList<ThresholdRule>()
        val follow = BlockingStateFollow(kid.prefs, kid.pipeline, rules = { rules }, clock = { kid.now })

        assertFalse(follow.onState(serviceEnabled = false), "no blocking rule: an off service is no problem")
        assertEquals(emptyList(), queued())

        rules = listOf(block)
        val off = kid.now
        assertFalse(follow.onState(serviceEnabled = false), "first off: only a candidate")
        assertEquals(emptyList(), queued())
        kid.now += BlockingStateFollow.OFF_GRACE_MS
        assertTrue(follow.onState(serviceEnabled = false), "off again after the grace: opens, from the first observation")
        kid.now += 60_000
        assertFalse(follow.onState(serviceEnabled = false), "still off: nothing new")
        assertEquals(listOf(Triple(StateEvent.Kind.BLOCKING_OFF, off, off)), queued())
        assertEquals(off, kid.prefs.blockingOffSince())

        assertTrue(follow.onState(serviceEnabled = true))
        assertFalse(follow.onState(serviceEnabled = true))
        val closing = queued().last()
        assertEquals(Triple(StateEvent.Kind.BLOCKING_OFF, off, off + 2 * BlockingStateFollow.OFF_GRACE_MS), closing)
        assertTrue(closing.third > closing.second)
        assertNull(kid.prefs.blockingOffSince())
    }

    /** The service list fills asynchronously at process start: off, then on within the grace signs nothing. */
    @Test
    fun aBriefOffWithinTheGraceSignsNothing() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        val rules = listOf(ThresholdRule.block(kid.keys.deviceId, ThresholdTarget.App("com.game"))!!)
        val follow = BlockingStateFollow(kid.prefs, kid.pipeline, rules = { rules }, clock = { kid.now })
        assertFalse(follow.onState(serviceEnabled = false))
        assertTrue(follow.offPending)
        kid.now += 250
        assertFalse(follow.onState(serviceEnabled = true))
        assertFalse(follow.offPending)
        // The cleared candidate: a later off starts a new grace instead of opening at once.
        kid.now += BlockingStateFollow.OFF_GRACE_MS
        assertFalse(follow.onState(serviceEnabled = false))
        assertEquals(emptyList(), queued())
        assertNull(kid.prefs.blockingOffSince())
    }

    /** The parents removed every blocking rule while the service was off: the open problem closes, nothing stays open. */
    @Test
    fun anOpenProblemClosesWhenNoRuleNeedsTheService() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        var rules = listOf(ThresholdRule.block(kid.keys.deviceId, ThresholdTarget.App("com.game"))!!)
        val follow = BlockingStateFollow(kid.prefs, kid.pipeline, rules = { rules }, clock = { kid.now })
        assertFalse(follow.onState(serviceEnabled = false))
        kid.now += BlockingStateFollow.OFF_GRACE_MS
        assertTrue(follow.onState(serviceEnabled = false))
        rules = emptyList()
        // Even a clock gone back closes it with an end after its start.
        kid.now -= 5_000
        assertTrue(follow.onState(serviceEnabled = false))
        val (open, close) = queued()
        assertEquals(open.second, close.second)
        assertTrue(close.third > close.second)
        assertFalse(follow.onState(serviceEnabled = false))
    }

    /** A refused event (not a consenting child) keeps no «since», so the next check tries again. */
    @Test
    fun aRefusedEventKeepsNothing() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom, consent = false)
        val follow = BlockingStateFollow(kid.prefs, kid.pipeline, rules = { listOf(ThresholdRule.block(kid.keys.deviceId, ThresholdTarget.App("com.game"))!!) }, clock = { kid.now })
        assertFalse(follow.onState(serviceEnabled = false))
        kid.now += BlockingStateFollow.OFF_GRACE_MS
        assertFalse(follow.onState(serviceEnabled = false))
        assertNull(kid.prefs.blockingOffSince())
    }
}
