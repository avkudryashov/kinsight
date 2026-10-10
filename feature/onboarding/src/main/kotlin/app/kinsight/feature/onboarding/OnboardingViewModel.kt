package app.kinsight.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.AppPrefs
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.Role
import app.kinsight.core.data.toDeviceRole
import app.kinsight.core.model.MembershipLimits
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.pairing.InvalidInviteException
import app.kinsight.core.sync.pairing.Invite
import app.kinsight.core.sync.pairing.UnsupportedInviteVersionException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class OnboardingMessage {
    INVALID_INVITE,
    UNSUPPORTED_INVITE,
    NAME_INVALID,
    CREATE_FAILED,
    NEARBY_REQUIRED,
    NEARBY_BLOCKED,
    SCANNER_UNAVAILABLE,
}

/** What onboarding needs from the group (GroupRepository and the usage settings in the app). */
interface OnboardingGroup {
    val state: Flow<GroupState>

    suspend fun createGroup(name: String)

    /** A consent is on record (the pairing screen records it for a consenting child). */
    suspend fun consentRecorded(): Boolean
}

/**
 * The first-run state machine. Survives a process kill two ways: [saved] keeps step, path, name, invite text and
 * consent (a kill before pairing returns to the same step), and a group that already exists on start resumes after
 * PAIR with the role from the group and the recorded consent (or at DONE for a created group), so the phone never
 * lands back on WELCOME where neither path works any more.
 */
