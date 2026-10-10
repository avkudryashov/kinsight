package app.kinsight.feature.devicedetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.kinsight.core.data.EventView
import app.kinsight.core.designsystem.component.formatDuration
import app.kinsight.core.model.proto.StateEvent
import kotlin.math.abs

/**
 * One line per StateEvent (spec §4.3), with the interval or the clock shift where the event carries one. The `when`
 * is exhaustive, so a new kind fails to compile until it has a line. Peer values are untrusted: a reversed interval
 * reads as zero, an overflowing one as the longest, and the shift's sign only picks forward or back.
 */
@Composable
fun eventText(e: EventView): String {
    val res = LocalResources.current
    // to > from yet a negative difference means it overflowed: read as the longest span.
    val span = formatDuration(res, if (e.toMs > e.fromMs) (e.toMs - e.fromMs).let { if (it < 0) Long.MAX_VALUE else it } else 0)
    return when (e.kind) {
        StateEvent.Kind.SHARING_PAUSED -> stringResource(R.string.det_ev_paused)
        StateEvent.Kind.SHARING_RESUMED -> stringResource(R.string.det_ev_resumed)
        StateEvent.Kind.USAGE_ACCESS_REVOKED -> stringResource(R.string.det_ev_access_off)
        StateEvent.Kind.USAGE_ACCESS_RESTORED -> stringResource(R.string.det_ev_access_on)
        StateEvent.Kind.CLOCK_CHANGED -> {
            val shift = formatDuration(res, if (e.shiftMs == Long.MIN_VALUE) Long.MAX_VALUE else abs(e.shiftMs))
            stringResource(if (e.shiftMs < 0) R.string.det_ev_clock_back else R.string.det_ev_clock_forward, shift)
        }
        StateEvent.Kind.BLUETOOTH_OFF -> stringResource(R.string.det_ev_bluetooth_off, span)
        StateEvent.Kind.DATA_GAP -> stringResource(R.string.det_ev_gap, span)
        StateEvent.Kind.LEFT_GROUP -> stringResource(R.string.det_ev_left)
        StateEvent.Kind.SYNC_STOPPED -> stringResource(R.string.det_ev_sync_stopped, span)
        StateEvent.Kind.NOTIFICATION_HIDDEN -> stringResource(R.string.det_ev_notification_hidden)
        StateEvent.Kind.NOTIFICATION_SHOWN -> stringResource(R.string.det_ev_notification_shown, span)
        StateEvent.Kind.NEARBY_PERMISSION_OFF -> stringResource(R.string.det_ev_nearby_off, span)
        StateEvent.Kind.APP_FORCE_STOPPED -> stringResource(R.string.det_ev_force_stopped, span)
        StateEvent.Kind.KIND_UNSPECIFIED -> stringResource(R.string.det_ev_unknown)
    }
}
