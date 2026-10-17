package app.kinsight.core.transport.ble

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.MembershipState
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.usage.UsageSettings

object SyncPolicies {
    /**
     * A CHILD takes no foreign entries (spec §4.8: its data goes to the parents); it receives membership and, from
     * protocol 3, the parents' share (SyncPolicy.FROM_PARENTS_TO_ALL: PIN, safe zones, storage access). Everyone else
     * takes all. Unknown membership keeps the 3c default (show others). Retention from the settings.
     */
    fun forDevice(membership: MembershipState?, self: DeviceId, settings: UsageSettings): SyncPolicy = SyncPolicy(
        self = self,
        showOthers = membership?.active?.get(self)?.isChild != true,
        detailRetentionDays = settings.detailRetentionDays.toLong(),
        summaryRetentionDays = settings.summaryRetentionDays.toLong(),
    )
}
