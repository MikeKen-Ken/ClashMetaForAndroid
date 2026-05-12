package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.ProxyGroupRefresh
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.core.bridge.HealthCheckCallback
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.util.sendProxyGroupRefresh
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

class HealthCheckNotificationModule(service: Service) : Module<Unit>(service) {
    private val notificationManager = NotificationManagerCompat.from(service)
    
    companion object {
        const val CHANNEL_ID = "health_check_channel"
        const val NOTIFICATION_ID = 1001
        private const val MAX_CONNECT_TIMES_PREFIX = "max-connect-times\t"
        private const val PROXY_GROUP_REFRESH_PREFIX = "proxy-group-refresh\t"
        
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

    private sealed class GroupNotification {
        abstract val groupName: String

        data class HealthCheck(override val groupName: String) : GroupNotification()

        data class MaxConnectTimesTest(
            override val groupName: String,
            val proxyName: String
        ) : GroupNotification()
    }

    override suspend fun run() = coroutineScope {
        val events = Channel<GroupNotification>(Channel.UNLIMITED)
        
        // Register callback
        Bridge.nativeSubscribeHealthCheck(object : HealthCheckCallback {
            override fun onHealthCheckTriggered(groupName: String) {
                if (groupName.startsWith(PROXY_GROUP_REFRESH_PREFIX)) {
                    refreshGroup(groupName.removePrefix(PROXY_GROUP_REFRESH_PREFIX))
                    return
                }

                val notification = parseNotification(groupName)
                events.trySend(notification)
                refreshGroup(notification.groupName)
            }
        })
        
        while (true) {
            select<Unit> {
                events.onReceive { notification ->
                    showNotification(notification)
                }
            }
        }
    }

    private fun refreshGroup(groupName: String) {
        if (groupName.isBlank()) return

        ProxyGroupRefresh.notifyGroupChanged(groupName)
        service.sendProxyGroupRefresh(groupName)
    }
    
    private fun parseNotification(payload: String): GroupNotification {
        if (!payload.startsWith(MAX_CONNECT_TIMES_PREFIX)) {
            return GroupNotification.HealthCheck(payload)
        }

        val parts = payload.removePrefix(MAX_CONNECT_TIMES_PREFIX).split('\t', limit = 2)
        val groupName = parts.getOrNull(0).orEmpty()
        val proxyName = parts.getOrNull(1).orEmpty()

        return GroupNotification.MaxConnectTimesTest(groupName, proxyName)
    }

    private fun showNotification(notification: GroupNotification) {
        val (title, message) = when (notification) {
            is GroupNotification.HealthCheck -> {
                service.getString(R.string.health_check_triggered_title) to
                    service.getString(R.string.health_check_triggered_message, notification.groupName)
            }
            is GroupNotification.MaxConnectTimesTest -> {
                service.getString(R.string.max_connect_times_test_triggered_title) to
                    service.getString(
                        R.string.max_connect_times_test_triggered_message,
                        notification.groupName,
                        notification.proxyName
                    )
            }
        }
        val builtNotification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_service)
            .setColor(service.getColorCompat(R.color.color_clash))
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(message))
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
        
        notificationManager.notify(NOTIFICATION_ID, builtNotification)
    }
}
