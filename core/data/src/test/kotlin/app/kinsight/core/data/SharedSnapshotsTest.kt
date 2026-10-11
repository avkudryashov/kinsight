package app.kinsight.core.data

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Final fix item 4: the screens' flows share one snapshot load per change (a full Room read and decode), however many
 * collectors there are; a wipe still reaches them, and a late subscriber never starts from a stale snapshot.
 */
class SharedSnapshotsTest {
    private val d = TestDevice()
    private val loads = AtomicInteger()

    /** Each load reads the identity first: counting the key reads counts the loads. */
    private fun snapshots(stopTimeoutMs: Long = 5_000) =
        Snapshots(d.db, d.groups, { loads.incrementAndGet(); d.keys }, d.transport, scope = d.scope, stopTimeoutMs = stopTimeoutMs)

    @AfterTest
    fun cleanUp() = d.close()

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(10) }
        assertTrue(condition(), what)
    }

    @Test
    fun manyCollectorsShareOneLoadPerChange() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        val snapshots = snapshots()
        val usage = UsageRepository(snapshots, d.prefs, d.appendOwn)
        val dashboard = AtomicReference<DashboardState?>()
        val parent = AtomicReference<Boolean?>()
        val rules = AtomicReference<List<ThresholdRule>?>()
        val group = AtomicReference<GroupState?>()
        val groups = GroupRepository(snapshots, d.db, d.groups, d.keyProvider, d.pipeline, d.sync, d.scope, d.sessions, forget = {})
        val jobs = listOf(
            d.scope.launch { usage.dashboard.collect { dashboard.set(it) } },
            d.scope.launch { usage.viewerIsParent.collect { parent.set(it) } },
            d.scope.launch { usage.thresholds.collect { rules.set(it) } },
            d.scope.launch { groups.state.collect { group.set(it) } },
        )
        waitUntil("every collector has a value") {
            dashboard.get() != null && parent.get() == true && rules.get() != null && group.get() is GroupState.Ready
        }
        delay(300) // nothing else in flight
        assertEquals(1, loads.get(), "four collectors, one load")

        val kidKeys = DeviceKeys.generate()
        d.pipeline.appendOwn { a -> listOf(a.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)) }
        waitUntil("the new child reached the collectors") {
            dashboard.get()?.children?.size == 1 && (group.get() as? GroupState.Ready)?.members?.size == 2
        }
        delay(300)
        assertEquals(2, loads.get(), "one change, one more load")
        jobs.forEach { it.cancel() }
    }

    @Test
    fun aWipeReachesTheSharedFlow() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        val snapshots = snapshots()
        val last = AtomicReference<GroupSnapshot?>()
        val job = d.scope.launch { snapshots.snapshots.collect { last.set(it) } }
        waitUntil("ready") { last.get() is GroupSnapshot.Ready }
        val reset = LocalReset(d.db, d.pipeline, { d.groups.delete(); d.transport.clear() }, d.settings, d.prefs, d.sync, {}, snapshots, scope = d.scope)
        reset.forgetGroup()
        waitUntil("the wipe reached the collector") { last.get() == GroupSnapshot.NoGroup }
        job.cancel()
    }

    @Test
    fun aLateSubscriberNeverStartsFromAStaleSnapshot() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        val snapshots = snapshots(stopTimeoutMs = 0)
        assertIs<GroupSnapshot.Ready>(withTimeout(10_000) { snapshots.snapshots.first() })
        // Nobody collects now; the group file goes behind the flow's back (no invalidate).
        delay(100)
        d.groups.delete()
        assertEquals(GroupSnapshot.NoGroup, withTimeout(10_000) { snapshots.snapshots.firstValue() })
    }

    private suspend fun Flow<GroupSnapshot>.firstValue(): GroupSnapshot = first()

    /** [failures] failing loads (an exception, not a key or group-file error), then real ones; counts every load. */
    private fun failing(failures: Int): Pair<Snapshots, AtomicInteger> {
        val s = Snapshots(d.db, d.groups, d.keyProvider, d.transport, scope = d.scope, retryBaseMs = 10)
        val calls = AtomicInteger()
        s.loader = { seen ->
            if (calls.incrementAndGet() <= failures) throw IllegalStateException("database is locked")
            s.load(seen)
        }
        return s to calls
    }

    /** Re-review item 1: a load that fails once is retried, and the root never shows the key error screen. */
    @Test
    fun oneFailingLoadIsRetriedAndNeverReachesTheErrorRoot() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        val (snapshots, calls) = failing(1)
        val groups = GroupRepository(snapshots, d.db, d.groups, d.keyProvider, d.pipeline, d.sync, d.scope, d.sessions, forget = {})
        val seen = java.util.Collections.synchronizedList(mutableListOf<GroupState>())
        val job = d.scope.launch { groups.state.collect { seen += it } }
        waitUntil("ready after the retry") { seen.lastOrNull() is GroupState.Ready }
        job.cancel()
        assertEquals(2, calls.get())
        assertTrue(seen.none { it is GroupState.StorageError || it is GroupState.KeyError }, "$seen")
    }

    @Test
    fun threeFailingLoadsInARowAreAStorageError() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        val (snapshots, calls) = failing(Int.MAX_VALUE)
        val error = withTimeout(10_000) { snapshots.snapshots.first { it !is GroupSnapshot.Ready } }
        assertIs<GroupSnapshot.StorageError>(error)
        assertEquals(3, calls.get())
    }
}
