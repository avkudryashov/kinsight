package app.kinsight.feature.onboarding

import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.pairing.Invite

enum class Path { CREATE, JOIN }

enum class Step { WELCOME, NAME, PARENT_INFO, NEARBY, SCAN, CONSENT, PAIR, USAGE_ACCESS, NOTIFICATIONS, BATTERY, DONE }

/** What is already granted: those permission steps are skipped (research §6.2: one disclosure right before each request). */
data class Env(val nearbyGranted: Boolean, val notificationsGranted: Boolean, val usageAccessGranted: Boolean)

data class OnboardingState(
    val step: Step = Step.WELCOME,
    val path: Path? = null,
    val name: String = "",
    val invite: Invite? = null,
    val consent: Boolean = false,
)

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
            when (s.invite?.role) {
                DeviceRole.CHILD -> add(Step.CONSENT)
                DeviceRole.PARENT -> add(Step.PARENT_INFO)
                else -> Unit
            }
            add(Step.PAIR)
            if (s.invite?.role == DeviceRole.CHILD && s.consent) add(Step.USAGE_ACCESS)
            addAll(listOf(Step.NOTIFICATIONS, Step.BATTERY, Step.DONE))
        }
    }

    private fun skipped(step: Step, s: OnboardingState, env: Env): Boolean = when (step) {
        Step.NEARBY -> env.nearbyGranted
        Step.NOTIFICATIONS -> env.notificationsGranted
        Step.USAGE_ACCESS -> env.usageAccessGranted
        Step.SCAN -> s.invite != null
        else -> false
    }

    fun next(s: OnboardingState, env: Env): Step {
        val order = order(s)
        return order.drop(order.indexOf(s.step) + 1).firstOrNull { !skipped(it, s, env) } ?: Step.DONE
    }

    fun previous(s: OnboardingState): Step {
        val order = order(s)
        return order.getOrNull(order.indexOf(s.step) - 1) ?: Step.WELCOME
    }
}
