package app.kinsight

import kotlinx.serialization.Serializable

// Tabs and screens inside the main root.
@Serializable object OverviewDest

@Serializable object GroupDest

@Serializable object SettingsDest

@Serializable data class DeviceDest(val id: String)

/** The detail range on screen: [period] a Period name, [start] and [end] ISO dates. */
@Serializable data class AllAppsDest(val id: String, val period: String, val start: String, val end: String)

/**
 * One app of device [id] (`device/{id}/app/{package}`), opening on the detail's range: [period] a Period name, [start]
 * and [end] ISO dates.
 */
@Serializable data class AppDest(val id: String, val packageName: String, val period: String, val start: String, val end: String)

/** The daily limits of child [id] (spec §9.7, stage 5b), a parent's phone only. */
@Serializable data class ThresholdsDest(val id: String)

@Serializable data class InviteDest(val role: String)

@Serializable object TransparencyDest

@Serializable object ConsentDest

@Serializable object BatteryDest

/** The root screens: one back stack entry each, so every root gets fresh view models (a second first run starts clean). */
@Serializable data class RootDest(val start: String)
