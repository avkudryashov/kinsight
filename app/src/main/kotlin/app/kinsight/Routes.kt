package app.kinsight

import kotlinx.serialization.Serializable

// Tabs and screens inside the main root.
@Serializable object OverviewDest

@Serializable object GroupDest

@Serializable object SettingsDest

@Serializable data class DeviceDest(val id: String)

@Serializable data class AllAppsDest(val id: String, val period: String)

@Serializable data class InviteDest(val role: String)

@Serializable object TransparencyDest

@Serializable object ConsentDest

@Serializable object BatteryDest

/** The root screens: one back stack entry each, so every root gets fresh view models (a second first run starts clean). */
@Serializable data class RootDest(val start: String)
