package app.kinsight.core.sync

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
}
