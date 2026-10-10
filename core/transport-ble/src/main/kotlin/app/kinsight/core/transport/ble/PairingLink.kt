package app.kinsight.core.transport.ble

import app.kinsight.core.sync.ByteStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import kotlin.time.Duration

/** The radio cannot be used for pairing; [reason] tells the screen what to ask the user for. */
class PairingRadioException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason { NO_PERMISSION, BLUETOOTH_OFF, RADIO }
}

/** Nobody advertised the invite's tag in time: the invite screen is closed, expired, or the phones are too far apart. */
class InviteHostNotFoundException(message: String) : IOException(message)

/** An open invite on the radio. Closing it stops the advert and the servers; idempotent, never throws. */
interface PairingHostHandle : AutoCloseable {
    /**
     * A standing radio problem while hosting, for the invite screen (the advert failed after its retry, the GATT service
     * was refused), or null while guests can find and dial this phone.
     */
    val problem: StateFlow<String?>
}

/**
 * The radio side of pairing (spec §6.4), separate from the sync radio: while the invite screen is open the admin
 * advertises the invite's tag on its own GATT service and L2CAP server; the new device finds the tag and dials it.
 */
interface PairingLink {
    /**
     * Starts hosting and returns once the advert has been asked for. Every inbound stream goes to [onInbound], called on
     * the accept thread: it must not block (launch the work), or the next guest waits. Closing the handle, or the end
     * of [scope], stops it. Throws PairingRadioException; IllegalArgumentException for a tag that does not fit the advert.
     */
    suspend fun host(scope: CoroutineScope, tag: ByteArray, onInbound: (ByteStream) -> Unit): PairingHostHandle

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
