package app.kinsight.core.transport.ble

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The L2CAP accept loop must outlive a failing accept() (emulator run 2026-10-10, finding 2): the listener is closed
 * and reopened after 1 s, 5 s, 30 s, 2 min (a new PSM each time, handed to onListener), and only a listener that
 * cannot be brought back is given up, once, through onGiveUp.
 */
class ResilientAcceptLoopTest {
    /** A scripted listener: accept() returns the next socket, or throws when the script says so or is exhausted. */
    private inner class FakeListener(val psm: Int, script: List<Int?>) : AutoCloseable {
        private val steps = ArrayDeque(script)
        var closed = false

        fun accept(): Int {
            if (closed) throw IOException("closed")
            val next = if (steps.isEmpty()) null else steps.poll()
            return next ?: throw IOException("read failed, socket might closed or timeout, read ret: -1")
        }

        override fun close() {
            closed = true
        }
    }

    private val logs = mutableListOf<String>()
    private val delivered = mutableListOf<Int>()
    private val published = mutableListOf<Int>()
    private var gaveUp: String? = null
    private var stopped = false

    @Test
    fun aFailedAcceptReopensTheListenerAfterOneSecondWithANewPsm() = runTest {
        val first = FakeListener(130, listOf(1))
        val second = FakeListener(131, listOf(2, 3))
        val opens = ArrayDeque(listOf(second))
        backgroundScope.launchResilientAcceptLoop(
            first = first,
            accept = FakeListener::accept,
            reopen = { opens.poll()!!.also { published += it.psm } },
            isClosed = { stopped },
            onSocket = { delivered += it; if (it == 3) stopped = true },
            discard = {},
            onGiveUp = { gaveUp = it },
            log = { logs += it },
            context = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertEquals(listOf(1), delivered)
        assertTrue(first.closed, "the dead listener is closed before a new one opens")
        advanceTimeBy(999)
        runCurrent()
        assertEquals(emptyList(), published)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(131), published)
        assertEquals(listOf(1, 2, 3), delivered)
        assertNull(gaveUp)
    }

    @Test
    fun reopeningBacksOffOneFiveThirtySecondsTwoMinutesThenGivesUpOnce() = runTest {
        val first = FakeListener(130, emptyList())
        var attempts = 0
        val at = mutableListOf<Long>()
        backgroundScope.launchResilientAcceptLoop(
            first = first,
            accept = FakeListener::accept,
            reopen = {
                attempts++
                at += testScheduler.currentTime
                throw IOException("No resources Available")
            },
            isClosed = { stopped },
            onSocket = { delivered += it },
            discard = {},
            onGiveUp = { gaveUp = it },
            log = { logs += it },
            context = StandardTestDispatcher(testScheduler),
        )
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(listOf(1_000L, 6_000L, 36_000L, 156_000L), at)
        assertEquals(4, attempts)
        assertTrue(gaveUp!!.contains("No resources"), "the last cause is named: $gaveUp")
        // One line when the listener fails and one when it is given up: never a line per attempt.
        assertEquals(2, logs.size, "logs: $logs")
    }

    @Test
    fun aSocketResetsTheBackoff() = runTest {
        val first = FakeListener(130, emptyList())
        val opens = ArrayDeque(listOf(FakeListener(131, listOf(1)), FakeListener(132, listOf(2))))
        val at = mutableListOf<Long>()
        backgroundScope.launchResilientAcceptLoop(
            first = first,
            accept = FakeListener::accept,
            reopen = { at += testScheduler.currentTime; opens.poll() ?: throw IOException("gone") },
            isClosed = { stopped },
            onSocket = { delivered += it },
            discard = {},
            onGiveUp = { gaveUp = it },
            log = { logs += it },
            context = StandardTestDispatcher(testScheduler),
        )
        advanceTimeBy(3_000)
        runCurrent()
        // 1 s after the first failure, then 1 s again (socket 1 arrived in between), then 1 s after socket 2.
        assertEquals(listOf(1_000L, 2_000L, 3_000L), at)
        assertEquals(listOf(1, 2), delivered)
    }

    @Test
    fun closingTheRadioEndsTheLoopWithoutReopening() = runTest {
        val first = FakeListener(130, emptyList())
        stopped = true
        var attempts = 0
        val job = backgroundScope.launchResilientAcceptLoop(
            first = first,
            accept = FakeListener::accept,
            reopen = { attempts++; FakeListener(131, emptyList()) },
            isClosed = { stopped },
            onSocket = { delivered += it },
            discard = {},
            onGiveUp = { gaveUp = it },
            log = { logs += it },
            context = StandardTestDispatcher(testScheduler),
        )
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(0, attempts)
        assertNull(gaveUp)
    }
}
