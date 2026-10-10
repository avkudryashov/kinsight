package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals

class AlertMarkersTest {
    private val a = DeviceId("aa".repeat(16))
    private val b = DeviceId("bb".repeat(16))

    @Test
    fun roundTrip() {
        val m = AlertMarkers(mapOf(a to 5L, b to 9L), setOf(b), setOf(a), "2026-10-10")
        assertEquals(m, AlertMarkers.decode(m.encode()))
        assertEquals(AlertMarkers(), AlertMarkers.decode(AlertMarkers().encode()))
        assertEquals(AlertMarkers(knownParents = emptySet()), AlertMarkers.decode(AlertMarkers(knownParents = emptySet()).encode()))
    }

    @Test
    fun garbageDecodesToDefaults() {
        assertEquals(AlertMarkers(), AlertMarkers.decode(null))
        assertEquals(AlertMarkers(), AlertMarkers.decode("n:zz=1\nc:nothex\nweird"))
    }

    @Test
    fun badPairsAreSkippedOneByOne() {
        val c = DeviceId("cc".repeat(16))
        val text = "n:zz=1,${a.hex}=5,${b.hex}=-1,${c.hex}=x,${c.hex}\nc:nothex,${b.hex}\np:${"zz".repeat(16)},${a.hex}\ns:"
        assertEquals(AlertMarkers(mapOf(a to 5L), setOf(b), setOf(a), null), AlertMarkers.decode(text))
    }
}
