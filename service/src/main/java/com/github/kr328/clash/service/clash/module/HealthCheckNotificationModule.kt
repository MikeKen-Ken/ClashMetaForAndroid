package com.github.kr328.clash.service.clash.module

import android.app.Service
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.core.bridge.HealthCheckCallback
import com.github.kr328.clash.service.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import android.content.Intent
import android.app.PendingIntent

class HealthCheckNotificationModule(service: Service) : Module<Unit>(service) {
    private val notificationManager = NotificationManagerCompat.from(service)
    
    companion object {
        const val CHANNEL_ID = "health_check_channel"
        const val NOTIFICATION_ID = 1001
        
        fun createNotificationChannel(service: Service) {
            NotificationManagerCompat.from(service).createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(service.getString(R.string.health_check_notification_channel))
                    .setDescription(service.getString(R.string.health_check_notification_channel_description))
                    .build()
            )
        }
    }

    override suspend fun run() = coroutineScope {
        val events = Channel<String>(Channel.UNLIMITED)
        
        // Register callback
        Bridge.nativeSubscribeHealthCheck(object : HealthCheckCallback {
            override fun onHealthCheckTriggered(groupName: String) {
                events.trySend(groupName)
            }
        })
        
        while (true) {
            select<Unit> {
                events.onReceive { groupName ->
                    showNotification(groupName)
                }
            }
        }
    }
    
    private fun showNotification(groupName: String) {
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_service)
            .setColor(service.getColorCompat(com.github.kr328.clash.design.R.color.color_clash))
            .setContentTitle(service.getString(R.string.health_check_triggered_title))
            .setContentText(service.getString(R.string.health_check_triggered_message, groupName))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(service.getString(R.string.health_check_triggered_message, groupName)))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    NOTIFICATION_ID,
                    Intent().setComponent(Components.MAIN_ACTIVITY)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                )
            )
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
