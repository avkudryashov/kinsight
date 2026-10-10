package app.kinsight.core.usage

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.MembershipState

/**
 * The one rule for reading and publishing this device's own usage (spec §4.8, Play stalkerware ruling): only an
 * active CHILD member that has consented. Parents, role-less (UNSPECIFIED) devices, departed members and devices
 * without a group never do. Pure, so the app gate (SyncGraph.install) and test devices share it.
 */
fun ownUsageAllowed(membership: MembershipState?, self: DeviceId, settings: UsageSettings): Boolean =
    membership?.active?.get(self)?.isChild == true && settings.consentAtMs != null
