package app.kinsight.core.usage

import android.app.usage.UsageEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventTypeMappingTest {
    @Test
    fun mapsTheEventsThePipelineReads() {
        assertEquals(EventType.RESUMED, UsageStatsEventSource.typeOf(UsageEvents.Event.ACTIVITY_RESUMED))
        assertEquals(EventType.PAUSED, UsageStatsEventSource.typeOf(UsageEvents.Event.ACTIVITY_PAUSED))
        assertEquals(EventType.SCREEN_ON, UsageStatsEventSource.typeOf(UsageEvents.Event.SCREEN_INTERACTIVE))
        assertEquals(EventType.SCREEN_OFF, UsageStatsEventSource.typeOf(UsageEvents.Event.SCREEN_NON_INTERACTIVE))
        assertEquals(EventType.UNLOCK, UsageStatsEventSource.typeOf(UsageEvents.Event.KEYGUARD_HIDDEN))
        assertEquals(EventType.SHUTDOWN, UsageStatsEventSource.typeOf(UsageEvents.Event.DEVICE_SHUTDOWN))
        assertEquals(EventType.STARTUP, UsageStatsEventSource.typeOf(UsageEvents.Event.DEVICE_STARTUP))
    }

    @Test
    fun ignoresEverythingElse() {
        assertNull(UsageStatsEventSource.typeOf(UsageEvents.Event.ACTIVITY_STOPPED))
        assertNull(UsageStatsEventSource.typeOf(UsageEvents.Event.KEYGUARD_SHOWN))
        assertNull(UsageStatsEventSource.typeOf(UsageEvents.Event.CONFIGURATION_CHANGE))
        assertNull(UsageStatsEventSource.typeOf(UsageEvents.Event.FOREGROUND_SERVICE_START))
    }
}
