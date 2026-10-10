package app.kinsight.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.AppPrefs
import app.kinsight.core.data.GroupRepository
import app.kinsight.core.model.MembershipLimits
import app.kinsight.core.sync.pairing.InvalidInviteException
import app.kinsight.core.sync.pairing.Invite
import app.kinsight.core.sync.pairing.UnsupportedInviteVersionException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingMessage { INVALID_INVITE, UNSUPPORTED_INVITE, NAME_INVALID, CREATE_FAILED, NEARBY_REQUIRED, SCANNER_UNAVAILABLE }

class OnboardingViewModel(
    private val prefs: AppPrefs,
    private val group: GroupRepository,
    defaultName: String,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState(name = DeviceNames.clean(defaultName)))
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private val _message = MutableStateFlow<OnboardingMessage?>(null)
    val message: StateFlow<OnboardingMessage?> = _message.asStateFlow()

    private var finishing = false

    /** Refreshed by the route on every resume and after each permission result. */
    @Volatile
    var env: Env = Env(nearbyGranted = false, notificationsGranted = false, usageAccessGranted = false)

    init {
        viewModelScope.launch { prefs.current().deviceName?.let { n -> _state.update { it.copy(name = n) } } }
    }

    fun choose(path: Path) = _state.update { it.copy(path = path, step = Step.NAME) }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun show(message: OnboardingMessage) {
        _message.value = message
    }

    /**
     * From the scanner or a deep link; an invalid text is reported, never fatal. A QR scanned in the middle of the
     * create path means the user wants to join instead: it restarts at the name.
     */
    fun onInviteText(text: String): Boolean {
        val invite = try {
            Invite.parse(text)
        } catch (e: UnsupportedInviteVersionException) {
            _message.value = OnboardingMessage.UNSUPPORTED_INVITE
            return false
        } catch (e: InvalidInviteException) {
            _message.value = OnboardingMessage.INVALID_INVITE
            return false
        }
        _message.value = null
        _state.update {
            val step = when {
                it.step == Step.WELCOME -> Step.NAME
                it.path == Path.CREATE && it.step != Step.NAME -> Step.NAME
                else -> it.step
            }
            it.copy(path = Path.JOIN, invite = invite, consent = false, step = step)
        }
        if (_state.value.step == Step.SCAN) next()
        return true
    }

    /** Only a child invite shows CONSENT (OnboardingFlow), so consent is never recorded for an adult. */
    fun consent(agreed: Boolean) {
        _state.update { it.copy(consent = agreed) }
        next()
    }

    fun next() {
        val s = _state.value
        if (s.step == Step.NAME) {
            val name = s.name.trim()
            if (!DeviceNames.ok(name)) {
                _message.value = OnboardingMessage.NAME_INVALID
                return
            }
            _state.update { it.copy(name = name) }
            viewModelScope.launch { prefs.setDeviceName(name) }
        }
        _message.value = null
        _state.update { it.copy(step = OnboardingFlow.next(it, env)) }
    }

    fun back() {
        _message.value = null
        _state.update { it.copy(step = OnboardingFlow.previous(it)) }
    }

    /** DONE: the create path makes the group now (spec §9.4 «Готово»), then the app opens the main screen. */
    fun finish(onFinished: () -> Unit) {
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            val s = _state.value
            try {
                if (s.path == Path.CREATE) group.createGroup(s.name.trim())
                prefs.setOnboardingDone(true)
                onFinished()
            } catch (e: Exception) {
                _message.value = OnboardingMessage.CREATE_FAILED
            } finally {
                finishing = false
            }
        }
    }
}

/** A default name (Build.MODEL) cut to the limit and stripped of what [DeviceNames.ok] refuses. */
internal fun DeviceNames.clean(name: String): String = buildString {
    name.codePoints().filter { !unsafe(it) }.limit(MembershipLimits.MAX_NAME_CODE_POINTS.toLong()).forEach(::appendCodePoint)
}.trim()
