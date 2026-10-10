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
    fun initializedFlagRoundTripsAndOldTextIsNot() {
        val m = AlertMarkers(mapOf(a to 5L), initialized = true)
        assertEquals(m, AlertMarkers.decode(m.encode()))
        assertEquals(false, AlertMarkers.decode("n:${a.hex}=5").initialized)
    }

    @Test
    fun thresholdMarkersRoundTripWithOddPackages() {
        val keys = setOf(
            "${a.hex}/total@3600000@2026-10-10",
            "${a.hex}/app:com.odd,pkg@x@900000@2026-10-11",
            "${b.hex}/cat:GAMES@5400000@2026-10-11",
        )
        val m = AlertMarkers(initialized = true, thresholdNotified = keys)
        assertEquals(m, AlertMarkers.decode(m.encode()))
    }

    @Test
    fun badThresholdMarkersAreSkippedOneByOne() {
        val good = "${a.hex}/total@3600000@2026-10-10"
        val text = "t:$good|nolimit@2026-10-10|${a.hex}/total@-5@2026-10-10|${a.hex}/total@60@not-a-date|@60@2026-10-10"
        assertEquals(setOf(good), AlertMarkers.decode(text).thresholdNotified)
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
