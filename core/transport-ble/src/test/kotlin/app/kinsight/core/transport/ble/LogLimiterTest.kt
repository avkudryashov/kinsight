package app.kinsight.core.transport.ble

import kotlin.test.Test
import kotlin.test.assertEquals

/** «skip <peer>: cooldown» was logged every 100 ms (3054 lines in 20 minutes): once per peer and reason per minute. */
class LogLimiterTest {
    @Test
    fun oneLinePerKeyPerMinute() {
        var now = 0L
        val l = LogLimiter({ now }, intervalMs = 60_000)
        val lines = mutableListOf<String>()
        repeat(600) {
            l.log("peer-a:cooldown", { lines += "a $now" })
            l.log("peer-b:cooldown", { lines += "b $now" })
            now += 100
        }
        assertEquals(listOf("a 0", "b 0"), lines)
        now = 60_000
        l.log("peer-a:cooldown", { lines += "a $now" })
        l.log("peer-a:busy", { lines += "busy $now" })
        assertEquals(listOf("a 0", "b 0", "a 60000", "busy 60000"), lines)
    }
}
