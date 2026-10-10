package app.kinsight.core.transport.ble

import app.kinsight.core.sync.ByteStream
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import kotlin.time.Duration

/** The radio cannot be used for pairing; [reason] tells the screen what to ask the user for. */
class PairingRadioException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason { NO_PERMISSION, BLUETOOTH_OFF, RADIO }
}

/** Nobody advertised the invite's tag in time: the invite screen is closed, expired, or the phones are too far apart. */
class InviteHostNotFoundException(message: String) : IOException(message)

/**
 * The radio side of pairing (spec §6.4), separate from the sync radio: while the invite screen is open the admin
 * advertises the invite's tag on its own GATT service and L2CAP server; the new device finds the tag and dials it.
 */
interface PairingLink {
    /** Starts hosting; every inbound stream goes to [onInbound]. Closing the handle stops it. Throws PairingRadioException. */
    fun host(scope: CoroutineScope, tag: ByteArray, onInbound: (ByteStream) -> Unit): AutoCloseable

    /** Finds the host advertising [tag] and connects; InviteHostNotFoundException after [timeout]. */
    suspend fun dial(tag: ByteArray, timeout: Duration): ByteStream
}

/** Legacy advertising budget of the pairing advert: flags (3) + service data AD with a 128-bit UUID (2 + 16 + tag). */
object PairingAdvert {
    const val LEGACY_BYTES = 31

    fun advertisementBytes(tagBytes: Int): Int = 3 + 2 + 16 + tagBytes

    /**
     * Whether a scan result's pairing service data is exactly [tag]. The scan filter already matches it, but a filter
     * compares a prefix and some controllers offload filters loosely, so the callback checks again before dialing.
     */
    fun carriesTag(serviceData: ByteArray?, tag: ByteArray): Boolean = serviceData != null && serviceData.contentEquals(tag)
}
