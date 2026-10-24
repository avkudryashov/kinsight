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
 * is exhaustive, so a new kind fails to compile until it has a line. Peer values are untrusted: an interval with no
 * end (to_ms 0) or ending before it starts gets a line without a duration, an overflowing one reads as the longest,
 * and the shift's sign only picks forward or back.
 */
@Composable
fun eventText(e: EventView): String {
    val res = LocalResources.current
    // to > from yet a negative difference means it overflowed: read as the longest span.
    val span = if (e.toMs > 0 && e.toMs >= e.fromMs) formatDuration(res, (e.toMs - e.fromMs).let { if (it < 0) Long.MAX_VALUE else it }) else null

    @Composable
    fun timed(withSpan: Int, without: Int) = if (span != null) stringResource(withSpan, span) else stringResource(without)

    /** Spec §10: still off when noticed (to = from), or closed with its length. */
    @Composable
    fun noticed(withSpan: Int, open: Int) = if (e.toMs > e.fromMs) timed(withSpan, open) else stringResource(open)

    return when (e.kind) {
        StateEvent.Kind.SHARING_PAUSED -> stringResource(R.string.det_ev_paused)
        StateEvent.Kind.SHARING_RESUMED -> stringResource(if (e.firstSharing) R.string.det_ev_started else R.string.det_ev_resumed)
        StateEvent.Kind.USAGE_ACCESS_REVOKED -> stringResource(R.string.det_ev_access_off)
        StateEvent.Kind.USAGE_ACCESS_RESTORED -> stringResource(R.string.det_ev_access_on)
        StateEvent.Kind.CLOCK_CHANGED -> {
            val shift = formatDuration(res, if (e.shiftMs == Long.MIN_VALUE) Long.MAX_VALUE else abs(e.shiftMs))
            stringResource(if (e.shiftMs < 0) R.string.det_ev_clock_back else R.string.det_ev_clock_forward, shift)
        }
        StateEvent.Kind.BLUETOOTH_OFF -> timed(R.string.det_ev_bluetooth_off, R.string.det_ev_bluetooth_off_open)
        StateEvent.Kind.DATA_GAP -> timed(R.string.det_ev_gap, R.string.det_ev_gap_open)
        StateEvent.Kind.LEFT_GROUP -> stringResource(R.string.det_ev_left)
        StateEvent.Kind.SYNC_STOPPED -> timed(R.string.det_ev_sync_stopped, R.string.det_ev_sync_stopped_open)
        StateEvent.Kind.NOTIFICATION_HIDDEN -> stringResource(R.string.det_ev_notification_hidden)
        StateEvent.Kind.NOTIFICATION_SHOWN -> timed(R.string.det_ev_notification_shown, R.string.det_ev_notification_shown_open)
        StateEvent.Kind.NEARBY_PERMISSION_OFF -> timed(R.string.det_ev_nearby_off, R.string.det_ev_nearby_off_open)
        StateEvent.Kind.APP_FORCE_STOPPED -> timed(R.string.det_ev_force_stopped, R.string.det_ev_force_stopped_open)
        StateEvent.Kind.ZONE_ENTER -> e.zoneName?.let { stringResource(R.string.det_ev_zone_enter, it) } ?: stringResource(R.string.det_ev_zone_enter_unknown)
        StateEvent.Kind.ZONE_EXIT -> e.zoneName?.let { stringResource(R.string.det_ev_zone_exit, it) } ?: stringResource(R.string.det_ev_zone_exit_unknown)
        StateEvent.Kind.LOCATION_PERMISSION_OFF -> noticed(R.string.det_ev_location_off, R.string.det_ev_location_off_open)
        StateEvent.Kind.LOCATION_DISABLED -> noticed(R.string.det_ev_location_disabled, R.string.det_ev_location_disabled_open)
        // Open: still off when noticed; closed: the service is back (the interval's length is on the alert).
        StateEvent.Kind.BLOCKING_OFF -> stringResource(if (e.toMs > e.fromMs) R.string.det_ev_blocking_on else R.string.det_ev_blocking_off)
        StateEvent.Kind.BLOCK_OVERRIDDEN -> stringResource(R.string.det_ev_block_overridden, e.packageName.orEmpty())
        StateEvent.Kind.KIND_UNSPECIFIED -> stringResource(R.string.det_ev_unknown)
    }
}
