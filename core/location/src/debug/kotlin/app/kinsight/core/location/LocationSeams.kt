package app.kinsight.core.location

/**
 * Debug builds only: switches for the emulator's device steps (task-6 report steps 2 and 4), set by [LocationDebug] from
 * adb. Off by default. The release source set has a twin with the same names as constants that are always off, so a
 * release build carries no switch and the compiler drops the branches.
 */
internal object LocationSeams {
    /** The caller's startForegroundService throws ForegroundServiceStartNotAllowedException, as a background start may. */
    @Volatile var refuseStart: Boolean = false

    /** The service's startForeground throws SecurityException, as Android 14+ may for the location type. */
    @Volatile var refuseForeground: Boolean = false

    /** An outside tick waits this long before its work, past the receiver's 30 s release and before armTick. */
    @Volatile var tickDelayMs: Long = 0L
}