class OnboardingViewModel(
    private val prefs: AppPrefs,
    private val group: OnboardingGroup,
    defaultName: String,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val fallbackName = DeviceNames.clean(defaultName).takeIf(DeviceNames::ok).orEmpty()
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private val _message = MutableStateFlow<OnboardingMessage?>(null)
    val message: StateFlow<OnboardingMessage?> = _message.asStateFlow()

    private var finishing = false

    /** Refreshed by the route on every resume and after each permission result. */
    @Volatile
    var env: Env = Env.NOTHING

    /** Done once the saved name and the group on disk have been applied. */
    internal val restoring: Job = viewModelScope.launch {
        if (saved.get<String>(KEY_NAME) == null) {
            val stored = runCatching { prefs.current().deviceName }.getOrNull()
            if (stored != null && DeviceNames.ok(stored)) set { it.copy(name = stored) }
        }
        val g = group.state.first { it !is GroupState.Loading }
        if (g is GroupState.Ready) resumeIn(g, runCatching { group.consentRecorded() }.getOrDefault(false))
    }

    private fun restore(): OnboardingState {
        val name = saved.get<String>(KEY_NAME)?.takeIf(DeviceNames::ok) ?: fallbackName
        val invite = saved.get<String>(KEY_INVITE)?.let { runCatching { Invite.parse(it) }.getOrNull() }
        val restored = OnboardingState(
            step = saved.get<String>(KEY_STEP)?.let { s -> Step.entries.firstOrNull { it.name == s } } ?: Step.WELCOME,
            path = saved.get<String>(KEY_PATH)?.let { p -> Path.entries.firstOrNull { it.name == p } },
            name = name,
            invite = invite,
            consent = saved.get<Boolean>(KEY_CONSENT) ?: false,
            joinedRole = saved.get<Int>(KEY_ROLE)?.let(DeviceRole::fromValue),
        )
        return when {
            restored.step !in OnboardingFlow.order(restored) -> restored.copy(step = if (restored.path == null) Step.WELCOME else Step.NAME)
            // The invite was lost: the steps that need it start over from the name.
            restored.path == Path.JOIN && restored.role == null && restored.step.ordinal > Step.SCAN.ordinal -> restored.copy(step = Step.NAME)
            else -> restored
        }
    }

    private fun set(transform: (OnboardingState) -> OnboardingState) {
        val s = transform(_state.value)
        _state.value = s
        saved[KEY_STEP] = s.step.name
        saved[KEY_PATH] = s.path?.name
        saved[KEY_NAME] = s.name
        saved[KEY_INVITE] = s.invite?.toUri()
        saved[KEY_CONSENT] = s.consent
        saved[KEY_ROLE] = s.joinedRole?.value
    }

    private fun resumeIn(g: GroupState.Ready, consentRecorded: Boolean) = set { s ->
        val created = s.path == Path.CREATE ||
            (s.path == null && g.self.isAdmin && g.self.role == Role.PARENT && g.members.size == 1)
        if (created) {
            val c = s.copy(path = Path.CREATE, invite = null, consent = false)
            return@set if (c.step == Step.BATTERY || c.step == Step.DONE) c else c.copy(step = Step.DONE)
        }
        val role = g.self.role.toDeviceRole()
        val joined = s.copy(path = Path.JOIN, joinedRole = role, consent = role == DeviceRole.CHILD && consentRecorded)
        val order = OnboardingFlow.order(joined)
        if (order.indexOf(joined.step) > order.indexOf(Step.PAIR)) joined
        else joined.copy(step = OnboardingFlow.next(joined.copy(step = Step.PAIR), env))
    }

    /** A new choice drops whatever an earlier path left: create never carries an invite or a consent. */
    fun choose(path: Path) = set {
        if (path == Path.CREATE) it.copy(path = path, step = Step.NAME, invite = null, consent = false, joinedRole = null)
        else it.copy(path = path, step = Step.NAME)
    }

    fun setName(name: String) = set { it.copy(name = name) }

    fun show(message: OnboardingMessage) {
        _message.value = message
    }

    /**
     * From the scanner or a deep link; an invalid text is reported, never fatal. A QR scanned in the middle of the
     * create path means the user wants to join instead: it restarts at the name. Once paired, invites are ignored.
     */
    fun onInviteText(text: String): Boolean {
        if (OnboardingFlow.pastPairing(_state.value) || _state.value.step == Step.PAIR) return false
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
        set {
            val step = when {
                it.step == Step.WELCOME -> Step.NAME
                it.path == Path.CREATE && it.step != Step.NAME -> Step.NAME
                it.step == Step.CONSENT || it.step == Step.PARENT_INFO -> Step.SCAN
                else -> it.step
            }
            it.copy(path = Path.JOIN, invite = invite, consent = false, step = step)
        }
        if (_state.value.step == Step.SCAN) next()
        return true
    }

    /** Only a child invite shows CONSENT (OnboardingFlow), so consent is never recorded for an adult. */
    fun consent(agreed: Boolean) {
        set { it.copy(consent = agreed && it.role == DeviceRole.CHILD) }
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
            set { it.copy(name = name) }
            viewModelScope.launch { runCatching { prefs.setDeviceName(name) } }
        }
        _message.value = null
        set { it.copy(step = OnboardingFlow.next(it, env)) }
    }

    fun back() {
        _message.value = null
        set { it.copy(step = OnboardingFlow.previous(it, env)) }
    }

    /**
     * DONE: the create path makes the group now (spec §9.4 «Готово»), unless a group already exists (a kill between
     * creating and finishing), then the app opens the main screen.
     */
    fun finish(onFinished: () -> Unit) {
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            val s = _state.value
            try {
                if (s.path == Path.CREATE && group.state.first { it !is GroupState.Loading } !is GroupState.Ready) {
                    group.createGroup(s.name.trim())
                }
                prefs.setOnboardingDone(true)
                onFinished()
            } catch (e: Exception) {
                _message.value = OnboardingMessage.CREATE_FAILED
            } finally {
                finishing = false
            }
        }
    }

    private companion object {
        const val KEY_STEP = "onb_step"
        const val KEY_PATH = "onb_path"
        const val KEY_NAME = "onb_name"
        const val KEY_INVITE = "onb_invite"
        const val KEY_CONSENT = "onb_consent"
        const val KEY_ROLE = "onb_joined_role"
    }
}

/** A default name (Build.MODEL) cut to the limit and stripped of what [DeviceNames.ok] refuses. */
internal fun DeviceNames.clean(name: String): String = buildString {
    name.codePoints().filter { !unsafe(it) }.limit(MembershipLimits.MAX_NAME_CODE_POINTS.toLong()).forEach(::appendCodePoint)
}.trim()
