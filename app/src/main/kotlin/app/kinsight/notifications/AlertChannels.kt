package app.kinsight.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.kinsight.R

/** Spec §9.7: «Целостность» and «Сводки»; «Служебное» belongs to the sync service. */
object AlertChannels {
    const val INTEGRITY = "kinsight-integrity"
    const val SUMMARIES = "kinsight-summaries"

    fun ensure(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(INTEGRITY, context.getString(R.string.channel_integrity), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_integrity_description) },
                NotificationChannel(SUMMARIES, context.getString(R.string.channel_summaries), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.channel_summaries_description) },
            ),
        )
    }
}
