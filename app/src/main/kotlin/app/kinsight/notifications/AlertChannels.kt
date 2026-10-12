package app.kinsight.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.kinsight.R
import app.kinsight.core.data.AlertLevel
import app.kinsight.core.data.Role

/**
 * Spec §9.7 after the 2026-10-11 audit (U4, U7): a parent's phone has «Важное», «Прочие события» (quiet), «Сводки»
 * and «Лимиты»; a child's phone only «Кто видит мои данные». «Служебное» belongs to the sync service.
 */
object AlertChannels {
    const val IMPORTANT = "kinsight-important"
    const val EVENTS = "kinsight-events"
    const val TRANSPARENCY = "kinsight-transparency"
    const val SUMMARIES = "kinsight-summaries"
    const val THRESHOLDS = "kinsight-thresholds"

    /** 0.7.x: every integrity alert on one sounding channel; deleted so its setting cannot linger. */
    const val LEGACY_INTEGRITY = "kinsight-integrity"

    private val ALL = listOf(IMPORTANT, EVENTS, TRANSPARENCY, SUMMARIES, THRESHOLDS, LEGACY_INTEGRITY)

    fun of(level: AlertLevel): String = when (level) {
        AlertLevel.IMPORTANT -> IMPORTANT
        AlertLevel.MINOR -> EVENTS
        AlertLevel.TRANSPARENCY -> TRANSPARENCY
        AlertLevel.SUMMARY -> SUMMARIES
        AlertLevel.THRESHOLD -> THRESHOLDS
    }

    /**
     * Creates [role]'s channels and deletes every other alert channel (the legacy one, a former role's). True when the
     * 0.7.x channel was there: deleting it cancelled its notifications, so the caller re-posts the open problems.
     */
    fun ensure(context: Context, role: Role): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        val hadLegacy = nm.getNotificationChannel(LEGACY_INTEGRITY) != null
        val wanted = when (role) {
            Role.PARENT -> listOf(
                channel(context, IMPORTANT, R.string.channel_important, R.string.channel_important_description, NotificationManager.IMPORTANCE_DEFAULT),
                channel(context, EVENTS, R.string.channel_events, R.string.channel_events_description, NotificationManager.IMPORTANCE_LOW),
                channel(context, SUMMARIES, R.string.channel_summaries, R.string.channel_summaries_description, NotificationManager.IMPORTANCE_LOW),
                channel(context, THRESHOLDS, R.string.channel_thresholds, R.string.channel_thresholds_description, NotificationManager.IMPORTANCE_DEFAULT),
            )
            Role.CHILD -> listOf(
                channel(context, TRANSPARENCY, R.string.channel_transparency, R.string.channel_transparency_description, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        nm.createNotificationChannels(wanted)
        val keep = wanted.map { it.id }.toSet()
        ALL.filter { it !in keep }.forEach { nm.deleteNotificationChannel(it) }
        return hadLegacy
    }

    private fun channel(context: Context, id: String, name: Int, description: Int, importance: Int) =
        NotificationChannel(id, context.getString(name), importance).apply { this.description = context.getString(description) }
}
