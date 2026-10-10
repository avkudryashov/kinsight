package app.kinsight.core.transport.ble

import kotlinx.coroutines.CancellationException
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
import kotlin.coroutines.CoroutineContext

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
 * The blocking accept loop of an L2CAP listener, on [context] (Dispatchers.IO). Each socket goes to [onSocket]; one
 * that arrives after close, or that [onSocket] fails on, is [discard]ed. The loop ends quietly when [isClosed] (closing
 * the listener makes accept() throw).
 *
 * An accept() that fails while not closed (emulator run 2026-10-10: «read failed, socket might closed or timeout»
 * under a connection storm, after which every dial met «No resources Available») does not end the loop: the dead
 * listener is closed and [reopen] opens a new one (a new PSM: [reopen] publishes it) after [delaysMs] (1 s, 5 s, 30 s,
 * 2 min; reset by the next socket). When the last delay's attempt fails too, [onGiveUp] gets the cause once and the
 * loop ends; the owner then restarts the radio as a whole. One log line per failure and one per outcome, never one per
 * attempt.
 */
internal fun <L : AutoCloseable, S> CoroutineScope.launchResilientAcceptLoop(
    first: L,
    accept: (L) -> S,
    reopen: () -> L,
    isClosed: () -> Boolean,
    onSocket: (S) -> Unit,
    discard: (S) -> Unit,
    onGiveUp: (String) -> Unit,
    log: (String) -> Unit,
    what: String = "l2cap",
    delaysMs: List<Long> = LISTENER_REOPEN_DELAYS_MS,
    context: CoroutineContext = Dispatchers.IO,
): Job = launch(context) {
    var listener = first
    var failures = 0 // consecutive, reset by a socket
    while (isActive) {
        val socket = try {
            accept(listener)
        } catch (e: Exception) {
            if (isClosed() || !isActive) {
                log("$what accept ended: $e") // close() of the listener lands here
                break
            }
            runCatching { listener.close() }
            var cause: Exception = e
            log("$what accept failed: $e; reopening the listener")
            var next: L? = null
            while (next == null) {
                val wait = delaysMs.getOrNull(failures)
                if (wait == null) {
                    log("$what listener cannot be reopened after ${delaysMs.size} attempts: $cause")
                    onGiveUp("$what listener failed: $cause")
                    return@launch
                }
                failures++
                delay(wait)
                if (isClosed()) return@launch
                next = try {
                    reopen()
                } catch (oe: Exception) {
                    if (oe is CancellationException) throw oe
                    cause = oe
                    null
                }
            }
            if (isClosed()) {
                runCatching { next.close() }
                return@launch
            }
            log("$what listener reopened (attempt $failures)")
            listener = next
            continue
        }
        failures = 0
        if (isClosed()) {
            runCatching { discard(socket) }
            break
        }
        try {
            onSocket(socket)
        } catch (e: Exception) {
            runCatching { discard(socket) }
            log("$what inbound unusable: $e")
        }
    }
}

/** 1 s, 5 s, 30 s, 2 min: then the owner restarts the whole radio (RadioRetrySchedule). */
internal val LISTENER_REOPEN_DELAYS_MS = listOf(1_000L, 5_000L, 30_000L, 120_000L)

/**
 * The standing problem of a pairing host, for the invite screen: a refused GATT service (guests cannot read the PSM)
 * outranks the advert's state. The advert follows AdvertiseRetry: one retry [RETRY_MS] after a failure, then the
 * failure stands; ALREADY_STARTED counts as on the air. Callbacks arrive on binder threads.
 */
internal class PairingAdvertProblems(private val scope: CoroutineScope, private val log: (String) -> Unit) {
    private val retry = AdvertiseRetry()
    private val _problem = MutableStateFlow<RadioProblem?>(null)
    val problem: StateFlow<RadioProblem?> = _problem.asStateFlow()

    @Volatile
    private var service: RadioProblem? = null

    @Volatile
    private var advert: RadioProblem? = null

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
            setAdvert(RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, cause = e::class.simpleName))
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
                setAdvert(RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, retrying = true, code = errorCode))
                val gen = retry.current()
                pending = scope.launch {
                    delay(RETRY_MS)
                    if (retry.isCurrent(gen)) attempt()
                }
            }
            AdvertiseRetry.Next.GIVE_UP -> {
                log("pairing advert failed: $errorCode")
                setAdvert(RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, code = errorCode))
            }
        }
    }

    fun serviceProblem(problem: RadioProblem) {
        log(problem.detail)
        service = problem
        publish()
    }

    /** No more starts: a pending retry is dropped. */
    fun stop() {
        stopped = true
        pending?.cancel()
    }

    private fun setAdvert(problem: RadioProblem?) {
        advert = problem
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
