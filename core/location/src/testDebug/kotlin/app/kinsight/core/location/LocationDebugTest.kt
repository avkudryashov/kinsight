package app.kinsight.core.location

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocationDebugTest {
    private var saved = TrackerState()

    private val store = object : TrackerStore {
        override suspend fun load() = saved

        override suspend fun save(s: TrackerState) {
            saved = s
        }

        override suspend fun clear() {
            saved = TrackerState()
        }
    }

    private val engine = LocationEngine(
        object : LocationHost {
            override suspend fun zones(): List<Zone>? = null

            override suspend fun record(events: List<ZoneEvent>, report: Report?) = false

            override fun upload() = Unit
        },
        store,
        object : FixSource {
            override fun ready() = false

            override suspend fun fix(timeoutMs: Long): Fix? = null
        },
        object : ProximityAlerts {
            override fun register(zone: Zone) = Unit

            override fun unregister(zoneId: String) = Unit
        },
        object : Cadence {
            override fun startService() = true

            override fun stopService() = Unit

            override fun armTick(delayMs: Long) = Unit

            override fun cancelTick() = Unit

            override fun schedulePeriodic() = Unit

            override fun cancelPeriodic() = Unit
        },
    )

    @BeforeTest
    fun setUp() {
        ServiceGate.reset()
        ServiceGate.clock = { 1_000L }
    }

    @AfterTest
    fun cleanUp() {
        ServiceGate.reset()
    }

    @Test
    fun theStatusCountsZonesAndNamesNoneOfThem() = runTest {
        saved = TrackerState(Mode.OUTSIDE, inside = setOf("aa".repeat(16)), registered = setOf("aa".repeat(16) + ":1", "bb".repeat(16) + ":2"), nextTickMs = 42L)
        ServiceGate.wantStart()
        val s = LocationDebug.status(engine)
        assertEquals(LocationDebugStatus(Mode.OUTSIDE, serviceForeground = false, serviceStartsPending = 1, nextTickMs = 42L, registeredZones = 2), s)
        assertFalse("aa" in s.toString() || "bb" in s.toString(), "no zone id in the status")
        assertEquals(TrackerState(Mode.OUTSIDE, setOf("aa".repeat(16)), saved.registered, 42L), saved, "reading changes nothing")
    }
}
