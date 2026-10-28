package com.github.kr328.clash.service.runtimeyaml

import android.content.Context
import com.github.kr328.clash.service.connectivitysync.ConnectivitySyncSettings
import com.github.kr328.clash.service.store.ServiceStore

internal data class RuntimeYamlAutoDownloadSettings(
    val url: String,
    val username: String,
    val password: String,
    val intervalHours: Int,
) {
    fun isEnabled(): Boolean = intervalHours > 0 && isConfigured()

    fun isConfigured(): Boolean {
        return url.isNotBlank() &&
            username.isNotBlank() &&
            password.isNotBlank() &&
            url.trim().startsWith("https://", ignoreCase = true)
    }

    companion object {
        fun load(context: Context): RuntimeYamlAutoDownloadSettings {
            val webdav = ConnectivitySyncSettings.load(context)
            val store = ServiceStore(context)
            return RuntimeYamlAutoDownloadSettings(
                url = webdav.url,
                username = webdav.username,
                password = webdav.password,
                intervalHours = RuntimeYamlAutoDownloadPolicy.acceptedInterval(
                    store.runtimeYamlAutoDownloadIntervalHours,
                ),
            )
        }
    }
}

fun ServiceStore.publishRuntimeYamlAutoDownloadInterval(intervalHours: Int) {
    runtimeYamlAutoDownloadIntervalHours =
        RuntimeYamlAutoDownloadPolicy.acceptedInterval(intervalHours)
}
