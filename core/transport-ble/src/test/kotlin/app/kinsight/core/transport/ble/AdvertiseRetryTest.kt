package app.kinsight.core.transport.ble

import android.bluetooth.le.AdvertiseCallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvertiseRetryTest {
    private val tooMany = AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS

    @Test
    fun oneRetryPerAdvertThenGiveUp() {
        val r = AdvertiseRetry()
        r.fresh()
        assertEquals(AdvertiseRetry.Next.RETRY, r.onFailure(tooMany))
        assertEquals(AdvertiseRetry.Next.GIVE_UP, r.onFailure(tooMany))
        assertEquals(AdvertiseRetry.Next.GIVE_UP, r.onFailure(tooMany))
    }

    @Test
    fun alreadyStartedCountsAsSuccess() {
        val r = AdvertiseRetry()
        r.fresh()
        assertEquals(AdvertiseRetry.Next.OK, r.onFailure(AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED))
        assertEquals(AdvertiseRetry.Next.RETRY, r.onFailure(tooMany))
    }

    @Test
    fun aNewAdvertGetsItsOwnRetryAndSupersedesAPendingOne() {
        val r = AdvertiseRetry()
        val first = r.fresh()
        assertEquals(AdvertiseRetry.Next.RETRY, r.onFailure(tooMany))
        assertTrue(r.isCurrent(first))
        val second = r.fresh()
        assertFalse(r.isCurrent(first))
        assertTrue(r.isCurrent(second))
        assertEquals(AdvertiseRetry.Next.RETRY, r.onFailure(tooMany))
    }
}
