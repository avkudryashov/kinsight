package app.kinsight.core.transport.ble

import android.bluetooth.le.AdvertiseCallback

/**
 * Advertising failures (AdvertiseCallback.onStartFailure): each new advert gets one retry, then the failure stands
 * until the next advert. ALREADY_STARTED means our advert is on the air: a success. [fresh] marks a new advert and
 * returns its generation; a pending retry runs only while [isCurrent] (a newer advert supersedes it). Thread-safe:
 * the callback arrives on a binder thread.
 */
internal class AdvertiseRetry {
    enum class Next { OK, RETRY, GIVE_UP }

    private var generation = 0L
    private var retried = false

    @Synchronized
    fun fresh(): Long {
        generation++
        retried = false
        return generation
    }

    @Synchronized
    fun current(): Long = generation

    @Synchronized
    fun isCurrent(gen: Long): Boolean = gen == generation

    @Synchronized
    fun onFailure(errorCode: Int): Next = when {
        errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> Next.OK
        !retried -> {
            retried = true
            Next.RETRY
        }
        else -> Next.GIVE_UP
    }
}
