package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.service.R

/** System notification emitted only after a runtime configuration update succeeds. */
object RuntimeConfigNotification {
    private const val channelId = "runtime_config_channel"
    private const val notificationId = 1002

    fun createChannel(service: Service) {
        NotificationManagerCompat.from(service).createNotificationChannel(
            NotificationChannelCompat.Builder(
                channelId,
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
            ).setName(service.getString(R.string.runtime_config_notification_channel))
                .setDescription(service.getString(R.string.runtime_config_notification_channel_description))
                .build(),
        )
    }

    fun notifyUpdated(context: Context) {
        val message = context.getString(R.string.runtime_config_updated_message)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_logo_service)
            .setColor(context.getColorCompat(R.color.color_clash))
            .setContentTitle(context.getString(R.string.runtime_config_updated_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notificationId,
                    Intent().setComponent(Components.MAIN_ACTIVITY)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
