package app.kinsight.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.kinsight.R

/** Spec §9.7: «Целостность», «Сводки» and «Пороги» (stage 5b); «Служебное» belongs to the sync service. */
object AlertChannels {
    const val INTEGRITY = "kinsight-integrity"
    const val SUMMARIES = "kinsight-summaries"
    const val THRESHOLDS = "kinsight-thresholds"

    fun ensure(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(INTEGRITY, context.getString(R.string.channel_integrity), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_integrity_description) },
                NotificationChannel(SUMMARIES, context.getString(R.string.channel_summaries), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.channel_summaries_description) },
                NotificationChannel(THRESHOLDS, context.getString(R.string.channel_thresholds), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_thresholds_description) },
            ),
        )
    }
}
