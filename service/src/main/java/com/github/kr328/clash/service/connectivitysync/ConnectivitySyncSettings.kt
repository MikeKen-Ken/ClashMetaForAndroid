package com.github.kr328.clash.service.connectivitysync

import android.content.Context
import com.github.kr328.clash.common.secret.SecretString
import com.github.kr328.clash.service.store.ServiceStore

internal data class ConnectivitySyncSettings(
    val url: String,
    val username: String,
    val password: String,
    val intervalHours: Int,
) {
    companion object {
        fun load(context: Context): ConnectivitySyncSettings {
            val store = ServiceStore(context)
            if (!store.connectivitySyncSettingsReady) {
                migrateFromUiPreferences(context, store)
                store.connectivitySyncSettingsReady = true
            }
            return from(store)
        }

        fun from(store: ServiceStore): ConnectivitySyncSettings = ConnectivitySyncSettings(
            url = store.connectivityWebdavUrl,
            username = store.connectivityWebdavUsername,
            password = store.connectivityWebdavPassword,
            intervalHours = store.connectivitySyncIntervalHours.coerceIn(1, 168),
        )

        @Suppress("DEPRECATION")
        private fun migrateFromUiPreferences(context: Context, store: ServiceStore) {
            if (store.connectivityWebdavUrl.isNotBlank()) return
            val prefs = context.applicationContext.getSharedPreferences(
                "ui",
                Context.MODE_MULTI_PROCESS,
            )
            store.connectivityWebdavUrl = prefs.getString("webdav_url", "").orEmpty()
            store.connectivityWebdavUsername = prefs.getString("webdav_username", "").orEmpty()
            store.connectivityWebdavPasswordStored = prefs.getString("webdav_password", "").orEmpty()
            val hours = prefs.getInt("connectivity_sync_interval_hours", 24)
            if (hours > 0) store.connectivitySyncIntervalHours = hours
        }
    }
}

fun ServiceStore.publishConnectivitySyncSettings(
    url: String,
    username: String,
    password: String,
    intervalHours: Int,
) {
    connectivityWebdavUrl = url
    connectivityWebdavUsername = username
    connectivityWebdavPasswordStored = SecretString.wrap(password)
    connectivitySyncIntervalHours = intervalHours
    connectivitySyncSettingsReady = true
}
