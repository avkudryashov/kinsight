package app.kinsight.core.sync

import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.proto.DeviceRole
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(io.kotest.common.ExperimentalKotest::class)
class ConvergenceTest {
    @Test
    fun randomSessionsWithDropsConverge(): Unit = runBlocking {
        checkAll(PropTestConfig(iterations = 15), Arb.long()) { seed ->
            val rnd = Random(seed)
            val nodes = buildGroup(rnd.nextInt(2, 6))

            // Every node writes some days, sometimes re-writing the same day (superseding).
            for (n in nodes) {
                repeat(rnd.nextInt(1, 25)) {
                    val day = TODAY.minusDays(rnd.nextLong(0, 10)).toString()
                    if (rnd.nextBoolean()) n.publish(n.author.summary(day, rnd.nextLong(0, 10_000)))
                    else n.publish(n.author.detail(day))
                }
            }

            // Random pairwise sessions, half of them with a dropped link.
            repeat(rnd.nextInt(5, 20)) {
                val a = nodes.random(rnd)
                val b = (nodes - a).random(rnd)
                val faults = if (rnd.nextBoolean()) LinkFaults(rnd.nextLong(100, 5_000)) else LinkFaults()
                val (ra, rb) = syncPair(a, b, faults)
                assertTrue(ra !is SessionResult.TooManyInvalid && rb !is SessionResult.TooManyInvalid, "seed=$seed")
            }

            // Two clean rounds over every pair.
            repeat(2) {
                for (i in nodes.indices) for (j in i + 1 until nodes.size) {
                    val (ra, rb) = syncPair(nodes[i], nodes[j])
                    assertTrue(ra is SessionResult.Completed && rb is SessionResult.Completed, "seed=$seed: $ra / $rb")
                }
            }

            val reference = nodes[0].store.liveEntries().toSet()
            val vector = nodes[0].store.versionVector()
            for (n in nodes.drop(1)) {
                assertEquals(reference, n.store.liveEntries().toSet(), "seed=$seed live entries of ${n.name}")
                assertEquals(vector, n.store.versionVector(), "seed=$seed vector of ${n.name}")
            }
        }
    }

    @Test
    fun mixedRetentionConvergesWithinEachNodesRetention(): Unit = runBlocking {
        checkAll(PropTestConfig(iterations = 15), Arb.long()) { seed ->
            val rnd = Random(seed)
            val nodes = buildGroup(rnd.nextInt(3, 6))
            // Some nodes keep details only a few days; they still relay what they keep.
            for (n in nodes) if (rnd.nextBoolean()) n.policy = n.policy.copy(detailRetentionDays = rnd.nextLong(1, 6))

            for (n in nodes) {
                repeat(rnd.nextInt(1, 25)) {
                    val day = TODAY.minusDays(rnd.nextLong(0, 30)).toString()
                    if (rnd.nextBoolean()) n.publish(n.author.summary(day, rnd.nextLong(0, 10_000)))
                    else n.publish(n.author.detail(day))
                }
            }

            repeat(rnd.nextInt(5, 20)) {
                val a = nodes.random(rnd)
                val b = (nodes - a).random(rnd)
                val faults = if (rnd.nextBoolean()) LinkFaults(rnd.nextLong(100, 5_000)) else LinkFaults()
                val (ra, rb) = syncPair(a, b, faults)
                assertTrue(ra !is SessionResult.TooManyInvalid && rb !is SessionResult.TooManyInvalid, "seed=$seed")
            }
            repeat(2) {
                for (i in nodes.indices) for (j in i + 1 until nodes.size) {
                    val (ra, rb) = syncPair(nodes[i], nodes[j])
                    assertTrue(ra is SessionResult.Completed && rb is SessionResult.Completed, "seed=$seed: $ra / $rb")
                }
            }

            for (holder in nodes) {
                val held = holder.store.liveEntries().toSet()
                for (author in nodes) {
                    val id = author.keys.deviceId
                    val missing = author.store.liveEntries()
                        .filter { it.author == id && !holder.policy.isExpired(it) && it !in held }
                    assertTrue(missing.isEmpty(), "seed=$seed: ${holder.name} misses ${author.name} seqs ${missing.map { it.seq }}")
                }
            }
        }
    }

    @Test
    fun categoryOverridesReachTheOtherParentNeverTheChild(): Unit = runBlocking {
        checkAll(PropTestConfig(iterations = 10), Arb.long()) { seed ->
            val rnd = Random(seed)
            // node0 and node1 are parents, node2 a child (showOthers off, as the app sets it for children).
            val nodes = buildGroup(3, showOthers = { it != 2 }, role = { if (it == 2) DeviceRole.CHILD else DeviceRole.PARENT })
            val (p0, p1, kid) = nodes
            kid.publish(kid.author.summary(TODAY.toString(), 1))
            for (i in nodes.indices) for (j in i + 1 until nodes.size) syncPair(nodes[i], nodes[j])
            val kidVector = kid.store.versionVector()
            val kidLive = kid.store.liveEntries().toSet()

            val packages = listOf("com.example.a", "com.example.b", "com.example.c")
            repeat(rnd.nextInt(2, 12)) {
                val p = if (rnd.nextBoolean()) p0 else p1
                p.publish(p.author.categoryOverride(packages.random(rnd), rnd.nextInt(0, 10)))
            }
            repeat(rnd.nextInt(3, 12)) {
                val a = nodes.random(rnd)
                val b = (nodes - a).random(rnd)
                val faults = if (rnd.nextBoolean()) LinkFaults(rnd.nextLong(100, 5_000)) else LinkFaults()
                val (ra, rb) = syncPair(a, b, faults)
                assertTrue(ra !is SessionResult.TooManyInvalid && rb !is SessionResult.TooManyInvalid, "seed=$seed")
            }
            repeat(2) {
                for (i in nodes.indices) for (j in i + 1 until nodes.size) {
                    val (ra, rb) = syncPair(nodes[i], nodes[j])
                    assertTrue(ra is SessionResult.Completed && rb is SessionResult.Completed, "seed=$seed: $ra / $rb")
                }
            }

            suspend fun overrides(n: Node) = n.store.liveEntries().filter { it.kind == EntryKind.CATEGORY_OVERRIDE }.toSet()
            val shared = overrides(p0)
            assertEquals(shared, overrides(p1), "seed=$seed: parents disagree")
            // One live override per (author, package): the author's latest.
            assertEquals(overrides(p0).map { it.supersedeKey }.toSet().size, overrides(p0).size, "seed=$seed")
            for (p in listOf(p0, p1)) {
                val own = p.store.liveEntries().filter { it.author == p.keys.deviceId && it.kind == EntryKind.CATEGORY_OVERRIDE }
                assertTrue(own.all { it in shared }, "seed=$seed: ${p.name}'s overrides missing")
            }
            assertTrue(overrides(kid).isEmpty(), "seed=$seed: the child got overrides")
            assertEquals(kidLive, kid.store.liveEntries().toSet(), "seed=$seed: the child's entries changed")
            assertEquals(kidVector[kid.keys.deviceId], kid.store.versionVector()[kid.keys.deviceId], "seed=$seed: the child's own position changed")
            // Each parent completes its own log on the child (nothing of it the child takes is missing), so the child's
            // position for it follows the parent's own: no resend of the same entries next session.
            for (p in listOf(p0, p1)) {
                assertEquals(p.store.versionVector()[p.keys.deviceId], kid.store.versionVector()[p.keys.deviceId], "seed=$seed: ${p.name}'s log on the child")
            }
        }
    }
}
