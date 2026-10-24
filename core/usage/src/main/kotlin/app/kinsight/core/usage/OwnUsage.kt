package app.kinsight.core.usage

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.DeviceRole

/**
 * The one rule for reading and publishing this device's own usage (spec §4.8, Play stalkerware ruling): only an
 * active CHILD member that has consented. Parents, role-less (UNSPECIFIED) devices, departed members and devices
 * without a group never do. Pure, so the app gate (SyncGraph.install) and test devices share it.
 */
fun ownUsageAllowed(membership: MembershipState?, self: DeviceId, settings: UsageSettings): Boolean =
    membership?.active?.get(self)?.isChild == true && settings.consentAtMs != null

/**
 * The parent's own screen time, kept on this phone only (own time spec 2026-10-24): an active PARENT member. A child,
 * a role-less (UNSPECIFIED) device, a departed member and a device without a group never do. It never publishes:
 * [ownUsageAllowed] stays the only rule for that. The switch itself is [UsageSettings.ownTimeLocal].
 */
fun ownTimeLocalAllowed(membership: MembershipState?, self: DeviceId): Boolean =
    membership?.active?.get(self)?.role == DeviceRole.PARENT
