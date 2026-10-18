package app.kinsight.core.location

/** Release twin of the debug build's switches: always off, nothing can set them. */
internal object LocationSeams {
    const val refuseStart: Boolean = false
    const val refuseForeground: Boolean = false
    const val tickDelayMs: Long = 0L
}
