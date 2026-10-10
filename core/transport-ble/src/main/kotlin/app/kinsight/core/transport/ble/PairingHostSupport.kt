package app.kinsight.core.transport.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Closes [resource] as soon as this scope is cancelled (or the returned job is). A completion handler would not do:
 * the scope's job completes only after its children, and the accept loop is a child blocked in accept() until the
 * listener is closed. Starts undispatched, so an already-cancelled scope closes [resource] before this returns.
 */
internal fun CoroutineScope.closeOnCancel(resource: AutoCloseable): Job = launch(start = CoroutineStart.UNDISPATCHED) {
    try {
        awaitCancellation()
    } finally {
        resource.close()
    }
}

/**
 * The blocking accept loop of an L2CAP listener, on Dispatchers.IO. It ends when [accept] throws (closing the listener
 * does that). Each socket goes to [onSocket]; one that arrives after close, or that [onSocket] fails on, is [discard]ed.
 */
internal fun <S> CoroutineScope.launchAcceptLoop(
    accept: () -> S,
    isClosed: () -> Boolean,
    onSocket: (S) -> Unit,
    discard: (S) -> Unit,
    log: (String) -> Unit,
): Job = launch(Dispatchers.IO) {
    while (isActive) {
        val socket = try {
            accept()
        } catch (e: Exception) {
            log("pairing accept ended: $e") // close() of the listener lands here
            break
        }
        if (isClosed()) {
            runCatching { discard(socket) }
            break
        }
        try {
            onSocket(socket)
        } catch (e: Exception) {
            runCatching { discard(socket) }
            log("pairing inbound unusable: $e")
        }
    }
}

/**
 * The standing problem of a pairing host, for the invite screen: a refused GATT service (guests cannot read the PSM)
 * outranks the advert's state. The advert follows AdvertiseRetry: one retry [RETRY_MS] after a failure, then the
 * failure stands; ALREADY_STARTED counts as on the air. Callbacks arrive on binder threads.
 */
internal class PairingAdvertProblems(private val scope: CoroutineScope, private val log: (String) -> Unit) {
    private val retry = AdvertiseRetry()
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    @Volatile
    private var service: String? = null

    @Volatile
    private var advert: String? = null

    @Volatile
    private var start: () -> Unit = {}

    @Volatile
    private var stopped = false

    @Volatile
    private var pending: Job? = null

    /** Starts the advert through [start] (which may throw: that is a failure with no retry). */
    fun begin(start: () -> Unit) {
        this.start = start
        retry.fresh()
        attempt()
    }

    private fun attempt() {
        if (stopped) return
        try {
            start()
        } catch (e: Exception) {
            log("pairing advert start threw: $e")
            setAdvert("advertising failed: ${e::class.simpleName}")
        }
    }

    fun onStartSuccess() {
        log("pairing advert on")
        setAdvert(null)
    }

    fun onStartFailure(errorCode: Int) {
        when (retry.onFailure(errorCode)) {
            AdvertiseRetry.Next.OK -> setAdvert(null)
            AdvertiseRetry.Next.RETRY -> {
                log("pairing advert failed: $errorCode, retrying in ${RETRY_MS / 1000} s")
                setAdvert("advertising failed ($errorCode), retrying")
                val gen = retry.current()
                pending = scope.launch {
                    delay(RETRY_MS)
                    if (retry.isCurrent(gen)) attempt()
                }
            }
            AdvertiseRetry.Next.GIVE_UP -> {
                log("pairing advert failed: $errorCode")
                setAdvert("advertising failed ($errorCode)")
            }
        }
    }

    fun serviceProblem(text: String) {
        log(text)
        service = text
        publish()
    }

    /** No more starts: a pending retry is dropped. */
    fun stop() {
        stopped = true
        pending?.cancel()
    }

    private fun setAdvert(text: String?) {
        advert = text
        publish()
    }

    @Synchronized
    private fun publish() {
        _problem.value = service ?: advert
    }

    companion object {
        const val RETRY_MS = 5_000L
    }
}
