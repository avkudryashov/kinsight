package app.kinsight.feature.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.AppPrefs
import app.kinsight.core.data.InviteState
import app.kinsight.core.data.JoinState
import app.kinsight.core.data.PairingCoordinator
import app.kinsight.core.data.Role
import app.kinsight.core.sync.pairing.Invite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** An open invite as the invite screen sees it ([PairingCoordinator.InviteSession]); a seam for tests. */
internal interface InviteHandle {
    val state: StateFlow<InviteState>

    fun confirm()

    fun decline()

    fun close()
}

internal fun interface InviteOpener {
    fun open(scope: CoroutineScope, role: Role, adminName: String): InviteHandle
}

/** A join attempt as the join screen sees it ([PairingCoordinator.JoinSession]); a seam for tests. */
internal interface JoinHandle {
    val state: StateFlow<JoinState>

    fun cancel()
}

internal fun interface JoinStarter {
    /** [freshIdentity]: reset the identity first (PairingCoordinator.join). */
    fun start(scope: CoroutineScope, invite: Invite, name: String, consent: Boolean, freshIdentity: Boolean): JoinHandle
}

/** [transfer]: the storage owner's invite of its own new phone (spec §7.3, [PairingCoordinator.invite] transferOwner). */
private fun PairingCoordinator.opener(transfer: Boolean = false) = InviteOpener { scope, role, adminName ->
    val s = invite(scope, role, adminName, transferOwner = transfer)
    object : InviteHandle {
        override val state = s.state

        override fun confirm() = s.confirm()

        override fun decline() = s.decline()

        override fun close() = s.close()
    }
}

private fun PairingCoordinator.starter() = JoinStarter { scope, invite, name, consent, freshIdentity ->
    val s = join(scope, invite, name, consent, freshIdentity)
    object : JoinHandle {
        override val state = s.state

        override fun cancel() = s.cancel()
    }
}

/** The admin name an invite carries when this phone has none yet (the invite refuses a blank one). */
internal const val FALLBACK_ADMIN_NAME = "Kinsight"

/**
 * One invite at a time for this screen. The screen closes it whenever it stops being visible ([close]: radio off,
 * token dead) and [resume] opens a fresh one when it comes back, unless the invite already ended (added, expired,
 * failed: those stay on screen until the admin acts).
 */
class InviteViewModel internal constructor(
    private val opener: InviteOpener,
    private val adminName: suspend () -> String,
    private val role: Role,
) : ViewModel() {
    constructor(pairing: PairingCoordinator, prefs: AppPrefs, role: Role, transfer: Boolean = false) : this(
        pairing.opener(transfer),
        { prefs.current().deviceName?.takeIf { it.isNotBlank() } ?: FALLBACK_ADMIN_NAME },
        role,
    )

    private val _state = MutableStateFlow<InviteState>(InviteState.Starting)
    val state: StateFlow<InviteState> = _state.asStateFlow()
    private val _confirming = MutableStateFlow(false)

    /** Add was tapped: the answer is on its way until the invite moves on (added, failed, back to the QR). */
    val confirming: StateFlow<Boolean> = _confirming.asStateFlow()
    private var handle: InviteHandle? = null
    private var collector: Job? = null
    private var closed = false

    init {
        start()
    }

    /** Opens a new invite (a new code), closing the current one first. */
    fun start() {
        stop()
        closed = false
        _confirming.value = false
        _state.value = InviteState.Starting
        collector = viewModelScope.launch {
            // The session is a child of this collector: cancelling the collector also ends it (and its radio).
            val h = opener.open(this, role, adminName())
            handle = h
            h.state.collect {
                _state.value = it
                if (it !is InviteState.Confirm) _confirming.value = false
            }
        }
    }

    fun confirm() {
        if (_state.value !is InviteState.Confirm || _confirming.value) return
        _confirming.value = true
        handle?.confirm()
    }

    fun decline() {
        if (_confirming.value) return
        handle?.decline()
    }

    /** The screen went away: the radio stops and a guest waiting for the admin is refused. */
    fun close() {
        stop()
        closed = true
    }

    /** The screen is visible again: a live invite closed by [close] is replaced by a fresh one. */
    fun resume() {
        if (!closed) return
        when (_state.value) {
            InviteState.Starting, is InviteState.Showing, is InviteState.Confirm -> start()
            else -> Unit
        }
    }

    private fun stop() {
        collector?.cancel()
        collector = null
        handle?.close()
        handle = null
    }

    override fun onCleared() {
        stop()
    }
}

/**
 * One join attempt for this screen. [consent] is the child's consent from onboarding; without it the phone joins
 * without sharing. [cancel] stops searching or waiting, but never a welcome that is being stored: once the admin has
 * added this phone, finishing is better than half a group.
 */
class JoinViewModel internal constructor(
    private val starter: JoinStarter,
    private val invite: Invite,
    private val name: String,
    private val consent: Boolean,
) : ViewModel() {
    constructor(pairing: PairingCoordinator, invite: Invite, name: String, consent: Boolean) :
        this(pairing.starter(), invite, name, consent)

    private val _state = MutableStateFlow<JoinState>(JoinState.Searching)
    val state: StateFlow<JoinState> = _state.asStateFlow()
    private var handle: JoinHandle? = null
    private var collector: Job? = null
    private var cancelled = false

    init {
        start()
    }

    /** A new attempt (Try again), cancelling the current one first. */
    fun start() = begin(freshIdentity = false)

    /**
     * «Отключить и подключить заново» after [app.kinsight.core.data.PairFailure.REMOVED_BEFORE]: a new attempt with the
     * same invitation that resets this phone's identity first, so the admin sees a new device.
     */
    fun resetAndRetry() = begin(freshIdentity = true)

    private fun begin(freshIdentity: Boolean) {
        handle?.cancel()
        collector?.cancel()
        cancelled = false
        _state.value = JoinState.Searching
        collector = viewModelScope.launch {
            val h = starter.start(viewModelScope, invite, name, consent, freshIdentity)
            handle = h
            h.state.collect { _state.value = it }
        }
    }

    fun cancel() {
        if (storing()) return
        handle?.cancel()
        cancelled = true
    }

    /** The screen is shown again: an attempt stopped by [cancel] starts over. */
    fun resume() {
        if (cancelled && !storing()) start()
    }

    private fun storing() = _state.value == JoinState.Saving || _state.value == JoinState.Joined

    override fun onCleared() {
        if (!storing()) handle?.cancel()
    }
}
