package app.kinsight.core.data

/** What follows a location state check (AppGraph.checkLocationState). */
internal object LocationStateFollow {
    /**
     * A recorded event is published and uploaded at once. Emulator finding: it also means access or the system switch
     * changed (access given back in the system settings above all), so the alerts and the cadence follow at once through
     * [refresh] (LocationGraph.refresh with rearm: launched, never awaited, ruling L6). Nothing happens otherwise.
     */
    suspend fun after(recorded: Boolean, refresh: () -> Unit, publish: suspend () -> Unit, upload: () -> Unit) {
        if (!recorded) return
        refresh()
        publish()
        upload()
    }
}
