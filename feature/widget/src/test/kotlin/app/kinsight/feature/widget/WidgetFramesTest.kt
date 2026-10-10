package app.kinsight.feature.widget

import app.kinsight.core.data.DashboardState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.ResetKind
import app.kinsight.core.data.ResetStatus
import app.kinsight.core.data.Role
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFramesTest {
    private val mom = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = true, lastSeenMs = null)
    private val parent = DashboardState(mom, null, emptyList(), emptyList())

    /** A live Glance session recomposes on updateAll: the refresh re-reads the data and the clock (review item 1). */
    @Test
    fun refreshRereadsDataAndClock() {
        runTest(UnconfinedTestDispatcher()) {
            val refresh = MutableStateFlow(0L)
            var reads = 0
            var clock = 100L
            val frames = mutableListOf<WidgetFrame>()
            val job = launch {
                widgetFrames(refresh, { reads++; flowOf(if (reads == 1) parent else null) }, flowOf(24), { clock })
                    .take(2).toList(frames)
            }
            clock = 200L
            refresh.value = 1
            job.join()
            assertEquals(2, reads)
            assertEquals(WidgetFrame(WidgetState.Parent(emptyList(), 0), 100L), frames[0])
            assertEquals(WidgetFrame(WidgetState.Empty, 200L), frames[1])
        }
    }

    @Test
    fun dataChangeWithinSessionIsShown() {
        runTest(UnconfinedTestDispatcher()) {
            val dashboard = MutableStateFlow<DashboardState?>(parent)
            val frames = mutableListOf<WidgetFrame>()
            val job = launch { widgetFrames(flowOf(0L), { dashboard }, flowOf(24), { 1L }).take(2).toList(frames) }
            dashboard.value = null
            job.join()
            assertEquals(listOf(WidgetState.Parent(emptyList(), 0), WidgetState.Empty), frames.map { it.state })
        }
    }

    @Test
    fun readFailureIsEmpty() {
        runTest {
            val frame = widgetFrames(flowOf(0L), { flow<DashboardState?> { throw IllegalStateException("db") } }, flowOf(24), { 5L }).first()
            assertEquals(WidgetFrame(WidgetState.Empty, 5L), frame)
        }
    }

    /** Review item 2: a wipe that ends (done or failed) redraws the widget; nothing else does. */
    @Test
    fun wipeEndIsDetected() {
        val running = ResetStatus.Running(ResetKind.FORGET_GROUP)
        assertTrue(KinsightWidget.wipeEnded(running, ResetStatus.Idle))
        assertTrue(KinsightWidget.wipeEnded(running, ResetStatus.Failed(ResetKind.FORGET_GROUP, "x")))
        assertFalse(KinsightWidget.wipeEnded(ResetStatus.Idle, running))
        assertFalse(KinsightWidget.wipeEnded(ResetStatus.Idle, ResetStatus.Idle))
        assertFalse(KinsightWidget.wipeEnded(running, running))
    }
}
