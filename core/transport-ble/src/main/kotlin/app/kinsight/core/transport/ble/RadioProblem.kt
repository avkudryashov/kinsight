package app.kinsight.core.transport.ble

/**
 * A standing radio problem, typed so the UI picks its words from [kind] and [retrying] (never by matching text).
 * [code] is the platform's error or status code, [cause] a short technical reason (an exception class, "refused").
 * [detail] is the technical line for diagnostics and support only.
 */
data class RadioProblem(
    val kind: Kind,
    val retrying: Boolean = false,
    val code: Int? = null,
    val cause: String? = null,
) {
    enum class Kind {
        /** The advert did not start (AdvertiseCallback failure or a throwing start). */
        ADVERTISE_FAILED,

        /** The running scan reported an error (ScanReceiver EXTRA_ERROR_CODE). */
        SCAN_FAILED,

        /** startScan did not accept the scan (its return code). */
        SCAN_START_FAILED,

        /** The pairing GATT service (the PSM guests read) was refused, failed or not confirmed. */
        PAIRING_SERVICE_FAILED,

        /** The pairing L2CAP listener could not be reopened. */
        PAIRING_LISTENER_FAILED,
    }

    val detail: String
        get() = buildString {
            append(
                when (kind) {
                    Kind.ADVERTISE_FAILED -> "advertising failed"
                    Kind.SCAN_FAILED -> "scan failed"
                    Kind.SCAN_START_FAILED -> "scan start failed"
                    Kind.PAIRING_SERVICE_FAILED -> "pairing GATT service failed"
                    Kind.PAIRING_LISTENER_FAILED -> "pairing listener lost"
                },
            )
            code?.let {
                append(
                    when (kind) {
                        Kind.SCAN_FAILED -> " (error $it)"
                        Kind.SCAN_START_FAILED -> " (rc $it)"
                        else -> " ($it)"
                    },
                )
            }
            cause?.let { append(": ").append(it) }
            if (retrying) append(", retrying")
        }

    override fun toString(): String = detail

    companion object {
        /** The diagnostics line for every standing problem, or null when there is none. */
        fun detailOf(problems: List<RadioProblem>): String? = problems.joinToString("; ") { it.detail }.ifEmpty { null }
    }
}
