package app.kinsight

import kotlinx.serialization.Serializable

// Tabs (UI refresh §2) and screens inside the main root.
@Serializable object OverviewDest

/** The Карта tab (UI refresh §5), a parent's phone only. */
@Serializable object MapDest

/** The «Ещё» tab (UI refresh §2). */
@Serializable object MoreDest

/** «Ещё → Семья»: the family group screen (the former Group tab). */
@Serializable object GroupDest

/** «Ещё → Синхронизация»: the former Settings tab. */
@Serializable object SettingsDest

/** «Ещё → Лимиты и уведомления». */
@Serializable object LimitsDest

/** «Ещё → О приложении». */
@Serializable object AboutDest

/** The child's setup steps still missing (UI refresh §6): after a later consent, or from «Ещё». */
@Serializable object ChildSetupDest

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

/** The family's safe zones (spec §8.1): a parent edits them, a child's phone lists them. */
@Serializable object ZonesDest

/** [transfer]: the storage owner's invite of its own new phone (spec §7.3), always for a parent. */
@Serializable data class InviteDest(val role: String, val transfer: Boolean = false)

@Serializable object TransparencyDest

@Serializable object ConsentDest

@Serializable object BatteryDest

/** The root screens: one back stack entry each, so every root gets fresh view models (a second first run starts clean). */
@Serializable data class RootDest(val start: String)
