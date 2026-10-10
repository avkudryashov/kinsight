package app.kinsight.feature.onboarding

import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.pairing.Invite

enum class Path { CREATE, JOIN }

enum class Step { WELCOME, NAME, PARENT_INFO, NEARBY, SCAN, CONSENT, PAIR, USAGE_ACCESS, NOTIFICATIONS, BATTERY, DONE }

/** What is already granted: those permission steps are skipped (research §6.2: one disclosure right before each request). */
data class Env(val nearbyGranted: Boolean, val notificationsGranted: Boolean, val usageAccessGranted: Boolean) {
    companion object {
        val NOTHING = Env(nearbyGranted = false, notificationsGranted = false, usageAccessGranted = false)
    }
}

data class OnboardingState(
    val step: Step = Step.WELCOME,
    val path: Path? = null,
    val name: String = "",
    val invite: Invite? = null,
    val consent: Boolean = false,
    /** This phone's role once it is in the group: the order then no longer needs the invite (resume after a kill). */
    val joinedRole: DeviceRole? = null,
) {
    /** The role this phone joins (or joined) with: the invite's, or the group's after pairing. */
    val role: DeviceRole? get() = joinedRole ?: invite?.role
}

/**
 * Spec §9.4 with the Play disclosure rules (research §6.2). Create: name, what a parent's phone does, Nearby,
 * notifications, battery. Join: name, Nearby (needed to pair), the QR (skipped with a deep link), consent for a child
 * or the parent text, pairing, then usage access (a consenting child only), notifications, battery. Only a child invite
 * ever reaches CONSENT or USAGE_ACCESS: adults never share (Play Stalkerware ruling).
 */
object OnboardingFlow {
    fun order(s: OnboardingState): List<Step> = when (s.path) {
        null -> listOf(Step.WELCOME, Step.NAME)
        Path.CREATE -> listOf(Step.WELCOME, Step.NAME, Step.PARENT_INFO, Step.NEARBY, Step.NOTIFICATIONS, Step.BATTERY, Step.DONE)
        Path.JOIN -> buildList {
            addAll(listOf(Step.WELCOME, Step.NAME, Step.NEARBY, Step.SCAN))
            when (s.role) {
                DeviceRole.CHILD -> add(Step.CONSENT)
                DeviceRole.PARENT -> add(Step.PARENT_INFO)
                else -> Unit
            }
            add(Step.PAIR)
            if (s.role == DeviceRole.CHILD && s.consent) add(Step.USAGE_ACCESS)
            addAll(listOf(Step.NOTIFICATIONS, Step.BATTERY, Step.DONE))
        }
    }

    private fun skipped(step: Step, s: OnboardingState, env: Env): Boolean = when (step) {
        Step.NEARBY -> env.nearbyGranted
        Step.NOTIFICATIONS -> env.notificationsGranted
        Step.USAGE_ACCESS -> env.usageAccessGranted
        Step.SCAN -> s.invite != null || s.joinedRole != null
        else -> false
    }

    /** The next step not skipped; a step outside the order (a stale state) stays where it is. */
    fun next(s: OnboardingState, env: Env): Step {
        val order = order(s)
        val i = order.indexOf(s.step)
        if (i < 0) return s.step
        return order.drop(i + 1).firstOrNull { !skipped(it, s, env) } ?: Step.DONE
    }

    /**
     * The previous step not skipped. Pairing cannot be undone by going back: from the first step after PAIR, back
     * stays there.
     */
    fun previous(s: OnboardingState, env: Env = Env.NOTHING): Step {
        val order = order(s)
        val i = order.indexOf(s.step)
        if (i < 0) return s.step
        val floor = order.indexOf(Step.PAIR).let { if (it in 0 until i) it + 1 else 0 }
        return order.subList(floor, i).lastOrNull { !skipped(it, s, env) } ?: if (floor > 0) s.step else Step.WELCOME
    }

    /** True once the phone is in the group or the step is past pairing: no new invite applies any more. */
    fun pastPairing(s: OnboardingState): Boolean {
        if (s.joinedRole != null) return true
        val order = order(s)
        val pair = order.indexOf(Step.PAIR)
        return pair >= 0 && order.indexOf(s.step) > pair
    }
}
